package com.example.datacraft.api;

import com.example.datacraft.common.Lifecycle;
import com.example.datacraft.engine.JobCatalog;
import com.example.datacraft.engine.JobExecutionEngine;
import com.example.datacraft.engine.JobExecutionRequest;
import com.example.datacraft.engine.JobExecutionResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * JSON HTTP API over a {@link JobCatalog}: {@code GET|HEAD /health}, {@code GET|HEAD /jobs} and
 * {@code POST /jobs/{name}/runs?lifecycle=dev&key=value}.
 *
 * <p>A run answers with the result JSON: 200 when it SUCCEEDED, 500 when it FAILED (including job
 * parameter rejections). Malformed requests (job path, lifecycle, percent-escapes that are not
 * UTF-8, raw non-ASCII query bytes that reach the handler) get 400 {@code invalid_request}. A
 * request target that {@link URI} itself rejects (a bad percent-escape, or raw bytes such as
 * unencoded UTF-8 for {@code €}) is refused by the JDK server before any handler runs, with its
 * plain HTML 400. Runs sent with an {@code Origin} header (from a browser) get 403; unknown routes
 * and jobs get 404; other methods get 405 with {@code Allow}; an unexpected handler failure gets
 * 500 {@code internal_error}.
 */
public final class EngineHttpServer implements AutoCloseable {

  /**
   * Drain window on close; with the 1 s executor wait it stays below Docker's 10 s stop timeout.
   */
  private static final int STOP_GRACE_SECONDS = 8;

  private static final System.Logger LOGGER = System.getLogger(EngineHttpServer.class.getName());

  private final HttpServer server;
  private final ExecutorService executorService;

  private EngineHttpServer(HttpServer server, ExecutorService executorService) {
    this.server = server;
    this.executorService = executorService;
  }

  public static EngineHttpServer start(EngineHttpServerConfig config, JobCatalog catalog)
      throws IOException {
    JobExecutionEngine engine = new JobExecutionEngine(catalog);
    HttpServer server =
        HttpServer.create(new InetSocketAddress(config.host(), config.port()), config.backlog());
    ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();
    EngineHttpServer engineHttpServer = new EngineHttpServer(server, executorService);

    server.createContext("/health", guarded(exchange -> engineHttpServer.handleHealth(exchange)));
    server.createContext(
        "/jobs", guarded(exchange -> engineHttpServer.handleJobs(exchange, catalog, engine)));
    // Longest-prefix matching keeps /health and /jobs on their handlers; any other path gets the
    // JSON error contract instead of the JDK's HTML 404.
    server.createContext(
        "/",
        guarded(exchange -> HttpJsonResponse.write(exchange, 404, EngineJson.error("not_found"))));
    server.setExecutor(executorService);
    server.start();
    return engineHttpServer;
  }

  public URI uri(String path) {
    String host = server.getAddress().getHostString();
    if (host.contains(":")) {
      host = "[" + host + "]";
    }
    return URI.create("http://" + host + ":" + server.getAddress().getPort() + path);
  }

  /**
   * Stops accepting connections at once, then waits up to 8 s for in-flight exchanges (returning as
   * soon as they finish) before closing every connection; runs still going after that are cut off.
   * The JDK server also waits out the full 8 s when a client disconnects while the drain starts.
   */
  @Override
  public void close() {
    server.stop(STOP_GRACE_SECONDS);
    executorService.shutdown();
    try {
      if (!executorService.awaitTermination(1, TimeUnit.SECONDS)) {
        executorService.shutdownNow();
      }
    } catch (InterruptedException exception) {
      executorService.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Answers and closes the exchange whatever the handler throws. The JDK server closes the
   * connection without a response on a RuntimeException, and neither answers nor closes it on an
   * Error, so the client would wait forever.
   */
  private static HttpHandler guarded(HttpHandler handler) {
    return exchange -> {
      try {
        handler.handle(exchange);
      } catch (RuntimeException | Error failure) {
        if (failure instanceof RuntimeException) {
          // Raw path: no query parameter values, and no decoded CR/LF in the log line.
          LOGGER.log(
              System.Logger.Level.ERROR,
              "Request "
                  + exchange.getRequestMethod()
                  + " "
                  + exchange.getRequestURI().getRawPath()
                  + " failed",
              failure);
        }
        try {
          HttpJsonResponse.write(exchange, 500, EngineJson.error("internal_error"));
        } catch (IOException | RuntimeException writeFailure) {
          // Headers already sent or the client is gone: closing is all that is left.
          failure.addSuppressed(writeFailure);
          exchange.close();
        }
        if (failure instanceof Error error) {
          // Rethrown so the thread's uncaught-exception handler prints it. A job run only gets here
          // with a VirtualMachineError; the engine turns every other Throwable into a result.
          throw error;
        }
      }
    };
  }

  private static boolean isGetOrHead(String method) {
    return "GET".equals(method) || "HEAD".equals(method);
  }

  private void handleHealth(HttpExchange exchange) throws IOException {
    if (!"/health".equals(exchange.getRequestURI().getPath())) {
      HttpJsonResponse.write(exchange, 404, EngineJson.error("not_found"));
      return;
    }
    if (!isGetOrHead(exchange.getRequestMethod())) {
      exchange.getResponseHeaders().set("Allow", "GET, HEAD");
      HttpJsonResponse.write(exchange, 405, EngineJson.error("method_not_allowed"));
      return;
    }
    HttpJsonResponse.write(exchange, 200, EngineJson.health());
  }

  private void handleJobs(HttpExchange exchange, JobCatalog catalog, JobExecutionEngine engine)
      throws IOException {
    String path = exchange.getRequestURI().getPath();
    if ("/jobs".equals(path)) {
      if (isGetOrHead(exchange.getRequestMethod())) {
        HttpJsonResponse.write(exchange, 200, EngineJson.jobs(catalog));
      } else {
        exchange.getResponseHeaders().set("Allow", "GET, HEAD");
        HttpJsonResponse.write(exchange, 405, EngineJson.error("method_not_allowed"));
      }
      return;
    }
    if (path.startsWith("/jobs/") && path.endsWith("/runs")) {
      if (!"POST".equals(exchange.getRequestMethod())) {
        exchange.getResponseHeaders().set("Allow", "POST");
        HttpJsonResponse.write(exchange, 405, EngineJson.error("method_not_allowed"));
        return;
      }
      // Browsers attach Origin to every POST, including DNS-rebound same-origin ones; curl,
      // java.net.http and Airflow do not. Loopback binding alone cannot stop a local browser.
      if (exchange.getRequestHeaders().containsKey("Origin")) {
        HttpJsonResponse.write(exchange, 403, EngineJson.error("cross_origin_forbidden"));
        return;
      }
      try {
        runJob(exchange, catalog, engine, path);
      } catch (IllegalArgumentException exception) {
        HttpJsonResponse.write(exchange, 400, EngineJson.error("invalid_request"));
      }
      return;
    }
    HttpJsonResponse.write(exchange, 404, EngineJson.error("not_found"));
  }

  private void runJob(
      HttpExchange exchange, JobCatalog catalog, JobExecutionEngine engine, String path)
      throws IOException {
    // URI.getPath() is already percent-decoded, and '+' is a literal path character.
    if (path.length() <= "/jobs/".length() + "/runs".length()) {
      throw new IllegalArgumentException("Missing job name.");
    }
    String jobName = path.substring("/jobs/".length(), path.length() - "/runs".length());
    if (jobName.isBlank() || jobName.contains("/")) {
      throw new IllegalArgumentException("Invalid job path.");
    }
    Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
    Lifecycle lifecycle = Lifecycle.fromName(query.getOrDefault("lifecycle", "dev"));
    query.remove("lifecycle");
    if (catalog.find(jobName).isEmpty()) {
      HttpJsonResponse.write(exchange, 404, EngineJson.error("unknown_job"));
      return;
    }
    JobExecutionResult result = engine.execute(JobExecutionRequest.of(jobName, lifecycle, query));
    HttpJsonResponse.write(
        exchange,
        result.status().name().equals("SUCCEEDED") ? 200 : 500,
        EngineJson.result(result));
  }

  private static String decode(String value) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    for (int i = 0; i < value.length(); ) {
      char ch = value.charAt(i);
      if (ch == '%') {
        if (i + 2 >= value.length()) {
          throw new IllegalArgumentException("Incomplete URL escape.");
        }
        int high = Character.digit(value.charAt(i + 1), 16);
        int low = Character.digit(value.charAt(i + 2), 16);
        if (high < 0 || low < 0) {
          throw new IllegalArgumentException("Invalid URL escape.");
        }
        bytes.write((high << 4) | low);
        i += 3;
      } else {
        // The JDK server maps each raw request-line byte to one char, so a char above 0x7F is a
        // byte of unencoded UTF-8; re-encoding it would silently double-decode the value.
        if (ch > 0x7F) {
          throw new IllegalArgumentException("Query must be percent-encoded UTF-8.");
        }
        bytes.write(ch == '+' ? ' ' : ch);
        i++;
      }
    }
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes.toByteArray()))
          .toString();
    } catch (CharacterCodingException exception) {
      throw new IllegalArgumentException("Query must use UTF-8.", exception);
    }
  }

  private static Map<String, String> parseQuery(String rawQuery) {
    TreeMap<String, String> parameters = new TreeMap<>();
    if (rawQuery == null || rawQuery.isBlank()) {
      return parameters;
    }
    for (String pair : rawQuery.split("&")) {
      String[] parts = pair.split("=", 2);
      String key = decode(parts[0]);
      String value = parts.length == 2 ? decode(parts[1]) : "";
      if (!key.isBlank()) {
        parameters.put(key, value);
      }
    }
    return parameters;
  }
}
