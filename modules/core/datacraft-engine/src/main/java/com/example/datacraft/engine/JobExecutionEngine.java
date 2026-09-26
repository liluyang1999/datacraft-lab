package com.example.datacraft.engine;

import java.time.Instant;

/**
 * Runs catalog jobs and turns every outcome into a {@link JobExecutionResult}.
 *
 * <p>An unknown job name, a {@code null} result, and every {@link Throwable} a job throws (checked
 * exceptions from Scala jobs, {@link LinkageError}s such as a missing Spark runtime, {@link
 * AssertionError}s) become a {@link JobStatus#FAILED} result, and the throwable is logged once at
 * {@code ERROR} through {@link System.Logger}. {@link VirtualMachineError}s ({@link
 * OutOfMemoryError}, {@link StackOverflowError}, {@link InternalError}) propagate to the caller
 * because the JVM may no longer be in a usable state. An {@link InterruptedException} also restores
 * the calling thread's interrupt flag.
 *
 * <p>For an exception the result message is the exception's message when it is non-blank, otherwise
 * its fully qualified class name. For an {@link Error} it is the simple class name followed by the
 * non-blank message, e.g. {@code NoClassDefFoundError: org/apache/spark/sql/SparkSession}.
 */
public final class JobExecutionEngine {

  private static final System.Logger LOGGER = System.getLogger(JobExecutionEngine.class.getName());

  private final JobCatalog catalog;

  public JobExecutionEngine(JobCatalog catalog) {
    if (catalog == null) {
      throw new IllegalArgumentException("Job catalog must not be null.");
    }
    this.catalog = catalog;
  }

  public JobExecutionResult execute(JobExecutionRequest request) {
    Instant startedAt = request.startedAt();
    try {
      DataJob job = catalog.require(request.jobName());
      JobExecutionResult result = job.run(request);
      if (result == null) {
        return JobExecutionResult.failure(
            request.jobName(), "Job returned null result.", startedAt, Instant.now());
      }
      return result;
    } catch (Throwable failure) {
      if (failure instanceof VirtualMachineError fatal) {
        throw fatal;
      }
      // Scala jobs may throw checked exceptions despite the Java interface's signature.
      if (failure instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      LOGGER.log(System.Logger.Level.ERROR, "Job " + request.jobName() + " failed", failure);
      return JobExecutionResult.failure(
          request.jobName(), failureMessage(failure), startedAt, Instant.now());
    }
  }

  private static String failureMessage(Throwable failure) {
    String message = failure.getMessage();
    boolean hasMessage = message != null && !message.isBlank();
    if (failure instanceof Error) {
      String type = failure.getClass().getSimpleName();
      if (type.isEmpty()) {
        type = failure.getClass().getName();
      }
      return hasMessage ? type + ": " + message : type;
    }
    // getName(), not getSimpleName(): anonymous classes have an empty simple name.
    return hasMessage ? message : failure.getClass().getName();
  }
}
