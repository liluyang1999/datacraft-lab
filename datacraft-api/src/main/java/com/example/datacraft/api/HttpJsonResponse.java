package com.example.datacraft.api;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class HttpJsonResponse {

  private HttpJsonResponse() {}

  static void write(HttpExchange exchange, int statusCode, String json) throws IOException {
    byte[] payload = json.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(statusCode, payload.length);
    exchange.getResponseBody().write(payload);
    exchange.close();
  }
}
