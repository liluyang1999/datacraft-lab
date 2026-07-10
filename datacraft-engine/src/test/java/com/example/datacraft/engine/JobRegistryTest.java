package com.example.datacraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class JobRegistryTest {

  @Test
  void registersJobsAndListsThemInStableOrder() {
    JobRegistry registry = new JobRegistry();
    registry.register(new EchoJob("zeta"));
    registry.register(new EchoJob("alpha"));

    assertEquals(List.of("alpha", "zeta"), registry.jobNames());
    assertEquals("alpha", registry.require("alpha").name());
  }

  @Test
  void rejectsDuplicateJobNames() {
    JobRegistry registry = new JobRegistry();
    registry.register(new EchoJob("echo"));

    assertThrows(IllegalArgumentException.class, () -> registry.register(new EchoJob("echo")));
  }

  private record EchoJob(String name) implements DataJob {
    @Override
    public String description() {
      return "test job";
    }

    @Override
    public JobExecutionResult run(JobExecutionRequest request) {
      return JobExecutionResult.success(name, "ok", request.startedAt(), request.startedAt());
    }
  }
}
