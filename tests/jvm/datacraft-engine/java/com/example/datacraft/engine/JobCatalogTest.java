package com.example.datacraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.datacraft.common.Lifecycle;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JobCatalogTest {

  @Test
  void executionEngineDependsOnCatalogInterfaceInsteadOfRegistryImplementation() {
    JobRegistry registry = new JobRegistry();
    registry.register(new FixedJob());
    JobCatalog catalog = registry;

    JobExecutionEngine engine = new JobExecutionEngine(catalog);
    JobExecutionResult result =
        engine.execute(JobExecutionRequest.of("fixed", Lifecycle.DEV, Map.of()));

    assertEquals(JobStatus.SUCCEEDED, result.status());
    assertEquals("fixed-result", result.message());
  }

  private static final class FixedJob implements DataJob {
    @Override
    public String name() {
      return "fixed";
    }

    @Override
    public String description() {
      return "fixed test job";
    }

    @Override
    public JobExecutionResult run(JobExecutionRequest request) {
      return JobExecutionResult.success(
          request.jobName(), "fixed-result", request.startedAt(), request.startedAt());
    }
  }
}
