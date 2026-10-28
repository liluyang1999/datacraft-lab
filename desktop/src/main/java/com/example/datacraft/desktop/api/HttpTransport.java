package com.example.datacraft.desktop.api;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * The one HTTP client of the app: HTTP/1.1 with keep-alive, a 3 s connect timeout, virtual threads,
 * no redirects and no {@code Origin} header (the engine refuses run requests that carry one). Every
 * request has its own total time limit. A failure to connect becomes an {@link ApiException} with
 * {@link ApiError#UNREACHABLE}, and a request not answered within its limit one with {@link
 * ApiError#TIMED_OUT}.
 */
public final class HttpTransport implements AutoCloseable {

  /** A received answer: status, body bytes, and how long the round trip took. */
  public record Response(int status, byte[] body, Duration latency, Map<String, String> headers) {

    public String text() {
      return new String(body, StandardCharsets.UTF_8);
    }

    public String header(String name) {
      return headers.getOrDefault(name.toLowerCase(java.util.Locale.ROOT), "");
    }
  }

  public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
  public static final Duration READ_TIMEOUT = Duration.ofSeconds(10);
  static final String USER_AGENT = "datacraft-desktop/0.1";

  private final ObjectMapper json =
      new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
  private final HttpClient client;

  public HttpTransport() {
    this.client =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();
  }

  /** A request builder for {@code base + path} with the app's headers and the given time limit. */
  public HttpRequest.Builder request(URI base, String path, Duration timeout) {
    String root = base.toString().replaceAll("/+$", "");
    return HttpRequest.newBuilder(URI.create(root + path))
        .timeout(timeout)
        .header("User-Agent", USER_AGENT)
        .header("Accept", "application/json");
  }

  /**
   * Makes cancelling {@code derived} cancel {@code source} too, so that giving up on a result also
   * aborts the HTTP exchange behind it instead of leaving the connection waiting.
   */
  public static <T, U> CompletableFuture<U> linked(
      CompletableFuture<T> source, CompletableFuture<U> derived) {
    derived.whenComplete(
        (value, failure) -> {
          if (derived.isCancelled()) {
            source.cancel(true);
          }
        });
    return derived;
  }

  /**
   * Sends the request; the future completes with any HTTP answer, or fails with UNREACHABLE or
   * TIMED_OUT. Cancelling the future aborts the exchange.
   */
  public CompletableFuture<Response> send(HttpRequest request) {
    long started = System.nanoTime();
    CompletableFuture<HttpResponse<byte[]>> exchange =
        client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
    return linked(
        exchange,
        exchange.handle(
            (response, failure) -> {
              if (failure != null) {
                if (cancelled(failure)) {
                  // The caller gave up; that is not a failure of the backend.
                  CancellationException cancelled = new CancellationException("request cancelled");
                  cancelled.initCause(failure);
                  throw cancelled;
                }
                throw notAnswered(request.uri(), failure);
              }
              Map<String, String> headers =
                  response.headers().map().entrySet().stream()
                      .collect(
                          Collectors.toMap(
                              entry -> entry.getKey().toLowerCase(java.util.Locale.ROOT),
                              entry -> String.join(",", entry.getValue()),
                              (first, second) -> first));
              return new Response(
                  response.statusCode(),
                  response.body(),
                  Duration.ofNanos(System.nanoTime() - started),
                  headers);
            }));
  }

  public ObjectMapper json() {
    return json;
  }

  /**
   * Parses the JSON object returned by either API; an empty or other-shaped body is a protocol
   * error.
   */
  public JsonNode parse(Response response) {
    try {
      JsonNode body = json.readTree(response.body());
      if (body == null || !body.isObject()) {
        throw new ApiException(
            ApiError.PROTOCOL, response.status(), "the answer is not a JSON object");
      }
      return body;
    } catch (IOException exception) {
      throw new ApiException(
          ApiError.PROTOCOL, response.status(), "the answer is not JSON", exception);
    }
  }

  /** {@code k=v&...} with each key and value percent-encoded as UTF-8; spaces as {@code %20}. */
  public static String query(Map<String, String> params) {
    return params.entrySet().stream()
        .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
        .collect(Collectors.joining("&"));
  }

  /** One path segment or query part, percent-encoded as UTF-8. */
  public static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  static ApiException notAnswered(URI uri, Throwable failure) {
    Throwable cause =
        failure instanceof CompletionException && failure.getCause() != null
            ? failure.getCause()
            : failure;
    String where = uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
    String reason;
    if (cause instanceof HttpConnectTimeoutException) {
      reason = "连接超时";
    } else if (cause instanceof HttpTimeoutException) {
      // Sent, but not answered in time: the server may still be working on it.
      return new ApiException(ApiError.TIMED_OUT, 0, where + "：等待响应超时", cause);
    } else if (hasCause(cause, UnresolvedAddressException.class)
        || hasCause(cause, UnknownHostException.class)) {
      reason = "无法解析主机名";
    } else if (cause instanceof ConnectException) {
      reason = "连接被拒绝";
    } else {
      reason =
          cause.getClass().getSimpleName()
              + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
    }
    return new ApiException(ApiError.UNREACHABLE, 0, where + "：" + reason, cause);
  }

  /** Whether a future failed because someone cancelled it, at any depth of wrapping. */
  public static boolean cancelled(Throwable failure) {
    return hasCause(failure, CancellationException.class);
  }

  private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (type.isInstance(cause)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public void close() {
    // close() would wait for every exchange in flight, a job run among them (up to its wait limit):
    // closing the window must not keep the process alive, so in-flight requests are cancelled.
    client.shutdownNow();
    try {
      client.awaitTermination(Duration.ofSeconds(2));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
