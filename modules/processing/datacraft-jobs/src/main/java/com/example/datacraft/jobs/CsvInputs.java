package com.example.datacraft.jobs;

import com.example.datacraft.common.DataCraftException;
import com.example.datacraft.engine.ParameterKeys;
import com.example.datacraft.io.CsvFiles;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.LongSupplier;

/** Confined, budgeted CSV snapshots; the shared lock covers both parsing and analysis. */
final class CsvInputs {
  /** Upper bound on the file size to load; above it, use the Spark {@code row-count} job. */
  static final String MAX_BYTES = "maxBytes";

  /** Default {@code maxBytes}: 64 MiB. */
  static final long DEFAULT_MAX_BYTES = 64L * 1024 * 1024;

  /**
   * The largest file whose decoded text is sure to fit one String: a String with any char above
   * U+00FF stores two bytes per char, in an array of at most {@code Integer.MAX_VALUE - 8} bytes.
   * Above it, decoding would end in an OutOfMemoryError whatever the heap size.
   */
  static final long MAX_LOADABLE_BYTES = (Integer.MAX_VALUE - 8) / 2;

  /** Shared by every in-memory CSV job, including its analysis callback. */
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

  private final InputFiles inputs;
  private final LongSupplier heapBudgetBytes;
  private final String jobName;

  CsvInputs(InputFiles inputs, LongSupplier heapBudgetBytes, String jobName) {
    this.inputs = inputs;
    this.heapBudgetBytes = heapBudgetBytes;
    this.jobName = jobName;
  }

  static Options options(Map<String, String> parameters) {
    return new Options(
        JobParameters.required(parameters, ParameterKeys.INPUT),
        JobParameters.booleanValue(parameters, ParameterKeys.HEADER, true),
        delimiter(parameters),
        JobParameters.longInRange(parameters, MAX_BYTES, 1L, MAX_LOADABLE_BYTES)
            .orElse(DEFAULT_MAX_BYTES));
  }

  /**
   * The callback consumes the snapshot before releasing its memory budget; it must not retain
   * records.
   */
  <T> T inspect(Options options, long extraBytesPerRecord, Function<Parsed, T> analyze) {
    InputFiles.InputFile file = inputs.open(options.input());
    try {
      ONE_RUN.lockInterruptibly();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new DataCraftException(
          "Interrupted while waiting for another " + jobName + " run to finish.", exception);
    }
    try {
      long size = file.size();
      requireWithinLimit(size, options.input(), options.maxBytes());
      requireHeapBudget(size * READ_HEAP_BYTES_PER_FILE_BYTE, options.input());
      Snapshot snapshot = read(file, options.input(), options.maxBytes());
      requireHeapBudget(
          estimateHeapBytes(snapshot.content(), options.delimiter(), extraBytesPerRecord),
          options.input());
      List<List<String>> records = CsvFiles.parse(snapshot.content(), options.delimiter());
      checkInterrupted();
      int columns = records.isEmpty() ? 0 : records.getFirst().size();
      for (int index = 1; index < records.size(); index++) {
        checkInterrupted();
        int fields = records.get(index).size();
        if (fields != columns) {
          throw new IllegalArgumentException(
              "CSV record "
                  + (index + 1)
                  + " has "
                  + fieldCount(fields)
                  + " but "
                  + (options.header() ? "the header" : "record 1")
                  + " has "
                  + fieldCount(columns));
        }
      }
      return analyze.apply(
          new Parsed(records, options.header(), snapshot.bytes(), snapshot.sha256()));
    } finally {
      ONE_RUN.unlock();
    }
  }

  static void checkInterrupted() {
    if (Thread.currentThread().isInterrupted()) {
      throw new DataCraftException("Interrupted while reading or analyzing CSV input.");
    }
  }

  record Options(String input, boolean header, char delimiter, long maxBytes) {}

  record Parsed(List<List<String>> records, boolean header, long bytes, String sha256) {
    int firstDataRecord() {
      return header && !records.isEmpty() ? 1 : 0;
    }

    long rows() {
      return records.size() - (long) firstDataRecord();
    }

    int columns() {
      return records.isEmpty() ? 0 : records.getFirst().size();
    }
  }

  /**
   * Upper-bound estimate of the heap that {@code content} and its {@link CsvFiles#parse} records
   * hold: every line terminator (LF, CR, or CRLF as one) may end a record and every delimiter may
   * end a field, even inside quotes. Only a field with no characters at all is costed as the shared
   * empty string.
   */
  static long estimateHeapBytes(String content, char delimiter, long extraBytesPerRecord) {
    long records = 1;
    long fields = 1; // the last field ends at the end of the content
    long emptyFields = 0;
    boolean fieldEmpty = true;
    boolean latin1 = true;
    for (int index = 0; index < content.length(); index++) {
      if ((index & 4095) == 0) {
        checkInterrupted();
      }
      char c = content.charAt(index);
      latin1 &= c <= 0xFF;
      if (c == '\n' && index > 0 && content.charAt(index - 1) == '\r') {
        continue; // CRLF ends one record, as it does for CsvFiles.parse
      }
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
    }
    return records * (RECORD_HEAP_BYTES + extraBytesPerRecord)
        + fields * FIELD_HEAP_BYTES
        + emptyFields * EMPTY_FIELD_HEAP_BYTES
        + content.length() * (latin1 ? LATIN1_CHAR_HEAP_BYTES : UTF16_CHAR_HEAP_BYTES);
  }

  static long defaultHeapBudgetBytes() {
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
              + " MiB of heap to profile, above the "
              + jobName
              + " budget of "
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
  private Snapshot read(InputFiles.InputFile file, String input, long maxBytes) {
    byte[] bytes = file.readBytes();
    // The file may have grown after its size was checked.
    requireWithinLimit(bytes.length, input, maxBytes);
    checkInterrupted();
    requireUtf8(bytes, input);
    return new Snapshot(
        new String(bytes, StandardCharsets.UTF_8),
        bytes.length,
        HexFormat.of().formatHex(sha256(bytes)));
  }

  private void requireWithinLimit(long size, String input, long maxBytes) {
    if (size > maxBytes) {
      throw new IllegalArgumentException(
          "Input "
              + input
              + " is "
              + size
              + " bytes, above maxBytes="
              + maxBytes
              + "; "
              + jobName
              + " loads the whole file into memory, so use the Spark row-count job"
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
      checkInterrupted();
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
}
