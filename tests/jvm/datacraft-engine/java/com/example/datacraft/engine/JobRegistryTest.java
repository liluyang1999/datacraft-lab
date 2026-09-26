package com.example.datacraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

  @Test
  void rejectsNullJobsAndNullOrBlankNames() {
    JobRegistry registry = new JobRegistry();

    assertThrows(IllegalArgumentException.class, () -> registry.register(null));
    assertThrows(IllegalArgumentException.class, () -> registry.register(new EchoJob(null)));
    assertThrows(IllegalArgumentException.class, () -> registry.register(new EchoJob(" ")));
    assertTrue(registry.jobNames().isEmpty());
  }

  @Test
  void rejectsNamesThatAreNotSinglePathSegments() {
    JobRegistry registry = new JobRegistry();

    IllegalArgumentException rejected =
        assertThrows(
            IllegalArgumentException.class, () -> registry.register(new EchoJob("reports/daily")));
    registry.register(new EchoJob("literal+%20"));

    assertTrue(rejected.getMessage().contains("reports/daily"), rejected.getMessage());
    assertEquals(List.of("literal+%20"), registry.jobNames());
  }

  @Test
  void exposesJobsReadOnlyAndFindsNothingForUnknownNames() {
    JobRegistry registry = new JobRegistry();
    EchoJob echo = new EchoJob("echo");
    registry.register(echo);

    assertThrows(
        UnsupportedOperationException.class, () -> registry.jobs().put("other", new EchoJob("x")));
    assertTrue(registry.find("missing").isEmpty());
    assertEquals(List.of("echo"), registry.jobNames());
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
