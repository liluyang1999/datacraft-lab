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
    // Jobs read every parameter as a string; a null would reach their result as a JVM message.
    TreeMap<String, String> copy = new TreeMap<>();
    if (parameters != null) {
      parameters.forEach(
          (name, value) -> {
            if (name == null) {
              throw new IllegalArgumentException("Parameter names must not be null.");
            }
            if (value == null) {
              throw new IllegalArgumentException("Parameter " + name + " must not be null.");
            }
            copy.put(name, value);
          });
    }
    parameters = Collections.unmodifiableMap(copy);
  }

  public static JobExecutionRequest of(
      String jobName, Lifecycle lifecycle, Map<String, String> parameters) {
    return new JobExecutionRequest(jobName, lifecycle, parameters, Instant.now());
  }
}
