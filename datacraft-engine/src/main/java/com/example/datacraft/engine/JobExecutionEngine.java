package com.example.datacraft.engine;

import java.time.Instant;

public final class JobExecutionEngine {

  private final JobCatalog catalog;

  public JobExecutionEngine(JobCatalog catalog) {
    if (catalog == null) {
      throw new IllegalArgumentException("Job catalog must not be null.");
    }
    this.catalog = catalog;
  }

  public JobExecutionResult execute(JobExecutionRequest request) {
    Instant startedAt = request.startedAt();
    try {
      DataJob job = catalog.require(request.jobName());
      JobExecutionResult result = job.run(request);
      if (result == null) {
        return JobExecutionResult.failure(
            request.jobName(), "Job returned null result.", startedAt, Instant.now());
      }
      return result;
    } catch (Exception exception) {
      // Scala jobs may throw checked exceptions despite the Java interface's signature.
      if (exception instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      String message = exception.getMessage();
      return JobExecutionResult.failure(
          request.jobName(),
          message == null ? exception.getClass().getSimpleName() : message,
          startedAt,
          Instant.now());
    }
  }
}
