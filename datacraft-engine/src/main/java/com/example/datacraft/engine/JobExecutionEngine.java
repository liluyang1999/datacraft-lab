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
    } catch (RuntimeException exception) {
      return JobExecutionResult.failure(
          request.jobName(), exception.getMessage(), startedAt, Instant.now());
    }
  }
}
