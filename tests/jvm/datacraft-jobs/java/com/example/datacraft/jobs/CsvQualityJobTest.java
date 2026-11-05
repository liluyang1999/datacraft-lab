package com.example.datacraft.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.datacraft.common.DataCraftException;
import com.example.datacraft.common.Lifecycle;
import com.example.datacraft.engine.JobExecutionEngine;
import com.example.datacraft.engine.JobExecutionRequest;
import com.example.datacraft.engine.JobExecutionResult;
import com.example.datacraft.engine.JobRegistry;
import com.example.datacraft.engine.JobStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class CsvQualityJobTest {

  @TempDir Path tempDir;

  @Test
  void cleanDataPassesWithAllMetricsFromOneUnchangedSnapshot() throws Exception {
    Path input = write("id,name\n1,艾达\n2,Grace\n");
    byte[] before = Files.readAllBytes(input);
    JobExecutionResult result = run(input, Map.of());
    assertEquals(JobStatus.SUCCEEDED, result.status());
    assertEquals("csv-quality", result.jobName());
    assertEquals("CSV quality passed: 2 rows, 0 blank cells, 0 duplicate rows", result.message());
    assertEquals("2", result.metrics().get("rows"));
    assertEquals("2", result.metrics().get("columns"));
    assertEquals("0", result.metrics().get("blankRows"));
    assertEquals(Integer.toString(before.length), result.metrics().get("bytes"));
    assertEquals(CsvProfileJobTest.sha256(before), result.metrics().get("sha256"));
    assertEquals(
        CsvProfileJobTest.sha256(before), CsvProfileJobTest.sha256(Files.readAllBytes(input)));
  }

  @Test
  void defaultGatesReportAllViolationsAndKeepMetricsWithoutFieldValues() throws Exception {
    Path input = write("id,name\n1,private-content\n1,private-content\n ,\t\n");
    JobExecutionResult result = run(input, Map.of("minRows", "4"));
    assertEquals(JobStatus.FAILED, result.status());
    assertEquals(
        "CSV quality failed: rows=3 is below minRows=4; blankCells=2 exceeds maxBlankCells=0; duplicateRows=1 exceeds maxDuplicateRows=0",
        result.message());
    assertEquals("3", result.metrics().get("rows"));
    assertEquals("2", result.metrics().get("blankCells"));
    assertEquals("1", result.metrics().get("blankRows"));
    assertEquals("1", result.metrics().get("duplicateRows"));
    assertFalse(result.message().contains("private-content"));
    assertFalse(result.metrics().toString().contains("private-content"));
  }

  @Test
  void exactThresholdsPassAndOneLowerThresholdFails() throws Exception {
    Path input = write("a,b\nx,\nx,\n");
    assertEquals(
        JobStatus.SUCCEEDED,
        run(input, Map.of("minRows", "2", "maxBlankCells", "2", "maxDuplicateRows", "1")).status());
    assertEquals(
        JobStatus.FAILED,
        run(input, Map.of("maxBlankCells", "1", "maxDuplicateRows", "1")).status());
    assertEquals(
        JobStatus.FAILED,
        run(input, Map.of("maxBlankCells", "2", "maxDuplicateRows", "0")).status());
  }

  @Test
  void duplicatesCountEveryOccurrenceAfterTheFirstAndExcludeTheHeader() throws Exception {
    Path input = write("a,b\na,b\na,b\na,b\n");
    JobExecutionResult result = run(input, Map.of("maxDuplicateRows", "2"));
    assertEquals(JobStatus.SUCCEEDED, result.status());
    assertEquals("2", result.metrics().get("duplicateRows"));
    assertEquals("3", result.metrics().get("rows"));
  }

  @Test
  void blankHeaderFieldsAreNotDataCells() throws Exception {
    JobExecutionResult result = run(write(",\n1,2\n"), Map.of());
    assertEquals(JobStatus.SUCCEEDED, result.status());
    assertEquals("0", result.metrics().get("blankCells"));
  }

  @Test
  void headerlessDataCountsTheFirstRecordAndBlankLines() throws Exception {
    JobExecutionResult result =
        run(
            write("\n\n"),
            Map.of("header", "false", "maxBlankCells", "2", "maxDuplicateRows", "1"));
    assertEquals(JobStatus.SUCCEEDED, result.status());
    assertEquals("2", result.metrics().get("rows"));
    assertEquals("2", result.metrics().get("blankRows"));
    assertEquals("2", result.metrics().get("blankCells"));
    assertEquals("1", result.metrics().get("duplicateRows"));
  }

  @Test
  void blankCellsUseUnicodeWhitespaceWithoutTreatingLiteralNullAsMissing() throws Exception {
    JobExecutionResult result =
        run(write("value\n\"\u2003\"\nnull\n\"\t\"\n"), Map.of("maxBlankCells", "2"));
    assertEquals(JobStatus.SUCCEEDED, result.status());
    assertEquals("2", result.metrics().get("blankCells"));
    assertEquals("0", result.metrics().get("duplicateRows"));
  }

  @Test
  void duplicateEqualityPreservesFieldBoundariesCaseAndWhitespace() throws Exception {
    JobExecutionResult result =
        run(write("a,b\n\"x,y\",z\nx,\"y,z\"\nA,b\na,b\n a,b\nAa,b\nBB,b\n"), Map.of());
    assertEquals(JobStatus.SUCCEEDED, result.status());
    assertEquals("0", result.metrics().get("duplicateRows"));
    assertEquals("7", result.metrics().get("rows"));
  }

  @Test
  void quotedRecordsBomAndCustomDelimiterShareTheProfileParser() throws Exception {
    JobExecutionResult result =
        run(
            write("\ufeffid;note\r\n1;\"hello\nworld\"\r\n1;\"hello\nworld\"\r\n"),
            Map.of("delimiter", ";", "maxDuplicateRows", "1"));
    assertEquals(JobStatus.SUCCEEDED, result.status());
    assertEquals("2", result.metrics().get("rows"));
    assertEquals("2", result.metrics().get("columns"));
    assertEquals("1", result.metrics().get("duplicateRows"));
  }

  @Test
  void emptyAndHeaderOnlyInputsFailUnlessZeroRowsAreExplicitlyAllowed() throws Exception {
    for (String content : new String[] {"", "id\n"}) {
      Path input = write(content);
      JobExecutionResult rejected = run(input, Map.of());
      assertEquals(JobStatus.FAILED, rejected.status());
      assertEquals("CSV quality failed: rows=0 is below minRows=1", rejected.message());
      assertEquals(JobStatus.SUCCEEDED, run(input, Map.of("minRows", "0")).status());
    }
  }

  @Test
  void malformedGatesAreRejectedWithoutLeakingTheirValues() throws Exception {
    Path input = write("id\n1\n");
    for (String key : new String[] {"minRows", "maxBlankCells", "maxDuplicateRows"}) {
      for (String invalid :
          new String[] {"-1", "", "1.5", "9223372036854775808", "secret\nforged"}) {
        IllegalArgumentException error =
            assertThrows(IllegalArgumentException.class, () -> run(input, Map.of(key, invalid)));
        assertEquals(key + " must be a 64-bit integer >= 0", error.getMessage());
        assertEquals(null, error.getCause());
      }
    }
  }

  @Test
  void caseAndWhitespaceMisspellingsOfQualityGatesFailClosed() throws Exception {
    Path input = write("id\n1\n");
    for (String key : new String[] {"minrows", " maxBlankCells", "maxDuplicateRows "}) {
      assertThrows(IllegalArgumentException.class, () -> run(input, Map.of(key, "1")));
    }
    assertEquals(JobStatus.SUCCEEDED, run(input, Map.of("spark.master", "local[*]")).status());
  }

  @Test
  void invalidCsvAndUtf8FailBeforeAnyQualityResult() throws Exception {
    for (String content : new String[] {"a,b\n1\n", "a\n\"unterminated"}) {
      Path input = write(content);
      assertThrows(IllegalArgumentException.class, () -> run(input, Map.of()));
    }
    Path input = tempDir.resolve("bad.csv");
    Files.write(input, new byte[] {(byte) 0xc3, 0x28});
    assertTrue(
        assertThrows(IllegalArgumentException.class, () -> run(input, Map.of()))
            .getMessage()
            .contains("not valid UTF-8"));
  }

  @Test
  void byteLimitAppliesAtItsBoundary() throws Exception {
    Path input = write("id\n1\n");
    long bytes = Files.size(input);
    assertEquals(
        JobStatus.SUCCEEDED, run(input, Map.of("maxBytes", Long.toString(bytes))).status());
    assertThrows(
        IllegalArgumentException.class,
        () -> run(input, Map.of("maxBytes", Long.toString(bytes - 1))));
  }

  @Test
  void duplicateIndexMustFitTheSameHeapBudgetAsParsedRecords() throws Exception {
    String content = "value\n" + "x\n".repeat(100);
    Path input = write(content);
    Map<String, String> parameters = Map.of("input", input.toString(), "maxDuplicateRows", "99");
    long parsingOnly = CsvProfileJob.estimateHeapBytes(content, ',');
    CsvQualityJob limited =
        new CsvQualityJob(InputFiles.confinedTo(Optional.empty()), () -> parsingOnly);
    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> limited.run(request(parameters)));
    assertTrue(error.getMessage().contains("above the csv-quality budget"));
    assertEquals(
        JobStatus.SUCCEEDED,
        new CsvProfileJob(InputFiles.confinedTo(Optional.empty()), () -> parsingOnly)
            .run(request(parameters))
            .status());
    assertEquals(JobStatus.SUCCEEDED, run(input, Map.of("maxDuplicateRows", "99")).status());
  }

  @Test
  void interruptionBeforeLoadingIsPreservedAndReleasesTheRunSlot() throws Exception {
    Path input = write("id\n1\n");
    Thread.currentThread().interrupt();
    try {
      assertThrows(DataCraftException.class, () -> run(input, Map.of()));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
    assertEquals(JobStatus.SUCCEEDED, run(input, Map.of()).status());
  }

  @Test
  void interruptionDuringInspectionReleasesTheRunSlot() throws Exception {
    Path input = write("id\n1\n");
    CsvQualityJob job =
        new CsvQualityJob(
            InputFiles.confinedTo(Optional.empty()),
            () -> {
              Thread.currentThread().interrupt();
              return Long.MAX_VALUE;
            });
    try {
      assertThrows(
          DataCraftException.class, () -> job.run(request(Map.of("input", input.toString()))));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
    assertEquals(JobStatus.SUCCEEDED, run(input, Map.of()).status());
  }

  @Test
  @Timeout(30)
  void profileAndQualityShareOneMemoryBudgetAndWaitingCanBeCancelled() throws Exception {
    Path input = write("id\n1\n");
    CountDownLatch inside = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicReference<Throwable> profileError = new AtomicReference<>();
    AtomicReference<Throwable> qualityError = new AtomicReference<>();
    CsvProfileJob profile =
        new CsvProfileJob(
            InputFiles.confinedTo(Optional.empty()),
            () -> {
              inside.countDown();
              try {
                if (!release.await(15, TimeUnit.SECONDS)) {
                  throw new AssertionError("release never arrived");
                }
              } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
              }
              return Long.MAX_VALUE;
            });
    Thread first =
        new Thread(
            () -> {
              try {
                profile.run(request(Map.of("input", input.toString())));
              } catch (Throwable error) {
                profileError.set(error);
              }
            });
    Thread second =
        new Thread(
            () -> {
              try {
                run(input, Map.of());
              } catch (Throwable error) {
                qualityError.set(error);
              }
            });
    first.start();
    try {
      assertTrue(inside.await(10, TimeUnit.SECONDS));
      second.start();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (second.getState() != Thread.State.WAITING
          && second.isAlive()
          && System.nanoTime() < deadline) {
        Thread.onSpinWait();
      }
      assertEquals(Thread.State.WAITING, second.getState());
      second.interrupt();
      second.join(5000);
      assertFalse(second.isAlive());
      assertTrue(qualityError.get() instanceof DataCraftException);
    } finally {
      release.countDown();
      first.join(5000);
      second.join(5000);
    }
    assertEquals(null, profileError.get());
    assertEquals(JobStatus.SUCCEEDED, run(input, Map.of()).status());
  }

  @Test
  void enginePreservesQualityMetricsForFailedRuns() throws Exception {
    Path input = write("id\n1\n1\n");
    JobRegistry registry = new JobRegistry();
    registry.register(new CsvQualityJob(Optional.empty()));
    JobExecutionResult result =
        new JobExecutionEngine(registry).execute(request(Map.of("input", input.toString())));
    assertEquals(JobStatus.FAILED, result.status());
    assertEquals("1", result.metrics().get("duplicateRows"));
    assertTrue(result.metrics().containsKey("durationMillis"));
  }

  private Path write(String content) throws Exception {
    return Files.writeString(tempDir.resolve("quality.csv"), content);
  }

  private static JobExecutionResult run(Path input, Map<String, String> options) {
    Map<String, String> parameters = new HashMap<>(options);
    parameters.put("input", input.toString());
    return new CsvQualityJob(Optional.empty()).run(request(parameters));
  }

  private static JobExecutionRequest request(Map<String, String> parameters) {
    return JobExecutionRequest.of("csv-quality", Lifecycle.DEV, parameters);
  }
}
