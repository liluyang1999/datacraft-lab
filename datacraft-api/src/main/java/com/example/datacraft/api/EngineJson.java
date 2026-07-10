package com.example.datacraft.api;

import com.example.datacraft.common.DataCraftException;
import com.example.datacraft.engine.JobCatalog;
import com.example.datacraft.engine.JobExecutionResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;

/** Serialises engine domain objects into the JSON responses exposed by the HTTP API. */
final class EngineJson {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private EngineJson() {}

  record JobView(String name, String description) {}

  record JobsView(List<JobView> jobs) {}

  record ResultView(String jobName, String status, String message, Map<String, String> metrics) {}

  record HealthView(String status, String service) {}

  record ErrorView(String error) {}

  static String jobs(JobCatalog catalog) {
    List<JobView> views =
        catalog.jobs().values().stream()
            .map(job -> new JobView(job.name(), job.description()))
            .toList();
    return write(new JobsView(views));
  }

  static String result(JobExecutionResult result) {
    return write(
        new ResultView(
            result.jobName(), result.status().name(), result.message(), result.metrics()));
  }

  static String health() {
    return write(new HealthView("UP", "datacraft-api"));
  }

  static String error(String code) {
    return write(new ErrorView(code));
  }

  private static String write(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new DataCraftException("Failed to serialise HTTP response.", exception);
    }
  }
}
