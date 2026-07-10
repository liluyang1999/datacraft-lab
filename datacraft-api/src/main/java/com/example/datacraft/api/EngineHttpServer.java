package com.example.datacraft.api;

import com.example.datacraft.common.Lifecycle;
import com.example.datacraft.engine.JobCatalog;
import com.example.datacraft.engine.JobExecutionEngine;
import com.example.datacraft.engine.JobExecutionRequest;
import com.example.datacraft.engine.JobExecutionResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
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
    HttpServer server =
        HttpServer.create(new InetSocketAddress(config.host(), config.port()), config.backlog());
    ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();
    JobExecutionEngine engine = new JobExecutionEngine(catalog);
    EngineHttpServer engineHttpServer = new EngineHttpServer(server, executorService);

    server.createContext("/health", exchange -> engineHttpServer.handleHealth(exchange));
    server.createContext(
        "/jobs", exchange -> engineHttpServer.handleJobs(exchange, catalog, engine));
    server.setExecutor(executorService);
    server.start();
    return engineHttpServer;
  }

  public URI uri(String path) {
    return URI.create(
        "http://"
            + server.getAddress().getHostString()
            + ":"
            + server.getAddress().getPort()
            + path);
  }

  @Override
  public void close() {
    server.stop(0);
    executorService.shutdownNow();
  }

  private void handleHealth(HttpExchange exchange) throws IOException {
    if (!"GET".equals(exchange.getRequestMethod())) {
      HttpJsonResponse.write(exchange, 405, EngineJson.error("method_not_allowed"));
      return;
    }
    HttpJsonResponse.write(exchange, 200, EngineJson.health());
  }

  private void handleJobs(HttpExchange exchange, JobCatalog catalog, JobExecutionEngine engine)
      throws IOException {
    String path = exchange.getRequestURI().getPath();
    if ("/jobs".equals(path) && "GET".equals(exchange.getRequestMethod())) {
      HttpJsonResponse.write(exchange, 200, EngineJson.jobs(catalog));
      return;
    }
    if (path.startsWith("/jobs/")
        && path.endsWith("/runs")
        && "POST".equals(exchange.getRequestMethod())) {
      runJob(exchange, engine, path);
      return;
    }
    HttpJsonResponse.write(exchange, 404, EngineJson.error("not_found"));
  }

  private void runJob(HttpExchange exchange, JobExecutionEngine engine, String path)
      throws IOException {
    String jobName = decode(path.substring("/jobs/".length(), path.length() - "/runs".length()));
    Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
    Lifecycle lifecycle = Lifecycle.fromName(query.getOrDefault("lifecycle", "dev"));
    query.remove("lifecycle");
    JobExecutionResult result = engine.execute(JobExecutionRequest.of(jobName, lifecycle, query));
    HttpJsonResponse.write(
        exchange,
        result.status().name().equals("SUCCEEDED") ? 200 : 500,
        EngineJson.result(result));
  }

  private static String decode(String value) {
    return URLDecoder.decode(value, StandardCharsets.UTF_8);
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
