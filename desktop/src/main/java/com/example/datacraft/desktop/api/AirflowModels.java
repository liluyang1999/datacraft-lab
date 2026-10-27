package com.example.datacraft.desktop.api;

import com.example.datacraft.desktop.util.Maps;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** What the Airflow 3.3 REST API answers, reduced to what the app shows. */
public final class AirflowModels {

  private AirflowModels() {}

  /** {@code GET /api/v2/monitor/health}: each component's status and last heartbeat. */
  public record Health(
      Component metadatabase,
      Component scheduler,
      Component triggerer,
      Optional<Component> dagProcessor,
      Duration latency) {

    public boolean healthy() {
      return metadatabase.healthy()
          && scheduler.healthy()
          && triggerer.healthy()
          && dagProcessor.map(Component::healthy).orElse(true);
    }
  }

  /** One health component; the heartbeat is absent for the metadatabase. */
  public record Component(String status, Optional<Instant> heartbeat) {
    public boolean healthy() {
      return "healthy".equalsIgnoreCase(status);
    }
  }

  /** One DAG of {@code GET /api/v2/dags}. */
  public record Dag(
      String dagId,
      String displayName,
      boolean paused,
      String description,
      String schedule,
      List<String> tags,
      boolean importErrors,
      Optional<Instant> nextRun) {
    public Dag {
      tags = List.copyOf(tags);
    }
  }

  /** One DAG run. */
  public record DagRun(
      String runId,
      String dagId,
      String state,
      String runType,
      Optional<Instant> queuedAt,
      Optional<Instant> startDate,
      Optional<Instant> endDate,
      Optional<Duration> duration,
      Instant runAfter,
      Map<String, Object> conf,
      String note,
      String triggeredBy) {
    public DagRun {
      conf = Maps.frozen(conf);
      note = note == null ? "" : note;
      triggeredBy = triggeredBy == null ? "" : triggeredBy;
    }

    public RunTone tone() {
      return RunTone.of(state);
    }
  }

  /** One task instance of a run. */
  public record TaskInstance(
      String taskId,
      String displayName,
      int mapIndex,
      Optional<String> state,
      int tryNumber,
      int maxTries,
      Optional<Instant> startDate,
      Optional<Instant> endDate,
      Optional<Duration> duration,
      String operator) {

    public RunTone tone() {
      return state.map(RunTone::of).orElse(RunTone.NONE);
    }
  }

  /** One part of a task log; {@code next} continues it. */
  public record LogChunk(List<String> lines, Optional<String> next) {
    public LogChunk {
      lines = List.copyOf(lines);
    }
  }
}
