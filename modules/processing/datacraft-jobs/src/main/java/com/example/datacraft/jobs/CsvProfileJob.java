package com.example.datacraft.jobs;

import com.example.datacraft.common.DataCraftException;
import com.example.datacraft.engine.DataJob;
import com.example.datacraft.engine.JobExecutionRequest;
import com.example.datacraft.engine.JobExecutionResult;
import com.example.datacraft.engine.ParameterKeys;
import com.example.datacraft.io.CsvFiles;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * Profiles a small UTF-8 CSV file in the JVM, without Spark.
 *
 * <p>Parameters: {@code input} (required file path), {@code header} ({@code true}/{@code false},
 * default {@code true}), {@code delimiter} (exactly one character other than a quote, CR, LF or
 * NUL; default comma), {@code expectedRows} (optional nonnegative 64-bit integer; a different row
 * count fails the job) and {@code maxBytes} (optional, 1 to 1073741819; default 64 MiB). Metrics:
 * {@code rows} (data records, excluding the header), {@code columns}, {@code bytes} and {@code
 * sha256} of the profiled file.
 *
 * <p>The whole file is decoded and parsed with {@link CsvFiles} in memory, so a file above {@code
 * maxBytes} fails with a pointer to the Spark {@code row-count} job. Parsed records need about 6
 * times the file size in heap for typical fields and far more for very short fields or blank lines,
 * so the job also fails when a conservative estimate exceeds a quarter of the JVM's maximum heap:
 * before reading, when three times the file size (the bytes plus their decoded text) is above that
 * budget, and before parsing, when the estimate for the parsed records is. The budget is for one
 * run, so a JVM loads one file at a time: concurrent runs, which the HTTP API allows, wait their
 * turn instead of each taking a quarter of the heap.
 *
 * <p>Records follow {@link CsvFiles} semantics: quoted fields may contain delimiters, quotes and
 * line breaks, a leading BOM is ignored, and a blank line is a record with one empty field. Every
 * record must have as many fields as the first record (the header when {@code header=true}); an
 * empty file has 0 rows and 0 columns.
 */
public final class CsvProfileJob implements DataJob {

  /** Upper bound on the file size to load; above it, use the Spark {@code row-count} job. */
  public static final String MAX_BYTES = "maxBytes";

  /** Default {@code maxBytes}: 64 MiB. */
  public static final long DEFAULT_MAX_BYTES = 64L * 1024 * 1024;

  /**
   * The largest file whose decoded text is sure to fit one String: a String with any char above
   * U+00FF stores two bytes per char, in an array of at most {@code Integer.MAX_VALUE - 8} bytes.
   * Above it, decoding would end in an OutOfMemoryError whatever the heap size.
   */
  static final long MAX_LOADABLE_BYTES = (Integer.MAX_VALUE - 8) / 2;

  /** Held while a run loads and parses its file, so one heap budget covers the whole JVM. */
  private static final ReentrantLock ONE_RUN = new ReentrantLock(true);

  // Conservative heap costs with compressed pointers, checked against CsvFiles.parse on JDK 25: a
  // record's list plus its slot in the outer list, a field's String plus its array header and list
  // slot, an empty unquoted field's list slot (it shares ""), and a char held twice (the content
  // and a field copy) at 1 byte for Latin-1 text or 2.
  private static final long RECORD_HEAP_BYTES = 96;
  private static final long FIELD_HEAP_BYTES = 56;
  private static final long EMPTY_FIELD_HEAP_BYTES = 8;
  private static final long LATIN1_CHAR_HEAP_BYTES = 2;
  private static final long UTF16_CHAR_HEAP_BYTES = 4;
  private static final long READ_HEAP_BYTES_PER_FILE_BYTE = 3;

  private static final Set<String> KNOWN_KEYS =
      Set.of(
          ParameterKeys.INPUT,
          ParameterKeys.HEADER,
          ParameterKeys.DELIMITER,
          ParameterKeys.EXPECTED_ROWS,
          MAX_BYTES);

  private final InputFiles inputs;
  private final LongSupplier heapBudgetBytes;

  /** Confines {@code input} under {@code DATACRAFT_DATA_ROOT} when that variable is set. */
  public CsvProfileJob() {
    this(InputFiles.fromEnvironment(), CsvProfileJob::defaultHeapBudgetBytes);
  }

  /**
   * Confines {@code input} strictly inside {@code dataRoot} when present; an empty value reads the
   * path unconfined.
   */
  public CsvProfileJob(Optional<Path> dataRoot) {
    this(InputFiles.confinedTo(dataRoot), CsvProfileJob::defaultHeapBudgetBytes);
  }

  CsvProfileJob(InputFiles inputs, LongSupplier heapBudgetBytes) {
    this.inputs = inputs;
    this.heapBudgetBytes = heapBudgetBytes;
  }

  @Override
  public String name() {
    return "csv-profile";
  }

  @Override
  public String description() {
    return "Profiles a small UTF-8 CSV file in the JVM: rows, columns, bytes and SHA-256.";
  }

  @Override
  public JobExecutionResult run(JobExecutionRequest request) {
    Map<String, String> parameters = request.parameters();
    JobParameters.rejectCaseVariants(parameters, KNOWN_KEYS);
    String input = JobParameters.required(parameters, ParameterKeys.INPUT);
    boolean header = JobParameters.booleanValue(parameters, ParameterKeys.HEADER, true);
    char delimiter = delimiter(parameters);
    OptionalLong expectedRows =
        JobParameters.longInRange(parameters, ParameterKeys.EXPECTED_ROWS, 0L, Long.MAX_VALUE);
    long maxBytes =
        JobParameters.longInRange(parameters, MAX_BYTES, 1L, MAX_LOADABLE_BYTES)
            .orElse(DEFAULT_MAX_BYTES);

    InputFiles.InputFile file = inputs.open(input);
    Profile profile;
    awaitTurn();
    try {
      profile = profile(file, input, maxBytes, delimiter, header);
    } finally {
      ONE_RUN.unlock();
    }

    Map<String, String> metrics =
        Map.of(
            "rows", Long.toString(profile.rows()),
            "columns", Integer.toString(profile.columns()),
            "bytes", Long.toString(profile.bytes()),
            "sha256", profile.sha256());
    if (expectedRows.isPresent() && expectedRows.getAsLong() != profile.rows()) {
      return JobExecutionResult.failed(
          name(),
          "Expected " + expectedRows.getAsLong() + " rows but found " + profile.rows(),
          metrics,
          request.startedAt(),
          Instant.now());
    }
    return JobExecutionResult.succeeded(
        name(),
        profile.rows() + " rows, " + profile.columns() + " columns in " + input,
        metrics,
        request.startedAt(),
        Instant.now());
  }

  /** Waits for the runs ahead; an interrupt while waiting fails this run and stays set. */
  private static void awaitTurn() {
    try {
      ONE_RUN.lockInterruptibly();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new DataCraftException(
          "Interrupted while waiting for another csv-profile run to finish.", exception);
    }
  }

  /**
   * Loads, parses and measures the file. The caller holds {@link #ONE_RUN}, and the file content
   * and its records are garbage when this returns.
   */
  private Profile profile(
      InputFiles.InputFile file, String input, long maxBytes, char delimiter, boolean header) {
    long size = file.size();
    requireWithinLimit(size, input, maxBytes);
    // Loading holds the bytes plus their decoded copy (up to two bytes per char): refuse a file
    // whose load alone would exceed the budget before allocating anything.
    requireHeapBudget(size * READ_HEAP_BYTES_PER_FILE_BYTE, input);
    Snapshot snapshot = read(file, input, maxBytes);
    requireHeapBudget(estimateHeapBytes(snapshot.content(), delimiter), input);
    List<List<String>> records = CsvFiles.parse(snapshot.content(), delimiter);
    int columns = records.isEmpty() ? 0 : records.getFirst().size();
    for (int index = 1; index < records.size(); index++) {
      int fields = records.get(index).size();
      if (fields != columns) {
        throw new IllegalArgumentException(
            "CSV record "
                + (index + 1)
                + " has "
                + fieldCount(fields)
                + " but "
                + (header ? "the header" : "record 1")
                + " has "
                + fieldCount(columns));
      }
    }
    long rows = header && !records.isEmpty() ? records.size() - 1L : records.size();
    return new Profile(rows, columns, snapshot.bytes(), snapshot.sha256());
  }

  /**
   * Upper-bound estimate of the heap that {@code content} and its {@link CsvFiles#parse} records
   * hold: every line terminator may end a record and every delimiter may end a field, even inside
   * quotes. Only a field with no characters at all is costed as the shared empty string.
   */
  static long estimateHeapBytes(String content, char delimiter) {
    long records = 1;
    long fields = 1; // the last field ends at the end of the content
    long emptyFields = 0;
    boolean fieldEmpty = true;
    boolean latin1 = true;
    for (int index = 0; index < content.length(); index++) {
      char c = content.charAt(index);
      if (c == '\n' || c == '\r' || c == delimiter) {
        if (fieldEmpty) {
          emptyFields++;
        } else {
          fields++;
        }
        if (c != delimiter) {
          records++;
        }
        fieldEmpty = true;
      } else {
        fieldEmpty = false;
      }
      latin1 &= c <= 0xFF;
    }
    return records * RECORD_HEAP_BYTES
        + fields * FIELD_HEAP_BYTES
        + emptyFields * EMPTY_FIELD_HEAP_BYTES
        + content.length() * (latin1 ? LATIN1_CHAR_HEAP_BYTES : UTF16_CHAR_HEAP_BYTES);
  }

  private static long defaultHeapBudgetBytes() {
    return Runtime.getRuntime().maxMemory() / 4;
  }

  private void requireHeapBudget(long estimate, String input) {
    long budget = heapBudgetBytes.getAsLong();
    if (estimate > budget) {
      throw new IllegalArgumentException(
          "Input "
              + input
              + " needs up to "
              + mebibytes(estimate)
              + " MiB of heap to profile, above the csv-profile budget of "
              + mebibytes(budget)
              + " MiB (a quarter of the maximum heap); use the Spark row-count job"
              + " (inputFormat=csv) or give the JVM more heap");
    }
  }

  private static char delimiter(Map<String, String> parameters) {
    String value = parameters.getOrDefault(ParameterKeys.DELIMITER, ",");
    if (value.length() != 1 || "\"\r\n\0".indexOf(value.charAt(0)) >= 0) {
      throw new IllegalArgumentException(
          "delimiter must be exactly one character other than a quote, newline or NUL");
    }
    return value.charAt(0);
  }

  /** Reads the file once, so every metric describes the same bytes. */
  private static Snapshot read(InputFiles.InputFile file, String input, long maxBytes) {
    byte[] bytes = file.readBytes();
    // The file may have grown after its size was checked.
    requireWithinLimit(bytes.length, input, maxBytes);
    requireUtf8(bytes, input);
    return new Snapshot(
        new String(bytes, StandardCharsets.UTF_8),
        bytes.length,
        HexFormat.of().formatHex(sha256(bytes)));
  }

  private static void requireWithinLimit(long size, String input, long maxBytes) {
    if (size > maxBytes) {
      throw new IllegalArgumentException(
          "Input "
              + input
              + " is "
              + size
              + " bytes, above maxBytes="
              + maxBytes
              + "; csv-profile loads the whole file into memory, so use the Spark row-count job"
              + " (inputFormat=csv) for larger files");
    }
  }

  /** Validates strictly through a small buffer, so decoding never holds a second full copy. */
  private static void requireUtf8(byte[] bytes, String input) {
    CharsetDecoder decoder =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    ByteBuffer in = ByteBuffer.wrap(bytes);
    CharBuffer out = CharBuffer.allocate(8192);
    CoderResult result;
    do {
      out.clear();
      result = decoder.decode(in, out, true);
      if (result.isError()) {
        // The decoder stops with the input positioned at the start of the malformed sequence.
        throw new IllegalArgumentException(
            "Input " + input + " is not valid UTF-8 at byte offset " + in.position());
      }
    } while (result.isOverflow());
  }

  private static byte[] sha256(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (NoSuchAlgorithmException exception) {
      throw new DataCraftException("SHA-256 digest algorithm is unavailable.", exception);
    }
  }

  private static long mebibytes(long bytes) {
    return Math.ceilDiv(bytes, 1L << 20);
  }

  private static String fieldCount(int fields) {
    return fields == 1 ? "1 field" : fields + " fields";
  }

  private record Snapshot(String content, long bytes, String sha256) {}

  private record Profile(long rows, int columns, long bytes, String sha256) {}
}
