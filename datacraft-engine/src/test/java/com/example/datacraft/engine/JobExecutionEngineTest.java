package com.example.datacraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.datacraft.common.Lifecycle;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JobExecutionEngineTest {

  @Test
  void executesRegisteredJobWithImmutableRequestParameters() {
    JobRegistry registry = new JobRegistry();
    registry.register(
        new DataJob() {
          @Override
          public String name() {
            return "echo";
          }

          @Override
          public String description() {
            return "returns the input message";
          }

          @Override
          public JobExecutionResult run(JobExecutionRequest request) {
            return JobExecutionResult.success(
                request.jobName(),
                request.parameters().get("message"),
                request.startedAt(),
                request.startedAt());
          }
        });
    JobExecutionEngine engine = new JobExecutionEngine(registry);

    JobExecutionResult result =
        engine.execute(JobExecutionRequest.of("echo", Lifecycle.DEV, Map.of("message", "hello")));

    assertEquals(JobStatus.SUCCEEDED, result.status());
    assertEquals("hello", result.message());
  }

  @Test
  void convertsJobExceptionsIntoFailedResults() {
    JobRegistry registry = new JobRegistry();
    registry.register(
        new DataJob() {
          @Override
          public String name() {
            return "broken";
          }

          @Override
          public String description() {
            return "throws";
          }

          @Override
          public JobExecutionResult run(JobExecutionRequest request) {
            throw new IllegalStateException("boom");
          }
        });
    JobExecutionEngine engine = new JobExecutionEngine(registry);

    JobExecutionResult result =
        engine.execute(JobExecutionRequest.of("broken", Lifecycle.PROD, Map.of()));

    assertEquals(JobStatus.FAILED, result.status());
    assertTrue(result.message().contains("boom"));
  }
}
