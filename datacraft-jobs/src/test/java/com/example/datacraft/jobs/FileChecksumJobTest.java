package com.example.datacraft.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.datacraft.common.Lifecycle;
import com.example.datacraft.engine.JobExecutionRequest;
import com.example.datacraft.engine.JobExecutionResult;
import com.example.datacraft.engine.JobStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FileChecksumJobTest {

  /** SHA-256 of the UTF-8 text {@code datacraft}. */
  private static final String DATACRAFT_SHA256 =
      "daa501f37955ee127679730f5a68588e36ed357b34448ed6e244a80a2bf2da4b";

  @TempDir Path tempDir;

  @Test
  void streamsAnyBinaryFileIntoSizeAndSha256() throws Exception {
    byte[] bytes = new byte[256 * 1024 + 7];
    for (int i = 0; i < bytes.length; i++) {
      bytes[i] = (byte) (i * 31);
    }
    Path input = Files.write(tempDir.resolve("blob.bin"), bytes);
    String expected = CsvProfileJobTest.sha256(bytes);

    JobExecutionResult result = run(Map.of("input", input.toString()));

    assertEquals(JobStatus.SUCCEEDED, result.status());
    assertEquals("file-checksum", result.jobName());
    assertEquals(Integer.toString(bytes.length), result.metrics().get("bytes"));
    assertEquals(expected, result.metrics().get("sha256"));
    assertEquals(
        "SHA-256 " + expected + " for " + bytes.length + " bytes in " + input, result.message());
  }

  @Test
  void checksumsAnEmptyFile() throws Exception {
    Path input = Files.createFile(tempDir.resolve("empty.bin"));

    JobExecutionResult result = run(Map.of("input", input.toString()));

    assertEquals("0", result.metrics().get("bytes"));
    assertEquals(CsvProfileJobTest.EMPTY_SHA256, result.metrics().get("sha256"));
  }

  @Test
  void acceptsAMatchingExpectedSha256InAnyCaseWithSurroundingSpace() throws Exception {
    Path input = Files.writeString(tempDir.resolve("in.txt"), "datacraft");

    JobExecutionResult result =
        run(
            Map.of(
                "input",
                input.toString(),
                "expectedSha256",
                "  " + DATACRAFT_SHA256.toUpperCase(Locale.ROOT) + "\t"));

    assertEquals(JobStatus.SUCCEEDED, result.status());
    assertEquals(DATACRAFT_SHA256, result.metrics().get("sha256"));
  }

  @Test
  void failsAMismatchedExpectedSha256WithTheObservedMetrics() throws Exception {
    Path input = Files.writeString(tempDir.resolve("in.txt"), "datacraft!");
    String actual = CsvProfileJobTest.sha256(Files.readAllBytes(input));

    JobExecutionResult result =
        run(Map.of("input", input.toString(), "expectedSha256", DATACRAFT_SHA256));

    assertEquals(JobStatus.FAILED, result.status());
    assertEquals("Expected SHA-256 " + DATACRAFT_SHA256 + " but found " + actual, result.message());
    assertEquals("10", result.metrics().get("bytes"));
    assertEquals(actual, result.metrics().get("sha256"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "daa501f37955ee127679730f5a68588e36ed357b34448ed6e244a80a2bf2da4",
        "daa501f37955ee127679730f5a68588e36ed357b34448ed6e244a80a2bf2da4b0",
        "gaa501f37955ee127679730f5a68588e36ed357b34448ed6e244a80a2bf2da4b",
        "daa501f3 7955ee127679730f5a68588e36ed357b34448ed6e244a80a2bf2da4b"
      })
  void rejectsAnExpectedSha256ThatIsNot64HexCharacters(String expectedSha256) throws Exception {
    Path input = Files.writeString(tempDir.resolve("in.txt"), "datacraft");

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> run(Map.of("input", input.toString(), "expectedSha256", expectedSha256)));

    assertEquals("expectedSha256 must be 64 hexadecimal characters", error.getMessage());
  }

  @Test
  void rejectsACaseVariantOfExpectedSha256() throws Exception {
    Path input = Files.writeString(tempDir.resolve("in.txt"), "datacraft");

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> run(Map.of("input", input.toString(), "expectedsha256", DATACRAFT_SHA256)));

    assertEquals(
        "Unknown parameter expectedsha256; did you mean expectedSha256?", error.getMessage());
  }

  @Test
  void requiresTheInputParameterAndAnExistingRegularFile() throws Exception {
    Path missing = tempDir.resolve("missing.bin");

    assertEquals(
        "Missing required parameter: input",
        assertThrows(IllegalArgumentException.class, () -> run(Map.of())).getMessage());
    assertEquals(
        "Input file does not exist: " + missing,
        assertThrows(IllegalArgumentException.class, () -> run(Map.of("input", missing.toString())))
            .getMessage());
    assertEquals(
        "Input is not a regular file: " + tempDir,
        assertThrows(IllegalArgumentException.class, () -> run(Map.of("input", tempDir.toString())))
            .getMessage());
  }

  private static JobExecutionResult run(Map<String, String> parameters) {
    return new FileChecksumJob(Optional.empty())
        .run(JobExecutionRequest.of("file-checksum", Lifecycle.DEV, parameters));
  }
}
