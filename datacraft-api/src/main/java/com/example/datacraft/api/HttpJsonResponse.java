package com.example.datacraft.api;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class HttpJsonResponse {

  private HttpJsonResponse() {}

  /**
   * Sends {@code json} with {@code statusCode} and closes the exchange. A HEAD request gets the
   * same status and headers, including the Content-Length a GET would carry, but no body.
   */
  static void write(HttpExchange exchange, int statusCode, String json) throws IOException {
    byte[] payload = json.getBytes(StandardCharsets.UTF_8);
    try (exchange) {
      exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
      if ("HEAD".equals(exchange.getRequestMethod())) {
        // The JDK requires -1 (no body) for HEAD and leaves Content-Length to the handler.
        exchange.getResponseHeaders().set("Content-Length", Integer.toString(payload.length));
        exchange.sendResponseHeaders(statusCode, -1);
        return;
      }
      exchange.sendResponseHeaders(statusCode, payload.length);
      exchange.getResponseBody().write(payload);
    }
  }
}
