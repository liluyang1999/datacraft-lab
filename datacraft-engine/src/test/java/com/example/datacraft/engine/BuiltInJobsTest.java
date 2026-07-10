package com.example.datacraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.datacraft.common.Lifecycle;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BuiltInJobsTest {

  @Test
  void providesStableCoreJobs() {
    JobRegistry registry = BuiltInJobs.registry();

    assertEquals(List.of("echo", "noop"), registry.jobNames());
  }

  @Test
  void echoJobReturnsMessageParameter() {
    JobExecutionEngine engine = new JobExecutionEngine(BuiltInJobs.registry());

    JobExecutionResult result =
        engine.execute(JobExecutionRequest.of("echo", Lifecycle.DEV, Map.of("message", "hello")));

    assertEquals(JobStatus.SUCCEEDED, result.status());
    assertEquals("hello", result.message());
  }
}
