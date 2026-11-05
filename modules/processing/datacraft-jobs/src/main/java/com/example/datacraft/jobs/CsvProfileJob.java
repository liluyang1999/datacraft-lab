package com.example.datacraft.jobs;

import com.example.datacraft.engine.DataJob;
import com.example.datacraft.engine.JobExecutionRequest;
import com.example.datacraft.engine.JobExecutionResult;
import com.example.datacraft.engine.ParameterKeys;
import com.example.datacraft.io.CsvFiles;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
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

  public static final String MAX_BYTES = CsvInputs.MAX_BYTES;
  public static final long DEFAULT_MAX_BYTES = CsvInputs.DEFAULT_MAX_BYTES;
  static final long MAX_LOADABLE_BYTES = CsvInputs.MAX_LOADABLE_BYTES;

  private static final Set<String> KNOWN_KEYS =
      Set.of(
          ParameterKeys.INPUT,
          ParameterKeys.HEADER,
          ParameterKeys.DELIMITER,
          ParameterKeys.EXPECTED_ROWS,
          MAX_BYTES);

  private final CsvInputs csv;

  /** Confines input under DATACRAFT_DATA_ROOT when configured. */
  public CsvProfileJob() {
    this(InputFiles.fromEnvironment(), CsvInputs::defaultHeapBudgetBytes);
  }

  public CsvProfileJob(Optional<Path> dataRoot) {
    this(InputFiles.confinedTo(dataRoot), CsvInputs::defaultHeapBudgetBytes);
  }

  CsvProfileJob(InputFiles inputs, LongSupplier heapBudgetBytes) {
    csv = new CsvInputs(inputs, heapBudgetBytes, name());
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
    CsvInputs.Options options = CsvInputs.options(parameters);
    OptionalLong expectedRows =
        JobParameters.longInRange(parameters, ParameterKeys.EXPECTED_ROWS, 0L, Long.MAX_VALUE);
    return csv.inspect(
        options,
        0,
        profile -> {
          Map<String, String> metrics =
              Map.of(
                  "rows",
                  Long.toString(profile.rows()),
                  "columns",
                  Integer.toString(profile.columns()),
                  "bytes",
                  Long.toString(profile.bytes()),
                  "sha256",
                  profile.sha256());
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
              profile.rows() + " rows, " + profile.columns() + " columns in " + options.input(),
              metrics,
              request.startedAt(),
              Instant.now());
        });
  }

  static long estimateHeapBytes(String content, char delimiter) {
    return CsvInputs.estimateHeapBytes(content, delimiter, 0);
  }
}
