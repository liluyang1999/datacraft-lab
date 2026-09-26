package com.example.datacraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.datacraft.common.Lifecycle;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JobExecutionRequestTest {

  private static final Instant STARTED = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void rejectsBlankOrNullJobName() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new JobExecutionRequest(" ", Lifecycle.DEV, Map.of(), STARTED));
    assertThrows(
        IllegalArgumentException.class,
        () -> new JobExecutionRequest(null, Lifecycle.DEV, Map.of(), STARTED));
  }

  @Test
  void rejectsNullLifecycleAndStartedTime() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new JobExecutionRequest("job", null, Map.of(), STARTED));
    assertThrows(
        IllegalArgumentException.class,
        () -> new JobExecutionRequest("job", Lifecycle.DEV, Map.of(), null));
  }

  @Test
  void nullParametersBecomeEmpty() {
    assertTrue(JobExecutionRequest.of("job", Lifecycle.DEV, null).parameters().isEmpty());
  }

  @Test
  void copiesParametersDefensivelyAndExposesThemReadOnly() {
    Map<String, String> source = new HashMap<>(Map.of("message", "hello"));
    JobExecutionRequest request = JobExecutionRequest.of("job", Lifecycle.DEV, source);

    source.put("message", "changed");
    source.put("extra", "value");

    assertEquals(Map.of("message", "hello"), request.parameters());
    assertThrows(
        UnsupportedOperationException.class, () -> request.parameters().put("extra", "value"));
  }
}
