package com.example.datacraft.desktop.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.datacraft.desktop.api.EngineModels.JobInfo;
import com.example.datacraft.desktop.api.EngineModels.JobResult;
import com.example.datacraft.desktop.api.EngineModels.JobStatus;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class EngineClientTest {

  private static final String TOKEN = "t".repeat(40);
  private final HttpTransport transport = new HttpTransport();
  private final StubServer server = new StubServer();
  private final EngineClient client = new EngineClient(transport, server.uri(), Optional.of(TOKEN));

  @AfterEach
  void close() {
    server.close();
    transport.close();
  }

  @Test
  void healthIsReadWithoutTheToken() {
    server.on("GET /health", 200, "{\"status\":\"UP\",\"service\":\"datacraft-api\"}");
    var health = client.health().join();
    assertTrue(health.up());
    assertEquals("datacraft-api", health.service());
    assertNull(server.requests().getFirst().header("Authorization"));
  }

  @Test
  void theJobCatalogueIsReadWithTheToken() {
    server.on(
        "GET /jobs",
        200,
        "{\"jobs\":[{\"name\":\"noop\",\"description\":\"空作业\"},{\"name\":\"echo\"}]}");
    assertEquals(
        List.of(new JobInfo("noop", "空作业"), new JobInfo("echo", "")), client.jobs().join());
    assertEquals("Bearer " + TOKEN, server.requests().getFirst().header("Authorization"));
  }

  @Test
  void aRunSendsItsParametersPercentEncodedAndNoOrigin() {
    server.on(
        "POST /jobs/csv-profile/runs",
        200,
        "{\"jobName\":\"csv-profile\",\"status\":\"SUCCEEDED\",\"message\":\"2 rows\","
            + "\"metrics\":{\"rows\":\"2\",\"durationMillis\":\"37\"}}");
    Map<String, String> params = new LinkedHashMap<>();
    params.put("input", "/opt/datacraft/data/résumé 2026.csv");
    params.put("expectedRows", "2");
    JobResult result = client.run("csv-profile", params, Duration.ofSeconds(5)).join();
    assertEquals(
        new JobResult(
            "csv-profile",
            JobStatus.SUCCEEDED,
            "2 rows",
            Map.of("rows", "2", "durationMillis", "37")),
        result);
    assertEquals(Duration.ofMillis(37), result.duration());
    var request = server.requests().getFirst();
    assertEquals(
        "input=%2Fopt%2Fdatacraft%2Fdata%2Fr%C3%A9sum%C3%A9%202026.csv&expectedRows=2",
        request.query());
    assertNull(request.header("Origin"), "the engine refuses run requests that carry an Origin");
  }

  @Test
  void aFailedJobIsAResultNotAnException() {
    server.on(
        "POST /jobs/csv-profile/runs",
        500,
        "{\"jobName\":\"csv-profile\",\"status\":\"FAILED\",\"message\":\"Missing required parameter: input\","
            + "\"metrics\":{\"durationMillis\":\"1\"}}");
    JobResult result = client.run("csv-profile", Map.of(), Duration.ofSeconds(5)).join();
    assertEquals(JobStatus.FAILED, result.status());
    assertEquals("Missing required parameter: input", result.message());
  }

  @Test
  void metricsKeepTheOrderTheEngineSentThem() {
    server.on(
        "POST /jobs/csv-profile/runs",
        200,
        "{\"jobName\":\"csv-profile\",\"status\":\"SUCCEEDED\",\"message\":\"ok\","
            + "\"metrics\":{\"rows\":\"2\",\"columns\":\"3\",\"bytes\":\"10\",\"sha256\":\"ab\","
            + "\"input\":\"/a.csv\",\"durationMillis\":\"1\"}}");
    JobResult result = client.run("csv-profile", Map.of(), Duration.ofSeconds(5)).join();
    assertEquals(
        List.of("rows", "columns", "bytes", "sha256", "input", "durationMillis"),
        List.copyOf(result.metrics().keySet()));
  }

  /** A run route that answers only when the latch is released. */
  private java.util.concurrent.CountDownLatch slowRun() {
    java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    server.on(
        "POST /jobs/noop/runs",
        request -> {
          try {
            release.await(15, java.util.concurrent.TimeUnit.SECONDS);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
          return StubServer.Answer.json(
              200,
              "{\"jobName\":\"noop\",\"status\":\"SUCCEEDED\",\"message\":\"noop\",\"metrics\":{}}");
        });
    return release;
  }

  @Test
  void aRunThatOutlivesItsWaitLimitTimesOutRatherThanBeingUnreachable() {
    var release = slowRun();
    try {
      ApiException failure =
          ApiException.of(
                  assertThrows(
                      CompletionException.class,
                      () -> client.run("noop", Map.of(), Duration.ofMillis(300)).join()))
              .orElseThrow();
      assertEquals(ApiError.TIMED_OUT, failure.error());
    } finally {
      release.countDown();
    }
  }

  @Test
  void closingTheTransportDoesNotWaitForARunInProgress() {
    var release = slowRun();
    try {
      var run = client.run("noop", Map.of(), Duration.ofSeconds(30));
      // The request must be on the wire before the transport closes.
      long deadline = System.nanoTime() + 5_000_000_000L;
      while (server.requests().stream().noneMatch(r -> r.path().equals("/jobs/noop/runs"))
          && System.nanoTime() < deadline) {
        Thread.onSpinWait();
      }
      org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
          Duration.ofSeconds(3), transport::close);
      assertThrows(CompletionException.class, run::join);
    } finally {
      release.countDown();
    }
  }

  @Test
  void eachDocumentedRefusalBecomesItsError() {
    Map<Integer, ApiError> expected =
        Map.of(
            401,
            ApiError.UNAUTHORIZED,
            403,
            ApiError.FORBIDDEN,
            404,
            ApiError.NOT_FOUND,
            503,
            ApiError.BUSY,
            400,
            ApiError.REJECTED,
            502,
            ApiError.SERVER);
    expected.forEach(
        (status, error) -> {
          server.on("POST /jobs/x/runs", status, "{\"error\":\"code-" + status + "\"}");
          ApiException failure =
              failure(() -> client.run("x", Map.of(), Duration.ofSeconds(5)).join());
          assertEquals(error, failure.error(), "HTTP " + status);
          assertEquals(status, failure.status());
          assertEquals(status == 502 ? "code-502" : "code-" + status, failure.detail());
        });
  }

  @Test
  void anAnswerThatIsNotJsonIsAProtocolError() {
    server.on("GET /jobs", request -> new StubServer.Answer(200, "<html>proxy</html>", Map.of()));
    assertEquals(ApiError.PROTOCOL, failure(() -> client.jobs().join()).error());
    server.on("GET /jobs", 200, "{\"items\":[]}");
    assertEquals(ApiError.PROTOCOL, failure(() -> client.jobs().join()).error());
  }

  @Test
  void aSuccessfulResponseMustBeAJsonObject() {
    for (String body : List.of("", " ", "null", "[]", "42")) {
      server.on("GET /health", 200, body);
      assertEquals(ApiError.PROTOCOL, failure(() -> client.health().join()).error(), body);
    }
  }

  @Test
  void anEmptyErrorBodyStillPreservesTheHttpStatus() {
    for (String body : List.of("", " ", "null")) {
      server.on("GET /jobs", 401, body);
      ApiException refused = failure(() -> client.jobs().join());
      assertEquals(ApiError.UNAUTHORIZED, refused.error());
      assertEquals(401, refused.status());
    }
  }

  @Test
  void aClosedPortIsUnreachableAndSaysWhere() throws Exception {
    int port;
    try (ServerSocket socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }
    EngineClient nowhere =
        new EngineClient(transport, URI.create("http://127.0.0.1:" + port), Optional.empty());
    ApiException failure = failure(() -> nowhere.health().join());
    assertEquals(ApiError.UNREACHABLE, failure.error());
    assertTrue(failure.detail().startsWith("127.0.0.1:" + port + "："), failure.detail());
  }

  private static ApiException failure(Runnable call) {
    CompletionException wrapped = assertThrows(CompletionException.class, call::run);
    return ApiException.of(wrapped)
        .orElseThrow(() -> new AssertionError("not an ApiException", wrapped));
  }
}
