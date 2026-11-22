package com.example.datacraft.desktop.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.datacraft.desktop.api.AirflowModels.ActiveTask;
import com.example.datacraft.desktop.api.AirflowModels.ImportError;
import com.example.datacraft.desktop.api.AirflowModels.Pool;
import com.example.datacraft.desktop.api.EngineModels.Status;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The calls behind the monitor: the engine's status, Airflow's import errors, pools and tasks. */
class LiveClientsTest {

  private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
  private static final String TOKEN = "t".repeat(40);
  private final HttpTransport transport = new HttpTransport();
  private final StubServer server = new StubServer();

  @AfterEach
  void close() {
    server.close();
    transport.close();
  }

  private AirflowClient airflow() {
    server.on("POST /auth/token", 201, "{\"access_token\":\"" + jwt(NOW.plusSeconds(3600)) + "\"}");
    return new AirflowClient(
        transport, server.uri(), "admin", Optional.of("pw"), Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void theEngineStatusCarriesVersionLoadCountersAndActiveRunsWithTheToken() {
    server.on(
        "GET /status",
        request ->
            ("Bearer " + TOKEN).equals(request.header("Authorization"))
                ? StubServer.Answer.json(
                    200,
                    "{\"status\":\"UP\",\"service\":\"datacraft-api\",\"version\":\"2.3.4\","
                        + "\"startedAt\":\"2026-11-20T07:00:00Z\",\"uptimeMillis\":10800000,"
                        + "\"jobs\":7,\"maxConcurrentRuns\":4,\"runs\":{\"active\":2,\"started\":9,"
                        + "\"succeeded\":5,\"failed\":1,\"rejected\":3},\"activeRuns\":["
                        + "{\"job\":\"csv-profile\",\"startedAt\":\"2026-11-20T09:59:50Z\","
                        + "\"elapsedMillis\":10000},{\"job\":\"\",\"elapsedMillis\":1},"
                        + "{\"job\":\"echo\",\"elapsedMillis\":-5}]}")
                : StubServer.Answer.json(401, "{\"error\":\"unauthorized\"}"));
    EngineClient engine = new EngineClient(transport, server.uri(), Optional.of(TOKEN));

    Status status = engine.status().join();

    assertEquals("2.3.4", status.version());
    assertEquals(Instant.parse("2026-11-20T07:00:00Z"), status.startedAt());
    assertEquals(Duration.ofHours(3), status.uptime());
    assertEquals(7, status.jobs());
    assertEquals(4, status.maxConcurrentRuns());
    assertEquals(
        List.of(9L, 5L, 1L, 3L),
        List.of(status.started(), status.succeeded(), status.failed(), status.rejected()));
    // A run without a job name is dropped; a negative elapsed time becomes zero.
    assertEquals(2, status.active().size());
    assertEquals("csv-profile", status.active().get(0).job());
    assertEquals(Duration.ofSeconds(10), status.active().get(0).elapsed());
    assertEquals(Duration.ZERO, status.active().get(1).elapsed());

    EngineClient anonymous = new EngineClient(transport, server.uri(), Optional.empty());
    CompletionException refused =
        org.junit.jupiter.api.Assertions.assertThrows(
            CompletionException.class, () -> anonymous.status().join());
    assertEquals(ApiError.UNAUTHORIZED, ApiException.of(refused).orElseThrow().error());
  }

  @Test
  void anEngineWithoutTheStatusRouteIsNotFound() {
    server.on("GET /status", 404, "{\"error\":\"not_found\"}");
    EngineClient engine = new EngineClient(transport, server.uri(), Optional.of(TOKEN));
    CompletionException missing =
        org.junit.jupiter.api.Assertions.assertThrows(
            CompletionException.class, () -> engine.status().join());
    assertEquals(ApiError.NOT_FOUND, ApiException.of(missing).orElseThrow().error());
  }

  @Test
  void importErrorsArriveNewestFirstWithTheirStackTraces() {
    AirflowClient client = airflow();
    server.on(
        "GET /api/v2/importErrors",
        200,
        "{\"import_errors\":[{\"import_error_id\":7,\"timestamp\":\"2026-11-20T09:58:00Z\","
            + "\"filename\":\"/opt/airflow/dags/broken_dag.py\",\"bundle_name\":\"dags-folder\","
            + "\"stack_trace\":\"Traceback\\nSyntaxError: invalid syntax\"}],\"total_entries\":1}");

    List<ImportError> errors = client.importErrors().join();

    assertEquals(1, errors.size());
    assertEquals("broken_dag.py", errors.getFirst().shortName());
    assertEquals(Optional.of(Instant.parse("2026-11-20T09:58:00Z")), errors.getFirst().timestamp());
    assertTrue(errors.getFirst().stackTrace().contains("SyntaxError"));
    var request =
        server.requests().stream()
            .filter(each -> each.path().equals("/api/v2/importErrors"))
            .findFirst()
            .orElseThrow();
    assertEquals("limit=50&order_by=-timestamp", request.query());
  }

  @Test
  void poolsCarryTheirSlotsAndAFullPoolWithWaitingTasksIsExhausted() {
    AirflowClient client = airflow();
    server.on(
        "GET /api/v2/pools",
        200,
        "{\"pools\":[{\"name\":\"default_pool\",\"slots\":4,\"running_slots\":4,\"queued_slots\":2,"
            + "\"scheduled_slots\":1,\"deferred_slots\":0,\"open_slots\":0},"
            + "{\"name\":\"unlimited\",\"slots\":-1,\"running_slots\":3,\"queued_slots\":0,"
            + "\"scheduled_slots\":0,\"deferred_slots\":1,\"open_slots\":-1}],\"total_entries\":2}");

    List<Pool> pools = client.pools().join();

    assertEquals(new Pool("default_pool", 4, 4, 2, 1, 0, 0), pools.get(0));
    assertTrue(pools.get(0).exhausted());
    assertTrue(pools.get(1).unlimited());
    assertTrue(!pools.get(1).exhausted());
  }

  @Test
  void activeTasksAskForEveryLiveStateAcrossAllDags() {
    AirflowClient client = airflow();
    server.on(
        "GET /api/v2/dags/~/dagRuns/~/taskInstances",
        200,
        "{\"task_instances\":[{\"dag_id\":\"etl\",\"dag_run_id\":\"manual__1\",\"task_id\":\"load\","
            + "\"task_display_name\":\"Load\",\"map_index\":2,\"state\":\"up_for_retry\","
            + "\"try_number\":2,\"max_tries\":3,\"start_date\":null,"
            + "\"queued_when\":\"2026-11-20T09:59:00Z\"},{\"task_id\":\"\"}],\"total_entries\":2}");

    List<ActiveTask> tasks = client.activeTasks().join();

    assertEquals(1, tasks.size());
    ActiveTask task = tasks.getFirst();
    assertEquals("etl", task.dagId());
    assertEquals("Load", task.displayName());
    assertEquals(2, task.mapIndex());
    assertEquals(RunTone.WAITING, task.tone());
    assertEquals(Optional.empty(), task.startDate());
    assertEquals(Optional.of(Instant.parse("2026-11-20T09:59:00Z")), task.queuedAt());
    String query =
        server.requests().stream()
            .filter(each -> each.path().equals("/api/v2/dags/~/dagRuns/~/taskInstances"))
            .findFirst()
            .orElseThrow()
            .query();
    assertTrue(query.startsWith("limit=50&order_by=-start_date"), query);
    for (String state : AirflowClient.ACTIVE_STATES) {
      assertTrue(query.contains("&state=" + state), query);
    }
  }

  private static String jwt(Instant expiresAt) {
    Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    String header = encoder.encodeToString("{\"alg\":\"HS512\"}".getBytes(StandardCharsets.UTF_8));
    String claims =
        encoder.encodeToString(
            ("{\"sub\":\"admin\",\"exp\":" + expiresAt.getEpochSecond() + "}")
                .getBytes(StandardCharsets.UTF_8));
    return header + "." + claims + ".signature";
  }
}
