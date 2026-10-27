package com.example.datacraft.desktop.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** A loopback HTTP server that answers scripted responses and records every request. */
public final class StubServer implements AutoCloseable {

  /** A recorded request. */
  public record Request(
      String method, String path, String query, Map<String, List<String>> headers, String body) {
    public String header(String name) {
      return headers.entrySet().stream()
          .filter(entry -> entry.getKey().equalsIgnoreCase(name))
          .map(entry -> String.join(",", entry.getValue()))
          .findFirst()
          .orElse(null);
    }
  }

  /** A scripted answer. */
  public record Answer(int status, String body, Map<String, String> headers) {
    public static Answer json(int status, String body) {
      return new Answer(status, body, Map.of("Content-Type", "application/json"));
    }
  }

  private final HttpServer server;
  private final List<Request> requests = Collections.synchronizedList(new ArrayList<>());
  private final Map<String, Function<Request, Answer>> routes = new ConcurrentHashMap<>();

  public StubServer() {
    try {
      server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
    server.createContext("/", this::handle);
    // One thread per request, as a real server answers: a held request does not hold up others.
    server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
    server.start();
  }

  /** Answers requests whose "METHOD path" (without the query) equals the key. */
  public StubServer on(String methodAndPath, Function<Request, Answer> answer) {
    routes.put(methodAndPath, answer);
    return this;
  }

  public StubServer on(String methodAndPath, int status, String json) {
    return on(methodAndPath, request -> Answer.json(status, json));
  }

  public URI uri() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }

  public List<Request> requests() {
    synchronized (requests) {
      return List.copyOf(requests);
    }
  }

  private void handle(HttpExchange exchange) throws IOException {
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    Request request =
        new Request(
            exchange.getRequestMethod(),
            exchange.getRequestURI().getRawPath(),
            exchange.getRequestURI().getRawQuery(),
            Map.copyOf(exchange.getRequestHeaders()),
            body);
    requests.add(request);
    Function<Request, Answer> route = routes.get(request.method() + " " + request.path());
    Answer answer =
        route == null ? Answer.json(404, "{\"error\":\"not_found\"}") : route.apply(request);
    answer.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
    byte[] bytes = answer.body().getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(answer.status(), bytes.length == 0 ? -1 : bytes.length);
    if (bytes.length > 0) {
      exchange.getResponseBody().write(bytes);
    }
    exchange.close();
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
