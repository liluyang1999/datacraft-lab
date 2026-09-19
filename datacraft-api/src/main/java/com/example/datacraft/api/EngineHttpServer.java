package com.example.datacraft.api;

import com.example.datacraft.common.Lifecycle;
import com.example.datacraft.engine.JobCatalog;
import com.example.datacraft.engine.JobExecutionEngine;
import com.example.datacraft.engine.JobExecutionRequest;
import com.example.datacraft.engine.JobExecutionResult;
import com.sun.net.httpserver.HttpExchange;
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

public final class EngineHttpServer implements AutoCloseable {

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

    server.createContext("/health", exchange -> engineHttpServer.handleHealth(exchange));
    server.createContext(
        "/jobs", exchange -> engineHttpServer.handleJobs(exchange, catalog, engine));
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

  @Override
  public void close() {
    server.stop(0);
    executorService.shutdownNow();
  }

  private void handleHealth(HttpExchange exchange) throws IOException {
    if (!"/health".equals(exchange.getRequestURI().getPath())) {
      HttpJsonResponse.write(exchange, 404, EngineJson.error("not_found"));
      return;
    }
    if (!"GET".equals(exchange.getRequestMethod())) {
      exchange.getResponseHeaders().set("Allow", "GET");
      HttpJsonResponse.write(exchange, 405, EngineJson.error("method_not_allowed"));
      return;
    }
    HttpJsonResponse.write(exchange, 200, EngineJson.health());
  }

  private void handleJobs(HttpExchange exchange, JobCatalog catalog, JobExecutionEngine engine)
      throws IOException {
    String path = exchange.getRequestURI().getPath();
    if ("/jobs".equals(path)) {
      if ("GET".equals(exchange.getRequestMethod())) {
        HttpJsonResponse.write(exchange, 200, EngineJson.jobs(catalog));
      } else {
        exchange.getResponseHeaders().set("Allow", "GET");
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
        int point = value.codePointAt(i);
        String text = ch == '+' ? " " : new String(Character.toChars(point));
        bytes.writeBytes(text.getBytes(StandardCharsets.UTF_8));
        i += Character.charCount(point);
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
