package com.example.datacraft.engine;

import com.example.datacraft.common.Lifecycle;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

public record JobExecutionRequest(
    String jobName, Lifecycle lifecycle, Map<String, String> parameters, Instant startedAt) {

  public JobExecutionRequest {
    if (jobName == null || jobName.isBlank()) {
      throw new IllegalArgumentException("Job name must not be blank.");
    }
    if (lifecycle == null) {
      throw new IllegalArgumentException("Lifecycle must not be null.");
    }
    if (startedAt == null) {
      throw new IllegalArgumentException("Started time must not be null.");
    }
    parameters =
        Collections.unmodifiableMap(new TreeMap<>(parameters == null ? Map.of() : parameters));
  }

  public static JobExecutionRequest of(
      String jobName, Lifecycle lifecycle, Map<String, String> parameters) {
    return new JobExecutionRequest(jobName, lifecycle, parameters, Instant.now());
  }
}
