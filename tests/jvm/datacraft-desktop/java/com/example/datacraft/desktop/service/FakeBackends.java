package com.example.datacraft.desktop.service;

import com.example.datacraft.desktop.api.HttpTransport;
import com.example.datacraft.desktop.api.StubServer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;

/** A scripted engine API and Airflow on loopback, healthy until a test says otherwise. */
public final class FakeBackends implements AutoCloseable {

  public static final String TOKEN = "t".repeat(40);
  public static final String PASSWORD = "pw";

  public final StubServer engine = new StubServer();
  public final StubServer airflow = new StubServer();

  public FakeBackends() {
    engine.on("GET /health", 200, "{\"status\":\"UP\",\"service\":\"datacraft-api\"}");
    engine.on(
        "GET /jobs",
        request ->
            ("Bearer " + TOKEN).equals(request.header("Authorization"))
                ? StubServer.Answer.json(
                    200,
                    "{\"jobs\":[{\"name\":\"echo\",\"description\":\"Returns the message parameter.\"},"
                        + "{\"name\":\"noop\",\"description\":\"\"}]}")
                : StubServer.Answer.json(401, "{\"error\":\"unauthorized\"}"));
    airflow.on(
        "POST /auth/token",
        request ->
            request.body().contains("\"password\":\"" + PASSWORD + "\"")
                ? StubServer.Answer.json(201, "{\"access_token\":\"" + jwt() + "\"}")
                : StubServer.Answer.json(401, "{\"detail\":\"Invalid credentials\"}"));
    healthyAirflow();
  }

  public void healthyAirflow() {
    airflow.on("GET /api/v2/monitor/health", 200, health("healthy"));
  }

  public void schedulerDown() {
    airflow.on("GET /api/v2/monitor/health", 200, health("unhealthy"));
  }

  /** The project's jobs and DAGs with a run history, for views that should look lived in. */
  public FakeBackends withPipelines() {
    engine.on(
        "GET /jobs",
        200,
        "{\"jobs\":["
            + job(
                "csv-profile",
                "Profiles a small UTF-8 CSV file in the JVM: rows, columns, bytes and SHA-256.")
            + ","
            + job("file-checksum", "Computes the SHA-256 checksum and size of a file in the JVM.")
            + ","
            + job("csv-to-parquet", "Converts a CSV dataset to Parquet.")
            + ","
            + job("row-count", "Counts rows in a dataset.")
            + ","
            + job("spark-version", "Reports the running Spark runtime version.")
            + ","
            + job("echo", "Returns the message parameter.")
            + ","
            + job("noop", "Does nothing.")
            + "]}");
    engine.on(
        "POST /jobs/csv-profile/runs",
        200,
        "{\"jobName\":\"csv-profile\",\"status\":\"SUCCEEDED\",\"message\":\"Profiled 12,480 rows in 6 columns\","
            + "\"metrics\":{\"rows\":\"12480\",\"columns\":\"6\",\"bytes\":\"1843200\","
            + "\"sha256\":\"9f2c4e1a7b3d5c6e8f0a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f6a\",\"durationMillis\":\"1240\"}}");
    airflow.on(
        "GET /api/v2/dags",
        200,
        "{\"total_entries\":3,\"dags\":["
            + dag("datacraft_engine_jobs", false, "@daily")
            + ","
            + dag("datacraft_sftp_ingest", true, null)
            + ","
            + dag("datacraft_spark_etl", false, null)
            + "]}");
    Instant now = Instant.now().truncatedTo(ChronoUnit.MINUTES);
    pipeline(
        "datacraft_engine_jobs",
        "scheduled",
        now.minus(Duration.ofMinutes(40)),
        Duration.ofDays(1),
        List.of("echo", "noop", "spark_version"),
        "success",
        "success",
        "success",
        "failed",
        "success",
        "success",
        "success",
        "success",
        "success",
        "success",
        "success",
        "success");
    pipeline(
        "datacraft_spark_etl",
        "manual",
        now.minus(Duration.ofMinutes(3)),
        Duration.ofHours(5),
        List.of("csv_to_parquet", "row_count"),
        "running",
        "failed",
        "success",
        "success",
        "failed",
        "success");
    airflow.on(
        "GET /api/v2/dags/datacraft_sftp_ingest/dagRuns",
        200,
        "{\"dag_runs\":[],\"total_entries\":0}");
    return this;
  }

  /** A DAG's runs, newest first and {@code step} apart, each with its tasks and their logs. */
  private void pipeline(
      String dagId,
      String type,
      Instant newest,
      Duration step,
      List<String> taskIds,
      String... states) {
    StringBuilder runs = new StringBuilder("{\"dag_runs\":[");
    for (int index = 0; index < states.length; index++) {
      Instant start = newest.minus(step.multipliedBy(index));
      String runId = type + "__" + start.toString().replace("Z", "+00:00");
      String state = states[index];
      boolean done = !state.equals("running") && !state.equals("queued");
      runs.append(index == 0 ? "" : ",")
          .append("{\"dag_run_id\":\"")
          .append(runId)
          .append("\",\"dag_id\":\"")
          .append(dagId)
          .append("\",\"state\":\"")
          .append(state)
          .append("\",\"run_type\":\"")
          .append(type)
          .append("\",\"queued_at\":\"")
          .append(start)
          .append("\",\"start_date\":\"")
          .append(start)
          .append("\",\"end_date\":")
          .append(done ? "\"" + start.plusSeconds(48) + "\"" : "null")
          .append(",\"duration\":")
          .append(done ? "48.0" : "null")
          .append(",\"run_after\":\"")
          .append(start)
          .append("\",\"conf\":{},\"note\":null,\"triggered_by\":\"rest_api\"}");
      String runPath = "/api/v2/dags/" + dagId + "/dagRuns/" + HttpTransport.encode(runId);
      StringBuilder tasks = new StringBuilder("{\"task_instances\":[");
      for (int task = 0; task < taskIds.size(); task++) {
        boolean last = task == taskIds.size() - 1;
        String taskState = !last ? "success" : state.equals("failed") ? "failed" : state;
        Instant taskStart = start.plusSeconds(3 + task * 20L);
        boolean taskDone = !taskState.equals("running");
        tasks
            .append(task == 0 ? "" : ",")
            .append(
                task(
                    taskIds.get(task),
                    taskState,
                    taskStart,
                    taskDone ? taskStart.plusSeconds(18) : null));
        if (index == 0 && taskIds.get(task).equals(LONG_LOG_TASK)) {
          airflow.on(
              "GET " + runPath + "/taskInstances/" + taskIds.get(task) + "/logs/1",
              200,
              longLog(taskStart));
          continue;
        }
        airflow.on(
            "GET " + runPath + "/taskInstances/" + taskIds.get(task) + "/logs/1",
            200,
            "{\"content\":["
                + logLine(taskStart, "info", "Task started: " + taskIds.get(task))
                + ","
                + logLine(
                    taskStart.plusSeconds(1),
                    "info",
                    "Running datacraft-cli run " + taskIds.get(task))
                + ","
                + logLine(
                    taskStart.plusSeconds(17),
                    taskState.equals("failed") ? "error" : "info",
                    taskState.equals("failed")
                        ? "Expected 12480 rows but found 12479"
                        : "Task finished: " + taskState)
                + "],\"continuation_token\":null}");
      }
      airflow.on(
          "GET " + runPath + "/taskInstances",
          200,
          tasks.append("],\"total_entries\":").append(taskIds.size()).append("}").toString());
    }
    airflow.on(
        "GET /api/v2/dags/" + dagId + "/dagRuns",
        200,
        runs.append("],\"total_entries\":").append(states.length).append("}").toString());
  }

  /** The newest engine-jobs run's spark_version task has a long log: scrolling must stay smooth. */
  public static final String LONG_LOG_TASK = "spark_version";

  public static final int LONG_LOG_LINES = 1500;

  private static String longLog(Instant start) {
    StringBuilder log = new StringBuilder("{\"content\":[");
    for (int line = 0; line < LONG_LOG_LINES; line++) {
      log.append(line == 0 ? "" : ",")
          .append(
              logLine(
                  start.plusMillis(line * 20L),
                  line % 97 == 0 ? "warning" : "info",
                  "Spark stage "
                      + line / 50
                      + " task "
                      + line % 50
                      + " finished in "
                      + (line % 13 + 2)
                      + " ms"));
    }
    return log.append("],\"continuation_token\":null}").toString();
  }

  private static String logLine(Instant at, String level, String event) {
    return "{\"timestamp\":\"" + at + "\",\"level\":\"" + level + "\",\"event\":\"" + event + "\"}";
  }

  private static String job(String name, String description) {
    return "{\"name\":\"" + name + "\",\"description\":\"" + description + "\"}";
  }

  private static String dag(String id, boolean paused, String schedule) {
    return "{\"dag_id\":\""
        + id
        + "\",\"dag_display_name\":\""
        + id
        + "\",\"is_paused\":"
        + paused
        + ",\"description\":null,\"timetable_summary\":"
        + (schedule == null ? "null" : "\"" + schedule + "\"")
        + ",\"tags\":[{\"name\":\"datacraft\",\"dag_id\":\""
        + id
        + "\"}],\"has_import_errors\":false,"
        + "\"next_dagrun_run_after\":null}";
  }

  private static String task(String id, String state, Instant start, Instant end) {
    return "{\"task_id\":\""
        + id
        + "\",\"task_display_name\":\""
        + id
        + "\",\"map_index\":-1,\"state\":\""
        + state
        + "\",\"try_number\":1,\"max_tries\":2,\"start_date\":\""
        + start
        + "\",\"end_date\":"
        + (end == null ? "null" : "\"" + end + "\"")
        + ",\"duration\":"
        + (end == null ? "null" : Duration.between(start, end).toSeconds())
        + ",\"operator_name\":\"BashOperator\"}";
  }

  private static String health(String scheduler) {
    return "{\"metadatabase\":{\"status\":\"healthy\"},"
        + "\"scheduler\":{\"status\":\""
        + scheduler
        + "\",\"latest_scheduler_heartbeat\":\""
        + Instant.now().minusSeconds(3)
        + "\"},"
        + "\"triggerer\":{\"status\":\"healthy\",\"latest_triggerer_heartbeat\":\"2026-10-27T09:59:58+00:00\"},"
        + "\"dag_processor\":{\"status\":\"healthy\",\"latest_dag_processor_heartbeat\":\"2026-10-27T09:59:58+00:00\"}}";
  }

  static String jwt() {
    Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    String header = encoder.encodeToString("{\"alg\":\"HS512\"}".getBytes(StandardCharsets.UTF_8));
    long exp = Instant.now().plusSeconds(3600).getEpochSecond();
    String claims =
        encoder.encodeToString(
            ("{\"sub\":\"admin\",\"exp\":" + exp + "}").getBytes(StandardCharsets.UTF_8));
    return header + "." + claims + ".signature";
  }

  @Override
  public void close() {
    engine.close();
    airflow.close();
  }
}
