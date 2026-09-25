package com.example.datacraft.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.datacraft.common.Lifecycle;
import com.example.datacraft.engine.BuiltInJobs;
import com.example.datacraft.engine.DataJob;
import com.example.datacraft.engine.JobExecutionEngine;
import com.example.datacraft.engine.JobExecutionRequest;
import com.example.datacraft.engine.JobExecutionResult;
import com.example.datacraft.engine.JobRegistry;
import com.example.datacraft.engine.JobStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JvmJobsTest {

  @TempDir Path tempDir;

  @Test
  void registersTheJvmJobsAlongsideTheBuiltInJobs() {
    JobRegistry registry = BuiltInJobs.registry();

    JobRegistry returned = JvmJobs.register(registry);

    assertSame(registry, returned);
    assertEquals(List.of("csv-profile", "echo", "file-checksum", "noop"), registry.jobNames());
    assertEquals(
        List.of("csv-profile", "file-checksum"),
        JvmJobs.all().stream().map(DataJob::name).toList());
  }

  @Test
  void rejectsANullRegistry() {
    assertThrows(IllegalArgumentException.class, () -> JvmJobs.register(null));
  }

  @Test
  void engineRunsCsvProfileAndReturnsItsMetrics() throws Exception {
    Path input = Files.writeString(tempDir.resolve("in.csv"), "id,name\n1,Ada\n");

    JobExecutionResult result =
        execute(unconfined(), "csv-profile", Map.of("input", input.toString()));

    assertEquals(JobStatus.SUCCEEDED, result.status());
    assertEquals("1", result.metrics().get("rows"));
    assertEquals("2", result.metrics().get("columns"));
  }

  @Test
  void engineTurnsCsvProfileFailuresIntoFailedResultsCarryingTheMessage() throws Exception {
    Path ragged = Files.writeString(tempDir.resolve("ragged.csv"), "a,b\n1,2\n3\n");
    Path valid = Files.writeString(tempDir.resolve("valid.csv"), "a,b\n1,2\n");
    JobRegistry registry = unconfined();

    assertFailed(
        execute(registry, "csv-profile", Map.of("input", ragged.toString())),
        "CSV record 3 has 1 field but the header has 2 fields");
    assertFailed(execute(registry, "csv-profile", Map.of()), "Missing required parameter: input");
    JobExecutionResult gated =
        execute(registry, "csv-profile", Map.of("input", valid.toString(), "expectedRows", "5"));
    assertFailed(gated, "Expected 5 rows but found 1");
    assertEquals("1", gated.metrics().get("rows"));
  }

  @Test
  void engineTurnsFileChecksumFailuresIntoFailedResultsCarryingTheMessage() throws Exception {
    Path root = Files.createDirectory(tempDir.resolve("root"));
    Path inside = Files.writeString(root.resolve("in.bin"), "datacraft");
    Path outside = Files.writeString(tempDir.resolve("outside.bin"), "datacraft");
    JobRegistry registry = new JobRegistry();
    registry.register(new CsvProfileJob(Optional.of(tempDir.resolve("missing-root"))));
    registry.register(new FileChecksumJob(Optional.of(root)));

    assertFailed(
        execute(registry, "file-checksum", Map.of("input", outside.toString())),
        "input must be inside the configured data root");
    assertFailed(
        execute(
            registry,
            "file-checksum",
            Map.of("input", inside.toString(), "expectedSha256", "0".repeat(64))),
        "Expected SHA-256 "
            + "0".repeat(64)
            + " but found daa501f37955ee127679730f5a68588e36ed357b34448ed6e244a80a2bf2da4b");
    assertFailed(
        execute(registry, "csv-profile", Map.of("input", outside.toString())),
        "DATACRAFT_DATA_ROOT must name an existing directory");
  }

  private static JobRegistry unconfined() {
    JobRegistry registry = new JobRegistry();
    registry.register(new CsvProfileJob(Optional.empty()));
    registry.register(new FileChecksumJob(Optional.empty()));
    return registry;
  }

  private static JobExecutionResult execute(
      JobRegistry registry, String jobName, Map<String, String> parameters) {
    return new JobExecutionEngine(registry)
        .execute(JobExecutionRequest.of(jobName, Lifecycle.DEV, parameters));
  }

  private static void assertFailed(JobExecutionResult result, String message) {
    assertEquals(JobStatus.FAILED, result.status(), result.message());
    assertEquals(message, result.message());
  }
}
