package com.example.datacraft.engine;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

public record JobExecutionResult(
    String jobName,
    JobStatus status,
    String message,
    Map<String, String> metrics,
    Instant startedAt,
    Instant finishedAt) {

  public JobExecutionResult {
    if (jobName == null || jobName.isBlank()) {
      throw new IllegalArgumentException("Job name must not be blank.");
    }
    if (status == null) {
      throw new IllegalArgumentException("Job status must not be null.");
    }
    if (startedAt == null || finishedAt == null) {
      throw new IllegalArgumentException("Execution timestamps must not be null.");
    }
    message = message == null ? "" : message;
    metrics = Collections.unmodifiableMap(new TreeMap<>(metrics == null ? Map.of() : metrics));
  }

  public static JobExecutionResult success(
      String jobName, String message, Instant startedAt, Instant finishedAt) {
    return new JobExecutionResult(
        jobName,
        JobStatus.SUCCEEDED,
        message,
        Map.of("durationMillis", Long.toString(durationMillis(startedAt, finishedAt))),
        startedAt,
        finishedAt);
  }

  public static JobExecutionResult failure(
      String jobName, String message, Instant startedAt, Instant finishedAt) {
    return new JobExecutionResult(
        jobName,
        JobStatus.FAILED,
        message,
        Map.of("durationMillis", Long.toString(durationMillis(startedAt, finishedAt))),
        startedAt,
        finishedAt);
  }

  /** Succeeded result merging the job-supplied metrics with the computed {@code durationMillis}. */
  public static JobExecutionResult succeeded(
      String jobName,
      String message,
      Map<String, String> metrics,
      Instant startedAt,
      Instant finishedAt) {
    return new JobExecutionResult(
        jobName,
        JobStatus.SUCCEEDED,
        message,
        withDuration(metrics, startedAt, finishedAt),
        startedAt,
        finishedAt);
  }

  /** Failed result merging the job-supplied metrics with the computed {@code durationMillis}. */
  public static JobExecutionResult failed(
      String jobName,
      String message,
      Map<String, String> metrics,
      Instant startedAt,
      Instant finishedAt) {
    return new JobExecutionResult(
        jobName,
        JobStatus.FAILED,
        message,
        withDuration(metrics, startedAt, finishedAt),
        startedAt,
        finishedAt);
  }

  private static Map<String, String> withDuration(
      Map<String, String> metrics, Instant startedAt, Instant finishedAt) {
    TreeMap<String, String> merged = new TreeMap<>(metrics == null ? Map.of() : metrics);
    merged.put("durationMillis", Long.toString(durationMillis(startedAt, finishedAt)));
    return merged;
  }

  private static long durationMillis(Instant startedAt, Instant finishedAt) {
    return Math.max(0L, Duration.between(startedAt, finishedAt).toMillis());
  }
}
