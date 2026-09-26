package com.example.datacraft.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.datacraft.common.Lifecycle;
import com.example.datacraft.engine.JobExecutionRequest;
import com.example.datacraft.engine.JobExecutionResult;
import com.example.datacraft.engine.JobStatus;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CsvProfileJobTest {

  static final String EMPTY_SHA256 =
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

  @TempDir Path tempDir;

  @Test
  void profilesAHeaderedCsvIntoRowsColumnsBytesAndSha256() throws Exception {
    Path input = write("people.csv", "id,name\n1,Ada\n2,Grace\n");

    JobExecutionResult result = run(Map.of("input", input.toString()));

    assertEquals(JobStatus.SUCCEEDED, result.status());
    assertEquals("csv-profile", result.jobName());
    assertEquals("2 rows, 2 columns in " + input, result.message());
    assertEquals("2", result.metrics().get("rows"));
    assertEquals("2", result.metrics().get("columns"));
    assertEquals(Long.toString(Files.size(input)), result.metrics().get("bytes"));
    assertEquals(sha256(Files.readAllBytes(input)), result.metrics().get("sha256"));
    assertTrue(result.metrics().containsKey("durationMillis"));
  }

  @Test
  void countsEveryRecordAsDataWhenHeaderIsFalse() throws Exception {
    Path input = write("values.csv", "1,Ada\n2,Grace\n3,Linus\n");

    JobExecutionResult result = run(Map.of("input", input.toString(), "header", " FALSE "));

    assertEquals(JobStatus.SUCCEEDED, result.status());
    assertEquals("3", result.metrics().get("rows"));
    assertEquals("2", result.metrics().get("columns"));
  }

  @Test
  void keepsQuotedDelimitersQuotesAndLineBreaksInsideOneField() throws Exception {
    Path input =
        write(
            "notes.csv",
            "\uFEFFid,note\r\n1,\"line one\nline two\"\r\n2,\"say \"\"hi\"\", then go\"\r\n");

    JobExecutionResult result = run(Map.of("input", input.toString(), "header", "true"));

    assertEquals(JobStatus.SUCCEEDED, result.status());
    assertEquals("2", result.metrics().get("rows"));
    assertEquals("2", result.metrics().get("columns"));
    assertEquals(Long.toString(Files.size(input)), result.metrics().get("bytes"));
  }

  @Test
  void splitsFieldsOnTheConfiguredDelimiter() throws Exception {
    Path semicolons = write("semicolons.csv", "a;b;c\n1;2,5;3\n");
    Path tabs = write("tabs.tsv", "a\tb\n1\t2\n3\t4\n");

    JobExecutionResult semicolonResult =
        run(Map.of("input", semicolons.toString(), "delimiter", ";"));
    JobExecutionResult tabResult = run(Map.of("input", tabs.toString(), "delimiter", "\t"));

    assertEquals("3", semicolonResult.metrics().get("columns"));
    assertEquals("1", semicolonResult.metrics().get("rows"));
    assertEquals("2", tabResult.metrics().get("columns"));
    assertEquals("2", tabResult.metrics().get("rows"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "||", "\"", "\n", "\r", "\0"})
  void rejectsDelimitersCsvFilesCannotUse(String delimiter) throws Exception {
    Path input = write("in.csv", "a,b\n");

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> run(Map.of("input", input.toString(), "delimiter", delimiter)));

    assertEquals(
        "delimiter must be exactly one character other than a quote, newline or NUL",
        error.getMessage());
  }

  @Test
  void failsARaggedRecordNamingItsOneBasedRecordNumber() throws Exception {
    // Record 2 spans two lines, so the short record is record 3 on line 4.
    Path headered = write("headered.csv", "a,b\n\"x\ny\",1\n2\n");
    Path headerless = write("headerless.csv", "1,2\n3,4,5\n");

    IllegalArgumentException headeredError =
        assertThrows(
            IllegalArgumentException.class, () -> run(Map.of("input", headered.toString())));
    IllegalArgumentException headerlessError =
        assertThrows(
            IllegalArgumentException.class,
            () -> run(Map.of("input", headerless.toString(), "header", "false")));

    assertEquals(
        "CSV record 3 has 1 field but the header has 2 fields", headeredError.getMessage());
    assertEquals(
        "CSV record 2 has 3 fields but record 1 has 2 fields", headerlessError.getMessage());
  }

  @Test
  void treatsABlankLineAsARecordWithOneEmptyField() throws Exception {
    Path twoColumns = write("trailing-blank.csv", "a,b\n1,2\n\n");
    Path oneColumn = write("one-column.csv", "value\n1\n\n2\n");

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class, () -> run(Map.of("input", twoColumns.toString())));
    JobExecutionResult result = run(Map.of("input", oneColumn.toString()));

    assertEquals("CSV record 3 has 1 field but the header has 2 fields", error.getMessage());
    assertEquals("3", result.metrics().get("rows"));
  }

  @Test
  void passesTheExpectedRowsGateWhenTheCountMatches() throws Exception {
    Path input = write("in.csv", "id\n1\n2\n");

    JobExecutionResult result = run(Map.of("input", input.toString(), "expectedRows", " 2 "));

    assertEquals(JobStatus.SUCCEEDED, result.status());
  }

  @Test
  void failsTheExpectedRowsGateWithTheObservedMetrics() throws Exception {
    Path input = write("in.csv", "id\n1\n2\n");

    JobExecutionResult result = run(Map.of("input", input.toString(), "expectedRows", "3"));

    assertEquals(JobStatus.FAILED, result.status());
    assertEquals("Expected 3 rows but found 2", result.message());
    assertEquals("2", result.metrics().get("rows"));
    assertEquals(sha256(Files.readAllBytes(input)), result.metrics().get("sha256"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"-1", "", " ", "abc", "1.5", "1,000", "9223372036854775808"})
  void rejectsExpectedRowsThatIsNotANonnegative64BitInteger(String expectedRows) throws Exception {
    Path input = write("in.csv", "id\n1\n");

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> run(Map.of("input", input.toString(), "expectedRows", expectedRows)));

    assertEquals("expectedRows must be a 64-bit integer >= 0", error.getMessage());
  }

  @Test
  void rejectsACaseVariantOfAKnownParameterButIgnoresUnrelatedKeys() throws Exception {
    Path input = write("in.csv", "id\n1\n");

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> run(Map.of("input", input.toString(), "expectedrows", "5")));
    JobExecutionResult result =
        run(Map.of("input", input.toString(), "spark.master", "local[*]", "note", "x"));

    assertEquals("Unknown parameter expectedrows; did you mean expectedRows?", error.getMessage());
    assertEquals(JobStatus.SUCCEEDED, result.status());
  }

  @Test
  void refusesAFileAboveMaxBytesAndPointsToTheSparkRowCountJob() throws Exception {
    Path input = write("in.csv", "id\n1\n2\n");
    long size = Files.size(input);

    JobExecutionResult atLimit =
        run(Map.of("input", input.toString(), "maxBytes", Long.toString(size)));
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> run(Map.of("input", input.toString(), "maxBytes", Long.toString(size - 1))));

    assertEquals(JobStatus.SUCCEEDED, atLimit.status());
    assertTrue(error.getMessage().contains("above maxBytes=" + (size - 1)), error.getMessage());
    assertTrue(error.getMessage().contains("Spark row-count job"), error.getMessage());
  }

  @Test
  void appliesA64MebibyteDefaultLimitBeforeReadingTheFile() throws Exception {
    Path input = tempDir.resolve("large.csv");
    try (RandomAccessFile file = new RandomAccessFile(input.toFile(), "rw")) {
      file.setLength(CsvProfileJob.DEFAULT_MAX_BYTES + 1);
    }

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> run(Map.of("input", input.toString())));

    assertEquals(64L * 1024 * 1024, CsvProfileJob.DEFAULT_MAX_BYTES);
    assertTrue(error.getMessage().contains("above maxBytes=67108864"), error.getMessage());
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-1", "", "x", "2147483640", "9223372036854775807"})
  void rejectsMaxBytesOutsideTheLoadableRange(String maxBytes) throws Exception {
    Path input = write("in.csv", "id\n1\n");

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> run(Map.of("input", input.toString(), "maxBytes", maxBytes)));

    assertEquals("maxBytes must be an integer from 1 to 2147483639", error.getMessage());
  }

  @Test
  void profilesAnEmptyFileAsZeroRowsAndZeroColumns() throws Exception {
    Path input = write("empty.csv", "");

    JobExecutionResult headered = run(Map.of("input", input.toString(), "expectedRows", "0"));
    JobExecutionResult headerless = run(Map.of("input", input.toString(), "header", "false"));
    JobExecutionResult gated = run(Map.of("input", input.toString(), "expectedRows", "1"));

    for (JobExecutionResult result : List.of(headered, headerless)) {
      assertEquals(JobStatus.SUCCEEDED, result.status());
      assertEquals("0", result.metrics().get("rows"));
      assertEquals("0", result.metrics().get("columns"));
      assertEquals("0", result.metrics().get("bytes"));
      assertEquals(EMPTY_SHA256, result.metrics().get("sha256"));
    }
    assertEquals(JobStatus.FAILED, gated.status());
    assertEquals("Expected 1 rows but found 0", gated.message());
  }

  @Test
  void profilesABomOnlyFileAsEmptyAndAHeaderOnlyFileAsZeroRows() throws Exception {
    Path bomOnly = write("bom.csv", "\uFEFF");
    Path headerOnly = write("header.csv", "a,b,c\n");

    JobExecutionResult bomResult = run(Map.of("input", bomOnly.toString()));
    JobExecutionResult headerResult = run(Map.of("input", headerOnly.toString()));

    assertEquals("0", bomResult.metrics().get("rows"));
    assertEquals("0", bomResult.metrics().get("columns"));
    assertEquals("3", bomResult.metrics().get("bytes"));
    assertEquals("0", headerResult.metrics().get("rows"));
    assertEquals("3", headerResult.metrics().get("columns"));
  }

  @Test
  void rejectsInvalidUtf8NamingTheByteOffset() throws Exception {
    Path input = tempDir.resolve("latin1.csv");
    Files.write(input, new byte[] {'a', ',', 'b', '\n', (byte) 0xC3, '(', '\n'});

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> run(Map.of("input", input.toString())));

    assertEquals("Input " + input + " is not valid UTF-8 at byte offset 4", error.getMessage());
  }

  @Test
  void rejectsMalformedQuoting() throws Exception {
    Path input = write("broken.csv", "a,b\n\"unterminated,1\n");

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> run(Map.of("input", input.toString())));

    assertEquals("Unterminated quoted CSV field.", error.getMessage());
  }

  @Test
  void rejectsAHeaderValueThatIsNotABoolean() throws Exception {
    Path input = write("in.csv", "a\n1\n");

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> run(Map.of("input", input.toString(), "header", "yes")));

    assertEquals("header must be true or false", error.getMessage());
  }

  @Test
  void requiresTheInputParameterAndAnExistingRegularFile() throws Exception {
    Path missing = tempDir.resolve("missing.csv");
    Path directory = Files.createDirectory(tempDir.resolve("folder.csv"));
    Map<String, String> blank = new HashMap<>();
    blank.put("input", "   ");

    assertEquals(
        "Missing required parameter: input",
        assertThrows(IllegalArgumentException.class, () -> run(Map.of())).getMessage());
    assertEquals(
        "Missing required parameter: input",
        assertThrows(IllegalArgumentException.class, () -> run(blank)).getMessage());
    assertEquals(
        "Input file does not exist: " + missing,
        assertThrows(IllegalArgumentException.class, () -> run(Map.of("input", missing.toString())))
            .getMessage());
    assertEquals(
        "Input is not a regular file: " + directory,
        assertThrows(
                IllegalArgumentException.class, () -> run(Map.of("input", directory.toString())))
            .getMessage());
  }

  @Test
  void refusesToParseWhenTheHeapEstimateExceedsTheBudget() throws Exception {
    // Blank lines are the costliest input: one record list per byte.
    Path input = write("blank-lines.csv", "\n".repeat(1_000));
    Map<String, String> parameters = Map.of("input", input.toString(), "header", "false");

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> run(parameters, 64 * 1024L));
    JobExecutionResult result = run(parameters, 1024 * 1024L);

    assertTrue(error.getMessage().contains("MiB of heap to profile"), error.getMessage());
    assertTrue(error.getMessage().contains("Spark row-count job"), error.getMessage());
    assertEquals(JobStatus.SUCCEEDED, result.status());
    assertEquals("1000", result.metrics().get("rows"));
  }

  @Test
  void refusesToReadWhenLoadingTheFileAloneWouldExceedTheBudget() throws Exception {
    // One 10,000-char field: parsing needs about 20,152 bytes, but loading needs 3 x 10,000.
    Path input = write("wide.csv", "x".repeat(10_000));
    Map<String, String> parameters = Map.of("input", input.toString(), "header", "false");

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> run(parameters, 25_000L));

    assertTrue(error.getMessage().contains("MiB of heap to profile"), error.getMessage());
    assertEquals(JobStatus.SUCCEEDED, run(parameters, 30_000L).status());
  }

  @Test
  void estimatesHeapFromEveryPossibleRecordAndFieldBoundary() {
    // 3 possible records and 5 possible fields for 8 Latin-1 chars: 3*96 + 5*56 + 8*2.
    assertEquals(584L, CsvProfileJob.estimateHeapBytes("a,b\nc,d\n", ','));
    assertEquals(152L, CsvProfileJob.estimateHeapBytes("", ','));
    // Quoted delimiters still count, and one non-Latin-1 char makes every char cost 4 bytes.
    assertEquals(2 * 96 + 3 * 56 + 6 * 4L, CsvProfileJob.estimateHeapBytes("\"é,中\"\n", ','));
    // Empty unquoted fields share one String, so they cost only a list slot.
    assertEquals(2 * 96 + 56 + 3 * 8 + 3 * 2L, CsvProfileJob.estimateHeapBytes(",,\n", ','));
  }

  private JobExecutionResult run(Map<String, String> parameters) {
    return new CsvProfileJob(Optional.empty())
        .run(JobExecutionRequest.of("csv-profile", Lifecycle.DEV, parameters));
  }

  private JobExecutionResult run(Map<String, String> parameters, long heapBudgetBytes) {
    return new CsvProfileJob(InputFiles.confinedTo(Optional.empty()), () -> heapBudgetBytes)
        .run(JobExecutionRequest.of("csv-profile", Lifecycle.DEV, parameters));
  }

  private Path write(String name, String content) throws IOException {
    return Files.writeString(tempDir.resolve(name), content, StandardCharsets.UTF_8);
  }

  static String sha256(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }
}
