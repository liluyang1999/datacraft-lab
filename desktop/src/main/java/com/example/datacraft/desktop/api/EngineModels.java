package com.example.datacraft.desktop.api;

import com.example.datacraft.desktop.util.Maps;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** What the engine API answers, as immutable records. */
public final class EngineModels {

  private EngineModels() {}

  /** {@code GET /health}. */
  public record Health(boolean up, String service, Duration latency) {}

  /** One run in progress on the engine: its job and how long it has been running. */
  public record ActiveRun(String job, Instant startedAt, Duration elapsed) {
    public ActiveRun {
      Objects.requireNonNull(job, "job");
      elapsed = elapsed.isNegative() ? Duration.ZERO : elapsed;
    }
  }

  /**
   * {@code GET /status}: the engine's version, how long it has been up, how many runs it holds at
   * once, the runs in progress and the run counters since it started.
   */
  public record Status(
      String version,
      Instant startedAt,
      Duration uptime,
      int jobs,
      int maxConcurrentRuns,
      long started,
      long succeeded,
      long failed,
      long rejected,
      List<ActiveRun> active) {
    public Status {
      version = version == null ? "" : version;
      active = List.copyOf(active);
    }
  }

  /** One entry of {@code GET /jobs}. */
  public record JobInfo(String name, String description) {
    public JobInfo {
      Objects.requireNonNull(name, "name");
      description = description == null ? "" : description;
    }
  }

  /** How a run ended, as the engine reports it. */
  public enum JobStatus {
    SUCCEEDED,
    FAILED,
    UNKNOWN;

    static JobStatus of(String value) {
      for (JobStatus status : values()) {
        if (status.name().equalsIgnoreCase(value)) {
          return status;
        }
      }
      return UNKNOWN;
    }
  }

  /** The result of {@code POST /jobs/{name}/runs}: 200 when it succeeded, 500 when it failed. */
  public record JobResult(
      String jobName, JobStatus status, String message, Map<String, String> metrics) {
    public JobResult {
      metrics = Maps.frozen(metrics);
      message = message == null ? "" : message;
    }

    /** The engine's own duration, from the {@code durationMillis} metric. */
    public Duration duration() {
      try {
        return Duration.ofMillis(Long.parseLong(metrics.getOrDefault("durationMillis", "0")));
      } catch (NumberFormatException malformed) {
        return Duration.ZERO;
      }
    }
  }
}
