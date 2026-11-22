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

  /** One task of a DAG ({@code GET /api/v2/dags/{dag_id}/tasks}) and the tasks that follow it. */
  public record Task(String taskId, String displayName, List<String> downstream, String operator) {
    public Task {
      downstream = List.copyOf(downstream);
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

  /**
   * A DAG file that failed to parse ({@code GET /api/v2/importErrors}): its DAGs are missing from
   * the list until the file is fixed.
   */
  public record ImportError(
      long id, String filename, Optional<Instant> timestamp, String stackTrace) {
    public ImportError {
      filename = filename == null ? "" : filename;
      stackTrace = stackTrace == null ? "" : stackTrace;
    }

    /** The file name without its folders, for a compact row. */
    public String shortName() {
      int slash = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
      return slash < 0 ? filename : filename.substring(slash + 1);
    }
  }

  /**
   * A pool of task slots ({@code GET /api/v2/pools}); {@code slots} of -1 means unlimited. Airflow
   * counts each state's slots; {@code open} is what is left.
   */
  public record Pool(
      String name, int slots, int running, int queued, int scheduled, int deferred, int open) {

    public boolean unlimited() {
      return slots < 0;
    }

    /** Tasks are waiting while no slot is free. */
    public boolean exhausted() {
      return !unlimited() && open <= 0 && (queued + scheduled) > 0;
    }
  }

  /**
   * A task instance that is running or waiting right now, in any DAG ({@code GET
   * /api/v2/dags/~/dagRuns/~/taskInstances} filtered by state).
   */
  public record ActiveTask(
      String dagId,
      String runId,
      String taskId,
      String displayName,
      int mapIndex,
      String state,
      int tryNumber,
      int maxTries,
      Optional<Instant> startDate,
      Optional<Instant> queuedAt) {

    public RunTone tone() {
      return RunTone.of(state);
    }

    /** A key that stays the same while the task's state changes. */
    public String key() {
      return dagId + "\n" + runId + "\n" + taskId + "\n" + mapIndex;
    }
  }

  /** One part of a task log; {@code next} continues it. */
  public record LogChunk(List<String> lines, Optional<String> next) {
    public LogChunk {
      lines = List.copyOf(lines);
    }
  }
}
