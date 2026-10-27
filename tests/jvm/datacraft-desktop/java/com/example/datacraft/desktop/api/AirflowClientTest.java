package com.example.datacraft.desktop.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AirflowClientTest {

  private static final Instant NOW = Instant.parse("2026-10-27T10:00:00Z");
  private final HttpTransport transport = new HttpTransport();
  private final StubServer server = new StubServer();
  private final AtomicInteger logins = new AtomicInteger();

  @AfterEach
  void close() {
    server.close();
    transport.close();
  }

  private AirflowClient client(Clock clock) {
    return new AirflowClient(transport, server.uri(), "admin", Optional.of("pw"), clock);
  }

  private void issuesTokens(Duration lifetime) {
    server.on(
        "POST /auth/token",
        request -> {
          int number = logins.incrementAndGet();
          return StubServer.Answer.json(
              201, "{\"access_token\":\"" + jwt(number, NOW.plus(lifetime)) + "\"}");
        });
  }

  @Test
  void signsInOnceAndListsTheDags() {
    issuesTokens(Duration.ofHours(1));
    server.on(
        "GET /api/v2/dags",
        200,
        "{\"total_entries\":1,\"dags\":[{\"dag_id\":\"datacraft_spark_etl\",\"dag_display_name\":\"datacraft_spark_etl\","
            + "\"is_paused\":true,\"description\":null,\"timetable_summary\":null,"
            + "\"tags\":[{\"name\":\"spark\",\"dag_id\":\"datacraft_spark_etl\"}],\"has_import_errors\":false,"
            + "\"next_dagrun_run_after\":null}]}");
    AirflowClient client = client(Clock.fixed(NOW, ZoneOffset.UTC));
    var dags = client.dags().join();
    client.dags().join();
    assertEquals(1, logins.get(), "the token is reused");
    assertEquals("datacraft_spark_etl", dags.getFirst().dagId());
    assertTrue(dags.getFirst().paused());
    assertEquals(List.of("spark"), dags.getFirst().tags());
    var login = server.requests().getFirst();
    assertEquals("{\"username\":\"admin\",\"password\":\"pw\"}", login.body());
    assertTrue(server.requests().get(1).header("Authorization").startsWith("Bearer "));
    assertEquals("limit=100&order_by=dag_id&exclude_stale=true", server.requests().get(1).query());
  }

  @Test
  void aTokenAboutToExpireIsRenewedBeforeTheRequest() {
    issuesTokens(Duration.ofSeconds(30));
    server.on("GET /api/v2/version", 200, "{\"version\":\"3.3.2\",\"git_version\":null}");
    AirflowClient client = client(Clock.fixed(NOW, ZoneOffset.UTC));
    assertEquals("3.3.2", client.version().join());
    assertEquals("3.3.2", client.version().join());
    assertEquals(2, logins.get(), "a token valid for less than 60 s is not used");
  }

  @Test
  void a401SignsInAgainOnceAndThenGivesUp() {
    issuesTokens(Duration.ofHours(1));
    AtomicInteger calls = new AtomicInteger();
    server.on(
        "GET /api/v2/version",
        request ->
            calls.incrementAndGet() == 1
                ? StubServer.Answer.json(401, "{\"detail\":\"Invalid JWT token\"}")
                : StubServer.Answer.json(200, "{\"version\":\"3.3.2\"}"));
    AirflowClient client = client(Clock.fixed(NOW, ZoneOffset.UTC));
    assertEquals("3.3.2", client.version().join());
    assertEquals(2, logins.get());

    server.on("GET /api/v2/version", 401, "{\"detail\":\"Invalid JWT token\"}");
    ApiException failure = failure(() -> client.version().join());
    assertEquals(ApiError.UNAUTHORIZED, failure.error());
    assertEquals(3, logins.get(), "one more sign-in, not a loop");
  }

  @Test
  void aWrongPasswordIsUnauthorized() {
    server.on("POST /auth/token", 401, "{\"detail\":\"Invalid credentials\"}");
    ApiException failure = failure(() -> client(Clock.systemUTC()).dags().join());
    assertEquals(ApiError.UNAUTHORIZED, failure.error());
    assertEquals("Invalid credentials", failure.detail());
  }

  @Test
  void aMissingPasswordFailsBeforeAnyRequest() {
    AirflowClient client =
        new AirflowClient(transport, server.uri(), "admin", Optional.empty(), Clock.systemUTC());
    assertEquals(ApiError.UNAUTHORIZED, failure(() -> client.dags().join()).error());
    assertTrue(server.requests().isEmpty());
  }

  @Test
  void triggeringSendsANullLogicalDateTheConfAndTheNote() {
    issuesTokens(Duration.ofHours(1));
    server.on(
        "POST /api/v2/dags/datacraft_spark_etl/dagRuns",
        200,
        "{\"dag_run_id\":\"manual__2026-10-27T10:00:00+00:00\",\"dag_id\":\"datacraft_spark_etl\",\"state\":\"queued\","
            + "\"run_type\":\"manual\",\"run_after\":\"2026-10-27T10:00:00Z\",\"conf\":{\"input\":\"/a.csv\"},"
            + "\"queued_at\":\"2026-10-27T10:00:01.123456Z\",\"start_date\":null,\"end_date\":null,\"duration\":null}");
    var run =
        client(Clock.fixed(NOW, ZoneOffset.UTC))
            .trigger("datacraft_spark_etl", Map.of("input", "/a.csv"), " 桌面端触发 ")
            .join();
    assertEquals(
        "{\"logical_date\":null,\"conf\":{\"input\":\"/a.csv\"},\"note\":\"桌面端触发\"}",
        server.requests().get(1).body());
    assertEquals(RunTone.WAITING, run.tone());
    assertEquals(Map.of("input", "/a.csv"), run.conf());
    assertEquals(Optional.of(Instant.parse("2026-10-27T10:00:01.123456Z")), run.queuedAt());
  }

  @Test
  void aRunWhoseConfHoldsANullIsStillListed() {
    issuesTokens(Duration.ofHours(1));
    server.on(
        "GET /api/v2/dags/datacraft_spark_etl/dagRuns",
        200,
        "{\"dag_runs\":[{\"dag_run_id\":\"manual__1\",\"dag_id\":\"datacraft_spark_etl\",\"state\":\"success\","
            + "\"run_type\":\"manual\",\"run_after\":\"2026-10-27T10:00:00Z\","
            + "\"conf\":{\"input\":\"/a.csv\",\"schema\":null},\"start_date\":null,\"end_date\":null,"
            + "\"duration\":null}],\"total_entries\":1}");
    var runs = client(Clock.fixed(NOW, ZoneOffset.UTC)).runs("datacraft_spark_etl", 20).join();
    assertEquals(1, runs.size());
    assertTrue(runs.getFirst().conf().containsKey("schema"));
    assertEquals(List.of("input", "schema"), List.copyOf(runs.getFirst().conf().keySet()));
  }

  @Test
  void runIdsAreEncodedAsOnePathSegment() {
    issuesTokens(Duration.ofHours(1));
    server.on(
        "GET /api/v2/dags/d/dagRuns/manual__2026-10-27T10%3A00%3A00%2B00%3A00/taskInstances",
        200,
        "{\"task_instances\":[{\"task_id\":\"convert\",\"task_display_name\":\"convert\",\"map_index\":-1,"
            + "\"state\":null,\"try_number\":0,\"max_tries\":1,\"duration\":1.5,\"operator_name\":\"BashOperator\"}]}");
    var tasks =
        client(Clock.fixed(NOW, ZoneOffset.UTC))
            .taskInstances("d", "manual__2026-10-27T10:00:00+00:00")
            .join();
    assertEquals(RunTone.NONE, tasks.getFirst().tone());
    assertEquals(Optional.of(Duration.ofMillis(1500)), tasks.getFirst().duration());
    assertEquals("BashOperator", tasks.getFirst().operator());
  }

  @Test
  void aValidationErrorIsRejectedWithItsFields() {
    issuesTokens(Duration.ofHours(1));
    server.on(
        "POST /api/v2/dags/d/dagRuns",
        422,
        "{\"detail\":[{\"loc\":[\"body\",\"conf\",\"input\"],\"msg\":\"field required\",\"type\":\"missing\"}]}");
    ApiException failure =
        failure(() -> client(Clock.fixed(NOW, ZoneOffset.UTC)).trigger("d", Map.of(), "").join());
    assertEquals(ApiError.REJECTED, failure.error());
    assertEquals("conf.input: field required", failure.detail());
  }

  @Test
  void aRunIdConflictIsRejectedWithTheReasonAndWithoutTheStatement() {
    issuesTokens(Duration.ofHours(1));
    server.on(
        "POST /api/v2/dags/d/dagRuns",
        409,
        "{\"detail\":{\"reason\":\"Unique constraint violation\","
            + "\"statement\":\"INSERT INTO dag_run (dag_id, run_id) VALUES (?, ?)\","
            + "\"orig_error\":\"duplicate key value violates unique constraint\"}}");
    ApiException failure =
        failure(() -> client(Clock.fixed(NOW, ZoneOffset.UTC)).trigger("d", Map.of(), "").join());
    assertEquals(ApiError.REJECTED, failure.error());
    assertEquals(409, failure.status());
    assertEquals("Unique constraint violation", failure.detail());
  }

  @Test
  void healthReadsEveryComponentWithoutSigningIn() {
    server.on(
        "GET /api/v2/monitor/health",
        200,
        "{\"metadatabase\":{\"status\":\"healthy\"},"
            + "\"scheduler\":{\"status\":\"healthy\",\"latest_scheduler_heartbeat\":\"2026-10-27T09:59:58+00:00\"},"
            + "\"triggerer\":{\"status\":\"unhealthy\",\"latest_triggerer_heartbeat\":null},\"dag_processor\":null}");
    var health = client(Clock.systemUTC()).health().join();
    assertFalse(health.healthy());
    assertEquals(
        Optional.of(Instant.parse("2026-10-27T09:59:58Z")), health.scheduler().heartbeat());
    assertTrue(health.dagProcessor().isEmpty());
    assertEquals(0, logins.get());
  }

  @Test
  void logsAreReadInPartsAndStructuredEntriesBecomeLines() {
    issuesTokens(Duration.ofHours(1));
    server.on(
        "GET /api/v2/dags/d/dagRuns/r/taskInstances/t/logs/1",
        request ->
            StubServer.Answer.json(
                200,
                request.query().contains("token=")
                    ? "{\"content\":[\"plain line\"],\"continuation_token\":null}"
                    : "{\"content\":[{\"timestamp\":\"2026-10-27T10:00:00Z\",\"level\":\"info\",\"event\":\"Task started\"}],"
                        + "\"continuation_token\":\"abc\"}"));
    AirflowClient client = client(Clock.fixed(NOW, ZoneOffset.UTC));
    var first = client.log("d", "r", "t", -1, 1, null).join();
    assertEquals(List.of("2026-10-27T10:00:00Z  INFO  Task started"), first.lines());
    var second = client.log("d", "r", "t", -1, 1, first.next().orElseThrow()).join();
    assertEquals(List.of("plain line"), second.lines());
    assertTrue(second.next().isEmpty());
  }

  @Test
  void aMappedTaskLogAsksForItsMapIndex() {
    issuesTokens(Duration.ofHours(1));
    server.on(
        "GET /api/v2/dags/d/dagRuns/r/taskInstances/t/logs/2",
        200,
        "{\"content\":[\"mapped\"],\"continuation_token\":null}");
    var chunk = client(Clock.fixed(NOW, ZoneOffset.UTC)).log("d", "r", "t", 3, 2, null).join();
    assertEquals(List.of("mapped"), chunk.lines());
    assertTrue(
        server.requests().getLast().query().contains("map_index=3"),
        server.requests().getLast().query());
  }

  @Test
  void theExpiryIsReadFromTheTokenItself() {
    assertEquals(Optional.of(NOW), AirflowClient.expiry(jwt(1, NOW)));
    assertEquals(Optional.empty(), AirflowClient.expiry("not-a-jwt"));
  }

  private static String jwt(int number, Instant expiresAt) {
    Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    String header = encoder.encodeToString("{\"alg\":\"HS512\"}".getBytes(StandardCharsets.UTF_8));
    String claims =
        encoder.encodeToString(
            ("{\"sub\":\"admin\",\"n\":" + number + ",\"exp\":" + expiresAt.getEpochSecond() + "}")
                .getBytes(StandardCharsets.UTF_8));
    return header + "." + claims + ".signature";
  }

  private static ApiException failure(Runnable call) {
    CompletionException wrapped = assertThrows(CompletionException.class, call::run);
    return ApiException.of(wrapped)
        .orElseThrow(() -> new AssertionError("not an ApiException", wrapped));
  }
}
