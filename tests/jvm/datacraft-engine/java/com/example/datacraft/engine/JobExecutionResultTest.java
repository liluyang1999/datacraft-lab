package com.example.datacraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JobExecutionResultTest {

  private static final Instant STARTED = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant FINISHED = Instant.parse("2026-01-01T00:00:02Z");

  @Test
  void succeededMergesJobMetricsWithComputedDuration() {
    JobExecutionResult result =
        JobExecutionResult.succeeded(
            "csv-to-parquet", "wrote 10 rows", Map.of("rows", "10"), STARTED, FINISHED);

    assertEquals(JobStatus.SUCCEEDED, result.status());
    assertEquals("wrote 10 rows", result.message());
    assertEquals("10", result.metrics().get("rows"));
    assertEquals("2000", result.metrics().get("durationMillis"));
  }

  @Test
  void failedMergesJobMetricsWithComputedDuration() {
    JobExecutionResult result =
        JobExecutionResult.failed("row-count", "boom", Map.of("input", "data"), STARTED, FINISHED);

    assertEquals(JobStatus.FAILED, result.status());
    assertEquals("data", result.metrics().get("input"));
    assertEquals("2000", result.metrics().get("durationMillis"));
  }

  @Test
  void metricsAreUnmodifiableAndNullMetricsAreTolerated() {
    JobExecutionResult result = JobExecutionResult.succeeded("noop", "ok", null, STARTED, FINISHED);

    assertEquals("2000", result.metrics().get("durationMillis"));
    assertThrows(
        UnsupportedOperationException.class, () -> result.metrics().put("injected", "value"));
  }

  @Test
  void durationNeverGoesNegativeWhenClockMovesBackwards() {
    JobExecutionResult result =
        JobExecutionResult.succeeded("noop", "ok", Map.of(), FINISHED, STARTED);

    assertEquals("0", result.metrics().get("durationMillis"));
  }

  @Test
  void blankJobNameIsRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> JobExecutionResult.succeeded(" ", "ok", Map.of(), STARTED, FINISHED));
  }

  @Test
  void nullMessageBecomesEmptyString() {
    JobExecutionResult result =
        JobExecutionResult.succeeded("noop", null, Map.of(), STARTED, FINISHED);

    assertTrue(result.message().isEmpty());
  }
}
