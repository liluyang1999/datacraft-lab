package com.example.datacraft.jobs;

import com.example.datacraft.engine.DataJob;
import com.example.datacraft.engine.JobExecutionRequest;
import com.example.datacraft.engine.JobExecutionResult;
import com.example.datacraft.engine.ParameterKeys;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Streams any regular file through SHA-256 in the JVM, without loading it into memory.
 *
 * <p>Parameters: {@code input} (required file path) and {@code expectedSha256} (optional, 64
 * hexadecimal characters in any case; a different checksum fails the job). Metrics: {@code bytes}
 * (the file size) and {@code sha256} (lower-case hex).
 */
public final class FileChecksumJob implements DataJob {

  /** Optional expected SHA-256 checksum, used as an integrity gate. */
  public static final String EXPECTED_SHA256 = "expectedSha256";

  private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-fA-F]{64}");
  private static final Set<String> KNOWN_KEYS = Set.of(ParameterKeys.INPUT, EXPECTED_SHA256);

  private final InputFiles inputs;

  /** Confines {@code input} under {@code DATACRAFT_DATA_ROOT} when that variable is set. */
  public FileChecksumJob() {
    this(InputFiles.fromEnvironment());
  }

  /**
   * Confines {@code input} strictly inside {@code dataRoot} when present; an empty value reads the
   * path unconfined.
   */
  public FileChecksumJob(Optional<Path> dataRoot) {
    this(InputFiles.confinedTo(dataRoot));
  }

  FileChecksumJob(InputFiles inputs) {
    this.inputs = inputs;
  }

  @Override
  public String name() {
    return "file-checksum";
  }

  @Override
  public String description() {
    return "Computes the SHA-256 checksum and size of a file in the JVM.";
  }

  @Override
  public JobExecutionResult run(JobExecutionRequest request) {
    Map<String, String> parameters = request.parameters();
    JobParameters.rejectCaseVariants(parameters, KNOWN_KEYS);
    String input = JobParameters.required(parameters, ParameterKeys.INPUT);
    Optional<String> expected = expectedSha256(parameters);

    InputFiles.InputFile file = inputs.open(input);
    long bytes = file.size();
    String sha256 = file.sha256Hex();

    Map<String, String> metrics = Map.of("bytes", Long.toString(bytes), "sha256", sha256);
    if (expected.isPresent() && !expected.get().equals(sha256)) {
      return JobExecutionResult.failed(
          name(),
          "Expected SHA-256 " + expected.get() + " but found " + sha256,
          metrics,
          request.startedAt(),
          Instant.now());
    }
    return JobExecutionResult.succeeded(
        name(),
        "SHA-256 " + sha256 + " for " + bytes + " bytes in " + input,
        metrics,
        request.startedAt(),
        Instant.now());
  }

  private static Optional<String> expectedSha256(Map<String, String> parameters) {
    String value = parameters.get(EXPECTED_SHA256);
    if (value == null) {
      return Optional.empty();
    }
    String trimmed = value.trim();
    if (!SHA256_HEX.matcher(trimmed).matches()) {
      throw new IllegalArgumentException(EXPECTED_SHA256 + " must be 64 hexadecimal characters");
    }
    return Optional.of(trimmed.toLowerCase(Locale.ROOT));
  }
}
