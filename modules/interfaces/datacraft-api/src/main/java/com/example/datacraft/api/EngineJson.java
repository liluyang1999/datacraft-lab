package com.example.datacraft.api;

import com.example.datacraft.common.AppInfo;
import com.example.datacraft.common.DataCraftException;
import com.example.datacraft.engine.JobCatalog;
import com.example.datacraft.engine.JobExecutionResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Serialises engine domain objects to JSON: the HTTP API's responses, and through {@link #result}
 * the line the CLI prints for {@code --json} and writes to {@code --result-file}.
 */
public final class EngineJson {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private EngineJson() {}

  record JobView(String name, String description) {}

  record JobsView(List<JobView> jobs) {}

  record ResultView(String jobName, String status, String message, Map<String, String> metrics) {}

  record HealthView(String status, String service) {}

  record RunCountsView(long active, long started, long succeeded, long failed, long rejected) {}

  record ActiveRunView(String job, String startedAt, long elapsedMillis) {}

  record StatusView(
      String status,
      String service,
      String version,
      String startedAt,
      long uptimeMillis,
      int jobs,
      int maxConcurrentRuns,
      RunCountsView runs,
      List<ActiveRunView> activeRuns) {}

  record ErrorView(String error) {}

  static String jobs(JobCatalog catalog) {
    List<JobView> views =
        catalog.jobs().values().stream()
            .map(job -> new JobView(job.name(), job.description()))
            .toList();
    return write(new JobsView(views));
  }

  public static String result(JobExecutionResult result) {
    return write(
        new ResultView(
            result.jobName(), result.status().name(), result.message(), result.metrics()));
  }

  static String health() {
    return write(new HealthView("UP", "datacraft-api"));
  }

  /** {@code GET /status}: the version, the uptime and the runs since the server started. */
  static String status(RunActivity.Snapshot snapshot, int jobs, int maxConcurrentRuns) {
    List<ActiveRunView> active =
        snapshot.active().stream()
            .map(
                run ->
                    new ActiveRunView(
                        run.job(),
                        run.startedAt().toString(),
                        Math.max(0, Duration.between(run.startedAt(), snapshot.now()).toMillis())))
            .toList();
    return write(
        new StatusView(
            "UP",
            "datacraft-api",
            AppInfo.version(),
            snapshot.startedAt().toString(),
            Math.max(0, snapshot.uptime().toMillis()),
            jobs,
            maxConcurrentRuns,
            new RunCountsView(
                active.size(),
                snapshot.started(),
                snapshot.succeeded(),
                snapshot.failed(),
                snapshot.rejected()),
            active));
  }

  static String error(String code) {
    return write(new ErrorView(code));
  }

  private static String write(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new DataCraftException("Failed to serialise JSON.", exception);
    }
  }
}
