package com.example.datacraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.datacraft.desktop.api.AirflowClient;
import com.example.datacraft.desktop.api.AirflowModels.DagRun;
import com.example.datacraft.desktop.api.ApiError;
import com.example.datacraft.desktop.api.ApiException;
import com.example.datacraft.desktop.api.EngineClient;
import com.example.datacraft.desktop.api.EngineModels.JobInfo;
import com.example.datacraft.desktop.api.EngineModels.JobResult;
import com.example.datacraft.desktop.api.EngineModels.JobStatus;
import com.example.datacraft.desktop.api.HttpTransport;
import com.example.datacraft.desktop.api.RunTone;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * The desktop clients against real backends: a running {@code serve-api} and a deployed Airflow.
 * Tagged e2e and left out of the normal build; run it with {@code -Pe2e} and these variables:
 *
 * <ul>
 *   <li>DATACRAFT_E2E_ENGINE_URL and DATACRAFT_E2E_ENGINE_TOKEN (empty when the API has none);
 *       DATACRAFT_E2E_DATA_DIR, a directory both this test and the engine read, for csv-profile;
 *   <li>DATACRAFT_E2E_AIRFLOW_URL, DATACRAFT_E2E_AIRFLOW_USER and DATACRAFT_E2E_AIRFLOW_PASSWORD.
 * </ul>
 *
 * <p>Nothing secret is printed: the variables are read here and only sent to their own backend.
 */
@Tag("e2e")
class EndToEndTest {

  private static final HttpTransport TRANSPORT = new HttpTransport();

  @AfterAll
  static void close() {
    TRANSPORT.close();
  }

  private static String env(String name) {
    String value = System.getenv(name);
    return value == null ? "" : value.strip();
  }

  private static ApiException failure(Runnable call) {
    try {
      call.run();
    } catch (CompletionException wrapped) {
      return ApiException.of(wrapped).orElseThrow(() -> wrapped);
    }
    throw new AssertionError("the call succeeded");
  }

  @Nested
  @EnabledIfEnvironmentVariable(named = "DATACRAFT_E2E_ENGINE_URL", matches = ".+")
  class Engine {

    private final URI url = URI.create(env("DATACRAFT_E2E_ENGINE_URL"));
    private final EngineClient engine =
        new EngineClient(TRANSPORT, url, Optional.of(env("DATACRAFT_E2E_ENGINE_TOKEN")));

    @Test
    void isHealthyAndListsTheProjectsJobs() {
      assertTrue(engine.health().join().up());
      List<String> names = engine.jobs().join().stream().map(JobInfo::name).toList();
      assertTrue(
          names.containsAll(List.of("csv-profile", "file-checksum", "echo", "noop")),
          names.toString());
    }

    @Test
    void echoesChineseTextUnchanged() {
      JobResult result =
          engine.run("echo", Map.of("message", "数艺工坊 端到端 ok"), Duration.ofSeconds(30)).join();
      assertEquals(JobStatus.SUCCEEDED, result.status());
      assertEquals("数艺工坊 端到端 ok", result.message());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DATACRAFT_E2E_DATA_DIR", matches = ".+")
    void profilesACsvFileAndReportsItsMetrics() throws Exception {
      Path input = Files.createTempFile(Path.of(env("DATACRAFT_E2E_DATA_DIR")), "e2e-", ".csv");
      try {
        Files.writeString(input, "id,note\n1,\"a, b\"\n2,c\n3,数据\n", StandardCharsets.UTF_8);
        JobResult result =
            engine
                .run(
                    "csv-profile",
                    Map.of("input", input.toString(), "expectedRows", "3"),
                    Duration.ofSeconds(60))
                .join();
        assertEquals(JobStatus.SUCCEEDED, result.status(), result.message());
        assertEquals("3", result.metrics().get("rows"));
        assertEquals("2", result.metrics().get("columns"));
      } finally {
        Files.deleteIfExists(input);
      }
    }

    @Test
    void aMissingParameterIsAFailedResultWithTheEnginesReason() {
      JobResult result = engine.run("csv-profile", Map.of(), Duration.ofSeconds(30)).join();
      assertEquals(JobStatus.FAILED, result.status());
      assertTrue(result.message().contains("Missing required parameter: input"), result.message());
    }

    @Test
    void anUnknownJobIsNotFound() {
      assertEquals(
          ApiError.NOT_FOUND,
          failure(() -> engine.run("no-such-job", Map.of(), Duration.ofSeconds(10)).join())
              .error());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DATACRAFT_E2E_ENGINE_TOKEN", matches = ".+")
    void aWrongTokenIsUnauthorized() {
      EngineClient wrong = new EngineClient(TRANSPORT, url, Optional.of("w".repeat(40)));
      assertEquals(ApiError.UNAUTHORIZED, failure(() -> wrong.jobs().join()).error());
    }
  }

  @Nested
  @EnabledIfEnvironmentVariable(named = "DATACRAFT_E2E_AIRFLOW_URL", matches = ".+")
  class Airflow {

    private static final String DAG = "datacraft_engine_jobs";
    private static AirflowClient airflow;

    @BeforeAll
    static void signIn() {
      airflow =
          new AirflowClient(
              TRANSPORT,
              URI.create(env("DATACRAFT_E2E_AIRFLOW_URL")),
              env("DATACRAFT_E2E_AIRFLOW_USER"),
              Optional.of(env("DATACRAFT_E2E_AIRFLOW_PASSWORD")),
              Clock.systemUTC());
      airflow.login().join();
    }

    @Test
    void isHealthyRunsVersion332AndHasTheProjectsDags() {
      assertTrue(airflow.health().join().healthy());
      assertEquals("3.3.2", airflow.version().join());
      List<String> dags = airflow.dags().join().stream().map(dag -> dag.dagId()).toList();
      assertTrue(
          dags.containsAll(
              List.of("datacraft_engine_jobs", "datacraft_spark_etl", "datacraft_sftp_ingest")),
          dags.toString());
    }

    @Test
    void theDagsShapesAreTheChainsTheirFilesDeclare() {
      // The graph of the pipelines view is drawn from these.
      Map<String, List<String>> etl = downstream("datacraft_spark_etl");
      assertEquals(Map.of("csv_to_parquet", List.of("row_count"), "row_count", List.of()), etl);
      Map<String, List<String>> engine = downstream("datacraft_engine_jobs");
      assertEquals(Map.of("noop", List.of("echo"), "echo", List.of()), engine);
      Map<String, List<String>> sftp = downstream("datacraft_sftp_ingest");
      assertEquals(
          Map.of(
              "download",
              List.of("csv_to_parquet"),
              "csv_to_parquet",
              List.of("row_count"),
              "row_count",
              List.of()),
          sftp);
    }

    private static Map<String, List<String>> downstream(String dagId) {
      Map<String, List<String>> shape = new java.util.HashMap<>();
      airflow.tasks(dagId).join().forEach(task -> shape.put(task.taskId(), task.downstream()));
      return shape;
    }

    @Test
    void pausingAndResumingIsWhatAirflowThenReports() {
      boolean before = paused(DAG);
      airflow.setPaused(DAG, !before).join();
      assertEquals(!before, paused(DAG));
      airflow.setPaused(DAG, before).join();
      assertEquals(before, paused(DAG));
    }

    @Test
    void aTriggeredRunIsFollowedToSuccessWithItsTasksAndLogs() throws InterruptedException {
      boolean wasPaused = paused(DAG);
      if (wasPaused) {
        airflow.setPaused(DAG, false).join(); // a paused DAG queues the run but never starts it
      }
      try {
        DagRun triggered = airflow.trigger(DAG, Map.of(), "数艺工坊端到端测试").join();
        Instant deadline = Instant.now().plus(Duration.ofMinutes(5));
        DagRun run = triggered;
        while (run.tone().active() && Instant.now().isBefore(deadline)) {
          Thread.sleep(3000);
          String runId = triggered.runId();
          run =
              airflow.runs(DAG, 25).join().stream()
                  .filter(r -> r.runId().equals(runId))
                  .findFirst()
                  .orElseThrow();
        }
        assertEquals(RunTone.SUCCESS, run.tone(), "run " + run.runId() + " ended " + run.state());
        var tasks = airflow.taskInstances(DAG, run.runId()).join();
        assertFalse(tasks.isEmpty());
        assertTrue(
            tasks.stream().allMatch(task -> task.tone() == RunTone.SUCCESS), tasks.toString());
        var task = tasks.getFirst();
        var log =
            airflow
                .log(
                    DAG,
                    run.runId(),
                    task.taskId(),
                    task.mapIndex(),
                    Math.max(1, task.tryNumber()),
                    null)
                .join();
        assertFalse(log.lines().isEmpty(), "the log of " + task.taskId() + " is empty");
      } finally {
        if (wasPaused) {
          airflow.setPaused(DAG, true).join();
        }
      }
    }

    private static boolean paused(String dagId) {
      return airflow.dags().join().stream()
          .filter(dag -> dag.dagId().equals(dagId))
          .findFirst()
          .orElseThrow()
          .paused();
    }
  }
}
