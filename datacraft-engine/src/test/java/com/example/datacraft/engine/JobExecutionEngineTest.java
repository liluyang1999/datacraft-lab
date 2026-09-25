package com.example.datacraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.datacraft.common.DataCraftException;
import com.example.datacraft.common.Lifecycle;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class JobExecutionEngineTest {

  @Test
  void executesRegisteredJobWithImmutableRequestParameters() {
    JobExecutionEngine engine =
        engineWith(
            "echo",
            request -> {
              assertThrows(
                  UnsupportedOperationException.class,
                  () -> request.parameters().put("injected", "value"));
              return JobExecutionResult.success(
                  request.jobName(),
                  request.parameters().get("message"),
                  request.startedAt(),
                  request.startedAt());
            });
    Map<String, String> source = new HashMap<>(Map.of("message", "hello"));
    JobExecutionRequest request = JobExecutionRequest.of("echo", Lifecycle.DEV, source);
    source.put("message", "changed");

    JobExecutionResult result = engine.execute(request);

    assertEquals(JobStatus.SUCCEEDED, result.status(), result.message());
    assertEquals("hello", result.message());
  }

  @Test
  void convertsJobExceptionsIntoFailedResults() {
    JobExecutionEngine engine =
        engineWith(
            "broken",
            request -> {
              throw new IllegalStateException("boom");
            });

    JobExecutionResult result =
        engine.execute(JobExecutionRequest.of("broken", Lifecycle.PROD, Map.of()));

    assertEquals(JobStatus.FAILED, result.status());
    assertTrue(result.message().contains("boom"));
  }

  static Stream<Arguments> nonFatalErrors() {
    return Stream.of(
        Arguments.of(
            new NoClassDefFoundError("org/apache/spark/sql/SparkSession"),
            "NoClassDefFoundError: org/apache/spark/sql/SparkSession"),
        Arguments.of(new AssertionError("broken invariant"), "AssertionError: broken invariant"),
        Arguments.of(new ExceptionInInitializerError("init"), "ExceptionInInitializerError: init"),
        // Stands in for Scala's ???, which throws scala.NotImplementedError (an Error).
        Arguments.of(new Error("not implemented"), "Error: not implemented"));
  }

  @ParameterizedTest
  @MethodSource("nonFatalErrors")
  void convertsNonFatalErrorsIntoFailedResultsNamingTheErrorType(
      Error error, String expectedMessage) {
    JobExecutionEngine engine =
        engineWith(
            "erroring",
            request -> {
              throw error;
            });

    JobExecutionResult result =
        engine.execute(JobExecutionRequest.of("erroring", Lifecycle.DEV, Map.of()));

    assertEquals(JobStatus.FAILED, result.status());
    assertTrue(result.message().startsWith(error.getClass().getSimpleName()), result.message());
    assertEquals(expectedMessage, result.message());
  }

  @Test
  void errorWithoutMessageFallsBackToItsTypeName() {
    JobExecutionEngine engine =
        engineWith(
            "erroring",
            request -> {
              throw new AssertionError();
            });

    JobExecutionResult result =
        engine.execute(JobExecutionRequest.of("erroring", Lifecycle.DEV, Map.of()));

    assertEquals(JobStatus.FAILED, result.status());
    assertEquals("AssertionError", result.message());
  }

  @Test
  void propagatesVirtualMachineErrors() {
    JobExecutionEngine outOfMemory =
        engineWith(
            "oom",
            request -> {
              throw new OutOfMemoryError("test");
            });
    JobExecutionEngine stackOverflow =
        engineWith(
            "deep",
            request -> {
              throw new StackOverflowError();
            });

    assertThrows(
        OutOfMemoryError.class,
        () -> outOfMemory.execute(JobExecutionRequest.of("oom", Lifecycle.DEV, Map.of())));
    assertThrows(
        StackOverflowError.class,
        () -> stackOverflow.execute(JobExecutionRequest.of("deep", Lifecycle.DEV, Map.of())));
  }

  @Test
  void unknownJobReturnsFailedResult() {
    JobExecutionEngine engine = new JobExecutionEngine(new JobRegistry());

    JobExecutionResult result =
        engine.execute(JobExecutionRequest.of("missing", Lifecycle.DEV, Map.of()));

    assertEquals(JobStatus.FAILED, result.status());
    assertTrue(result.message().contains("Unknown job name: missing"), result.message());
  }

  @Test
  void nullJobResultBecomesFailedResult() {
    JobExecutionEngine engine = engineWith("silent", request -> null);

    JobExecutionResult result =
        engine.execute(JobExecutionRequest.of("silent", Lifecycle.DEV, Map.of()));

    assertEquals(JobStatus.FAILED, result.status());
    assertEquals("Job returned null result.", result.message());
  }

  @Test
  void interruptedJobFailsAndRestoresInterruptFlag() {
    JobExecutionEngine engine =
        engineWith(
            "interrupted",
            request -> {
              throw JobExecutionEngineTest.<RuntimeException>sneakyThrow(
                  new InterruptedException("stop"));
            });

    JobExecutionResult result =
        engine.execute(JobExecutionRequest.of("interrupted", Lifecycle.DEV, Map.of()));
    // Reads and clears the flag so it cannot leak into later tests on this thread.
    boolean restored = Thread.interrupted();

    assertTrue(restored);
    assertEquals(JobStatus.FAILED, result.status());
    assertEquals("stop", result.message());
  }

  @Test
  void blankOrNullExceptionMessageFallsBackToQualifiedClassName() {
    JobExecutionEngine anonymous =
        engineWith(
            "anonymous",
            request -> {
              throw new RuntimeException() {};
            });
    JobExecutionEngine unnamed =
        engineWith(
            "unnamed",
            request -> {
              throw new IllegalStateException();
            });
    JobExecutionEngine blank =
        engineWith(
            "blank",
            request -> {
              throw new IllegalArgumentException(" ");
            });

    JobExecutionResult anonymousResult =
        anonymous.execute(JobExecutionRequest.of("anonymous", Lifecycle.DEV, Map.of()));
    JobExecutionResult unnamedResult =
        unnamed.execute(JobExecutionRequest.of("unnamed", Lifecycle.DEV, Map.of()));
    JobExecutionResult blankResult =
        blank.execute(JobExecutionRequest.of("blank", Lifecycle.DEV, Map.of()));

    assertEquals(JobStatus.FAILED, anonymousResult.status());
    assertFalse(anonymousResult.message().isBlank());
    assertTrue(
        anonymousResult.message().startsWith(JobExecutionEngineTest.class.getName() + "$"),
        anonymousResult.message());
    assertEquals("java.lang.IllegalStateException", unnamedResult.message());
    assertEquals("java.lang.IllegalArgumentException", blankResult.message());
  }

  @Test
  void failedJobLogsThrowableWithCause() {
    DataCraftException failure = new DataCraftException("outer", new IOException("root cause"));
    JobExecutionEngine engine =
        engineWith(
            "broken",
            request -> {
              throw failure;
            });
    // Strong reference: JUL holds loggers weakly, and the handler must stay attached.
    Logger engineLogger = Logger.getLogger(JobExecutionEngine.class.getName());
    List<LogRecord> records = new CopyOnWriteArrayList<>();
    Handler capture = new CapturingHandler(records);
    engineLogger.addHandler(capture);
    JobExecutionResult result;
    try {
      result = engine.execute(JobExecutionRequest.of("broken", Lifecycle.DEV, Map.of()));
    } finally {
      engineLogger.removeHandler(capture);
    }

    assertEquals(JobStatus.FAILED, result.status());
    assertEquals("outer", result.message());
    assertEquals(1, records.size(), () -> "records: " + records);
    LogRecord logged = records.get(0);
    assertEquals(Level.SEVERE, logged.getLevel());
    assertEquals("Job broken failed", logged.getMessage());
    assertSame(failure, logged.getThrown());
    assertEquals("root cause", logged.getThrown().getCause().getMessage());
  }

  private static JobExecutionEngine engineWith(
      String name, Function<JobExecutionRequest, JobExecutionResult> body) {
    JobRegistry registry = new JobRegistry();
    registry.register(new LambdaJob(name, body));
    return new JobExecutionEngine(registry);
  }

  /** Throws a checked exception from code whose signature does not declare it, like Scala. */
  @SuppressWarnings("unchecked")
  private static <T extends Throwable> RuntimeException sneakyThrow(Throwable throwable) throws T {
    throw (T) throwable;
  }

  private record LambdaJob(String name, Function<JobExecutionRequest, JobExecutionResult> body)
      implements DataJob {
    @Override
    public String description() {
      return "test job";
    }

    @Override
    public JobExecutionResult run(JobExecutionRequest request) {
      return body.apply(request);
    }
  }

  private static final class CapturingHandler extends Handler {
    private final List<LogRecord> records;

    CapturingHandler(List<LogRecord> records) {
      this.records = records;
    }

    @Override
    public void publish(LogRecord logRecord) {
      records.add(logRecord);
    }

    @Override
    public void flush() {}

    @Override
    public void close() {}
  }
}
