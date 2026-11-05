package com.example.datacraft.jobs;

import com.example.datacraft.engine.DataJob;
import com.example.datacraft.engine.JobExecutionRequest;
import com.example.datacraft.engine.JobExecutionResult;
import com.example.datacraft.engine.ParameterKeys;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.LongSupplier;

/**
 * Checks a small, rectangular UTF-8 CSV snapshot before downstream processing.
 *
 * <p>Input, header, delimiter and maxBytes follow {@link CsvProfileJob}. Quality gates minRows
 * (default 1), maxBlankCells (default 0) and maxDuplicateRows (default 0) accept nonnegative 64-bit
 * integers. Header fields are excluded from quality counts. A blank cell satisfies {@link
 * String#isBlank}; a blank row has only blank cells. Each occurrence after the first identical
 * parsed data row is a duplicate; values are not trimmed, case-folded or concatenated.
 *
 * <p>Quality failures retain all aggregate metrics, but never disclose field values. Parsing, file
 * access and invalid parameters fail through the engine's normal error contract. The shared CSV
 * heap budget includes a conservative 96 bytes per potential record for the duplicate index, in
 * addition to the existing input and parsing estimates. No source file is modified.
 */
public final class CsvQualityJob implements DataJob {

  public static final String MIN_ROWS = "minRows";
  public static final String MAX_BLANK_CELLS = "maxBlankCells";
  public static final String MAX_DUPLICATE_ROWS = "maxDuplicateRows";

  private static final long DUPLICATE_INDEX_BYTES_PER_RECORD = 96;
  private static final Set<String> KNOWN_KEYS =
      Set.of(
          ParameterKeys.INPUT,
          ParameterKeys.HEADER,
          ParameterKeys.DELIMITER,
          CsvInputs.MAX_BYTES,
          MIN_ROWS,
          MAX_BLANK_CELLS,
          MAX_DUPLICATE_ROWS);

  private final CsvInputs csv;

  /** Confines input under DATACRAFT_DATA_ROOT when configured. */
  public CsvQualityJob() {
    this(InputFiles.fromEnvironment(), CsvInputs::defaultHeapBudgetBytes);
  }

  public CsvQualityJob(Optional<Path> dataRoot) {
    this(InputFiles.confinedTo(dataRoot), CsvInputs::defaultHeapBudgetBytes);
  }

  CsvQualityJob(InputFiles inputs, LongSupplier heapBudgetBytes) {
    csv = new CsvInputs(inputs, heapBudgetBytes, name());
  }

  @Override
  public String name() {
    return "csv-quality";
  }

  @Override
  public String description() {
    return "Checks a small UTF-8 CSV for minimum rows, blank cells and duplicate rows.";
  }

  @Override
  public JobExecutionResult run(JobExecutionRequest request) {
    Map<String, String> parameters = request.parameters();
    JobParameters.rejectCaseVariants(parameters, KNOWN_KEYS);
    CsvInputs.Options options = CsvInputs.options(parameters);
    long minRows = gate(parameters, MIN_ROWS, 1);
    long maxBlankCells = gate(parameters, MAX_BLANK_CELLS, 0);
    long maxDuplicateRows = gate(parameters, MAX_DUPLICATE_ROWS, 0);
    return csv.inspect(
        options,
        DUPLICATE_INDEX_BYTES_PER_RECORD,
        parsed -> {
          Counts counts = analyze(parsed);
          Map<String, String> metrics =
              Map.of(
                  "rows", Long.toString(parsed.rows()),
                  "columns", Integer.toString(parsed.columns()),
                  "blankCells", Long.toString(counts.blankCells()),
                  "blankRows", Long.toString(counts.blankRows()),
                  "duplicateRows", Long.toString(counts.duplicateRows()),
                  "bytes", Long.toString(parsed.bytes()),
                  "sha256", parsed.sha256());
          List<String> failures = new ArrayList<>();
          if (parsed.rows() < minRows) {
            failures.add("rows=" + parsed.rows() + " is below minRows=" + minRows);
          }
          if (counts.blankCells() > maxBlankCells) {
            failures.add(
                "blankCells=" + counts.blankCells() + " exceeds maxBlankCells=" + maxBlankCells);
          }
          if (counts.duplicateRows() > maxDuplicateRows) {
            failures.add(
                "duplicateRows="
                    + counts.duplicateRows()
                    + " exceeds maxDuplicateRows="
                    + maxDuplicateRows);
          }
          if (!failures.isEmpty()) {
            return JobExecutionResult.failed(
                name(),
                "CSV quality failed: " + String.join("; ", failures),
                metrics,
                request.startedAt(),
                Instant.now());
          }
          return JobExecutionResult.succeeded(
              name(),
              "CSV quality passed: "
                  + parsed.rows()
                  + " rows, "
                  + counts.blankCells()
                  + " blank cells, "
                  + counts.duplicateRows()
                  + " duplicate rows",
              metrics,
              request.startedAt(),
              Instant.now());
        });
  }

  private static long gate(Map<String, String> parameters, String key, long defaultValue) {
    return JobParameters.longInRange(parameters, key, 0, Long.MAX_VALUE).orElse(defaultValue);
  }

  private static Counts analyze(CsvInputs.Parsed parsed) {
    // A balanced tree keeps adversarial String hash collisions from making the scan quadratic.
    Set<List<String>> seen = new TreeSet<>(CsvQualityJob::compareRows);
    long blankCells = 0;
    long blankRows = 0;
    long duplicateRows = 0;
    for (int index = parsed.firstDataRecord(); index < parsed.records().size(); index++) {
      CsvInputs.checkInterrupted();
      List<String> row = parsed.records().get(index);
      boolean blankRow = true;
      for (String field : row) {
        CsvInputs.checkInterrupted();
        if (field.isBlank()) {
          blankCells++;
        } else {
          blankRow = false;
        }
      }
      if (blankRow) {
        blankRows++;
      }
      if (!seen.add(row)) {
        duplicateRows++;
      }
    }
    return new Counts(blankCells, blankRows, duplicateRows);
  }

  private record Counts(long blankCells, long blankRows, long duplicateRows) {}

  private static int compareRows(List<String> left, List<String> right) {
    for (int index = 0; index < Math.min(left.size(), right.size()); index++) {
      CsvInputs.checkInterrupted();
      int compared = left.get(index).compareTo(right.get(index));
      if (compared != 0) {
        return compared;
      }
    }
    return Integer.compare(left.size(), right.size());
  }
}
