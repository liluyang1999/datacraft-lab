package com.example.datacraft.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.datacraft.engine.DataJob;
import com.example.datacraft.engine.JobCatalog;
import com.example.datacraft.engine.JobExecutionRequest;
import com.example.datacraft.engine.JobExecutionResult;
import com.example.datacraft.engine.JobRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

// Backstop so a server that never answers fails the test instead of hanging the build.
// Tests declare the HttpClient before the server so the server closes first, while the client's
// connections are idle: a client disconnecting during close() makes the JDK wait the full grace.
@Timeout(30)
class EngineHttpServerTest {

  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
  private static final String JSON = "application/json; charset=utf-8";

  @Test
  void rejectsInvalidRequestsWithoutDroppingTheConnection() throws Exception {
    JobRegistry registry = new JobRegistry();
    registry.register(new EchoJob());
    try (HttpClient client = HttpClient.newHttpClient();
        EngineHttpServer server =
            EngineHttpServer.start(EngineHttpServerConfig.localEphemeral(), registry)) {
      for (String path :
          new String[] {
            "/jobs/echo/runs?lifecycle=invalid",
            "/jobs//runs",
            "/jobs/runs",
            "/jobs/%20/runs",
            "/jobs/echo/extra/runs",
            "/jobs/echo%2Fextra/runs",
            "/jobs/echo/runs?message=%FF"
          }) {
        HttpResponse<String> response =
            client.send(
                HttpRequest.newBuilder(server.uri(path))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(400, response.statusCode(), path);
      }
      assertEquals(
          404,
          client
              .send(
                  HttpRequest.newBuilder(server.uri("/health/extra")).GET().build(),
                  HttpResponse.BodyHandlers.ofString())
              .statusCode());
      assertEquals(
          405,
          client
              .send(
                  HttpRequest.newBuilder(server.uri("/jobs/echo/runs")).GET().build(),
                  HttpResponse.BodyHandlers.ofString())
              .statusCode());
      assertEquals(
          404,
          client
              .send(
                  HttpRequest.newBuilder(server.uri("/jobs/missing/runs"))
                      .POST(HttpRequest.BodyPublishers.noBody())
                      .build(),
                  HttpResponse.BodyHandlers.ofString())
              .statusCode());
    }
  }

  @Test
  void decodesJobPathExactlyOnceAndKeepsPlusCharacters() throws Exception {
    JobRegistry registry = new JobRegistry();
    registry.register(
        new DataJob() {
          public String name() {
            return "literal+%20";
          }

          public String description() {
            return "path decoding regression";
          }

          public JobExecutionResult run(JobExecutionRequest request) {
            return JobExecutionResult.success(
                name(), "ok", request.startedAt(), request.startedAt());
          }
        });
    try (HttpClient client = HttpClient.newHttpClient();
        EngineHttpServer server =
            EngineHttpServer.start(EngineHttpServerConfig.localEphemeral(), registry)) {
      HttpResponse<String> response =
          client.send(
              HttpRequest.newBuilder(server.uri("/jobs/literal+%2520/runs"))
                  .POST(HttpRequest.BodyPublishers.noBody())
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
    }
  }

  @Test
  void decodesPlusAsASpaceInTheQueryOnly() throws Exception {
    JobRegistry registry = new JobRegistry();
    registry.register(new EchoJob());
    try (HttpClient client = HttpClient.newHttpClient();
        EngineHttpServer server =
            EngineHttpServer.start(EngineHttpServerConfig.localEphemeral(), registry)) {
      HttpResponse<String> response =
          send(client, post(server, "/jobs/echo/runs?message=a+b%2Bc%20d"));

      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("\"message\":\"a b+c d\""), response.body());
    }
  }

  @Test
  void checksMethodThenOriginThenRequestSyntaxThenJobName() throws Exception {
    JobRegistry registry = new JobRegistry();
    registry.register(new EchoJob());
    try (HttpClient client = HttpClient.newHttpClient();
        EngineHttpServer server =
            EngineHttpServer.start(EngineHttpServerConfig.localEphemeral(), registry)) {
      // Each request fails every later check as well, so the status names the first one.
      String everythingWrong = "/jobs/missing/runs?lifecycle=bogus";
      HttpResponse<String> wrongMethod =
          send(client, request(server, everythingWrong).header("Origin", "null").GET());
      HttpResponse<String> crossOrigin =
          send(
              client,
              request(server, everythingWrong)
                  .header("Origin", "null")
                  .POST(HttpRequest.BodyPublishers.noBody()));
      HttpResponse<String> malformed = send(client, post(server, everythingWrong));
      HttpResponse<String> unknownJob = send(client, post(server, "/jobs/missing/runs"));

      assertEquals(405, wrongMethod.statusCode());
      assertEquals(403, crossOrigin.statusCode());
      assertEquals(400, malformed.statusCode());
      assertEquals(404, unknownJob.statusCode());
      assertEquals("{\"error\":\"unknown_job\"}", unknownJob.body());
    }
  }

  @Test
  void exposesHealthJobsAndRunEndpoints() throws Exception {
    JobRegistry registry = new JobRegistry();
    registry.register(new EchoJob());

    try (EngineHttpServer server =
        EngineHttpServer.start(EngineHttpServerConfig.localEphemeral(), registry)) {
      HttpClient client = HttpClient.newHttpClient();

      HttpResponse<String> health =
          client.send(
              HttpRequest.newBuilder(server.uri("/health")).GET().build(),
              HttpResponse.BodyHandlers.ofString());
      HttpResponse<String> jobs =
          client.send(
              HttpRequest.newBuilder(server.uri("/jobs")).GET().build(),
              HttpResponse.BodyHandlers.ofString());
      HttpResponse<String> run =
          client.send(
              HttpRequest.newBuilder(server.uri("/jobs/echo/runs?lifecycle=dev&message=hello"))
                  .POST(HttpRequest.BodyPublishers.noBody())
                  .build(),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(200, health.statusCode());
      assertTrue(health.body().contains("\"status\":\"UP\""));
      assertEquals(200, jobs.statusCode());
      assertTrue(jobs.body().contains("\"name\":\"echo\""));
      assertEquals(200, run.statusCode());
      assertTrue(run.body().contains("\"status\":\"SUCCEEDED\""));
      assertTrue(run.body().contains("\"message\":\"hello\""));
    }
  }

  @Test
  void returnsJsonNotFoundForUnknownRoutes() throws Exception {
    try (HttpClient client = HttpClient.newHttpClient();
        EngineHttpServer server =
            EngineHttpServer.start(EngineHttpServerConfig.localEphemeral(), new JobRegistry())) {
      for (String path : new String[] {"/", "/missing", "/api/v1/jobs", "/healthz"}) {
        HttpResponse<String> response = send(client, request(server, path).GET());

        assertEquals(404, response.statusCode(), path);
        assertEquals(Optional.of(JSON), response.headers().firstValue("Content-Type"), path);
        assertEquals("{\"error\":\"not_found\"}", response.body(), path);
      }
    }
  }

  @Test
  void returnsJobResultWith500WhenTheJobFails() throws Exception {
    JobRegistry registry = new JobRegistry();
    registry.register(
        new FunctionJob(
            "needs-input",
            request -> {
              throw new IllegalArgumentException("Missing required parameter: input");
            }));
    try (HttpClient client = HttpClient.newHttpClient();
        EngineHttpServer server =
            EngineHttpServer.start(EngineHttpServerConfig.localEphemeral(), registry)) {
      HttpResponse<String> failed = send(client, post(server, "/jobs/needs-input/runs"));
      HttpResponse<String> malformed =
          send(client, post(server, "/jobs/needs-input/runs?lifecycle=bogus"));

      assertEquals(500, failed.statusCode());
      assertEquals(Optional.of(JSON), failed.headers().firstValue("Content-Type"));
      assertTrue(failed.body().contains("\"jobName\":\"needs-input\""), failed.body());
      assertTrue(failed.body().contains("\"status\":\"FAILED\""), failed.body());
      assertTrue(
          failed.body().contains("\"message\":\"Missing required parameter: input\""),
          failed.body());
      assertTrue(failed.body().contains("\"metrics\":{"), failed.body());
      assertFalse(failed.body().contains("\"error\""), failed.body());
      assertEquals(400, malformed.statusCode());
      assertEquals("{\"error\":\"invalid_request\"}", malformed.body());
    }
  }

  @Test
  void returnsJsonFailureWhenAJobHitsAMissingRuntimeClass() throws Exception {
    JobRegistry registry = new JobRegistry();
    registry.register(
        new FunctionJob(
            "spark-version",
            request -> {
              throw new NoClassDefFoundError("org/apache/spark/sql/SparkSession");
            }));
    try (HttpClient client = HttpClient.newHttpClient();
        EngineHttpServer server =
            EngineHttpServer.start(EngineHttpServerConfig.localEphemeral(), registry)) {
      HttpResponse<String> response = send(client, post(server, "/jobs/spark-version/runs"));

      assertEquals(500, response.statusCode());
      assertTrue(response.body().contains("\"status\":\"FAILED\""), response.body());
      assertTrue(response.body().contains("SparkSession"), response.body());
    }
  }

  @Test
  void answersWhenAJobThrowsAVmError() throws Exception {
    JobRegistry registry = new JobRegistry();
    registry.register(
        new FunctionJob(
            "deep",
            request -> {
              throw new StackOverflowError();
            }));
    try (UncaughtErrors uncaught = new UncaughtErrors();
        HttpClient client = HttpClient.newHttpClient();
        EngineHttpServer server =
            EngineHttpServer.start(EngineHttpServerConfig.localEphemeral(), registry)) {
      HttpResponse<String> response = send(client, post(server, "/jobs/deep/runs"));
      HttpResponse<String> health = send(client, request(server, "/health").GET());

      assertEquals(500, response.statusCode());
      assertEquals(Optional.of(JSON), response.headers().firstValue("Content-Type"));
      assertEquals("{\"error\":\"internal_error\"}", response.body());
      assertEquals(200, health.statusCode());
      // After answering, the handler rethrows the Error so it is never silently swallowed.
      assertInstanceOf(StackOverflowError.class, uncaught.next());
    }
  }

  @Test
  void aCatalogFailureIsNeverReportedAsAnInvalidRequest() throws Exception {
    // Only parsing the request may answer 400; the same exception type from the catalog is a bug.
    IllegalArgumentException catalogFailure = new IllegalArgumentException("catalog bug");
    // Strong reference: JUL holds loggers weakly, and the handler must stay attached.
    Logger serverLogger = Logger.getLogger(EngineHttpServer.class.getName());
    List<LogRecord> records = new CopyOnWriteArrayList<>();
    Handler capture = new CapturingHandler(records, Level.SEVERE);
    serverLogger.addHandler(capture);
    try (HttpClient client = HttpClient.newHttpClient();
        EngineHttpServer server =
            EngineHttpServer.start(
                EngineHttpServerConfig.localEphemeral(), brokenCatalog(catalogFailure))) {
      HttpResponse<String> run = send(client, post(server, "/jobs/any/runs"));

      assertEquals(500, run.statusCode());
      assertEquals("{\"error\":\"internal_error\"}", run.body());
      assertEquals(1, records.size(), () -> "records: " + records);
      assertSame(catalogFailure, records.get(0).getThrown());
    } finally {
      serverLogger.removeHandler(capture);
    }
  }

  @Test
  void answersWithInternalErrorWhenTheCatalogFails() throws Exception {
    IllegalStateException catalogFailure = new IllegalStateException("catalog offline");
    JobCatalog brokenCatalog = brokenCatalog(catalogFailure);
    // Strong reference: JUL holds loggers weakly, and the handler must stay attached.
    Logger serverLogger = Logger.getLogger(EngineHttpServer.class.getName());
    List<LogRecord> records = new CopyOnWriteArrayList<>();
    Handler capture = new CapturingHandler(records, Level.SEVERE);
    serverLogger.addHandler(capture);
    try (UncaughtErrors uncaught = new UncaughtErrors();
        HttpClient client = HttpClient.newHttpClient();
        EngineHttpServer server =
            EngineHttpServer.start(EngineHttpServerConfig.localEphemeral(), brokenCatalog)) {
      HttpResponse<String> run = send(client, post(server, "/jobs/any/runs"));
      HttpResponse<String> jobs = send(client, request(server, "/jobs").GET());
      HttpResponse<String> health = send(client, request(server, "/health").GET());

      assertEquals(500, run.statusCode());
      assertEquals("{\"error\":\"internal_error\"}", run.body());
      assertEquals(500, jobs.statusCode());
      assertEquals("{\"error\":\"internal_error\"}", jobs.body());
      assertEquals(200, health.statusCode());
      assertEquals(1, records.size(), () -> "records: " + records);
      assertSame(catalogFailure, records.get(0).getThrown());
      assertEquals("broken catalog", uncaught.next().getMessage());
    } finally {
      serverLogger.removeHandler(capture);
    }
  }

  @Test
  void supportsHeadOnReadEndpointsWithoutJdkWarnings() throws Exception {
    // Strong reference: JUL holds loggers weakly, and the handler must stay attached.
    Logger jdkLogger = Logger.getLogger("com.sun.net.httpserver");
    List<LogRecord> warnings = new CopyOnWriteArrayList<>();
    Handler capture = new CapturingHandler(warnings, Level.WARNING);
    jdkLogger.addHandler(capture);
    JobRegistry registry = new JobRegistry();
    registry.register(new EchoJob());
    try (HttpClient client = HttpClient.newHttpClient();
        EngineHttpServer server =
            EngineHttpServer.start(EngineHttpServerConfig.localEphemeral(), registry)) {
      for (String path : new String[] {"/health", "/jobs"}) {
        HttpResponse<String> get = send(client, request(server, path).GET());
        HttpResponse<String> head = send(client, request(server, path).HEAD());
        HttpResponse<String> post = send(client, post(server, path));

        assertEquals(200, head.statusCode(), path);
        assertEquals("", head.body(), path);
        assertEquals(Optional.of(JSON), head.headers().firstValue("Content-Type"), path);
        assertEquals(
            Optional.of(String.valueOf(get.body().getBytes(StandardCharsets.UTF_8).length)),
            head.headers().firstValue("Content-Length"),
            path);
        assertEquals(405, post.statusCode(), path);
        assertEquals(Optional.of("GET, HEAD"), post.headers().firstValue("Allow"), path);
      }
      HttpResponse<String> headRun = send(client, request(server, "/jobs/echo/runs").HEAD());
      HttpResponse<String> headMissing = send(client, request(server, "/missing").HEAD());

      assertEquals(405, headRun.statusCode());
      assertEquals(Optional.of("POST"), headRun.headers().firstValue("Allow"));
      assertEquals(404, headMissing.statusCode());
      assertEquals("", headMissing.body());
      assertTrue(
          warnings.isEmpty(),
          () -> "JDK warnings: " + warnings.stream().map(LogRecord::getMessage).toList());
    } finally {
      jdkLogger.removeHandler(capture);
    }
  }

  @Test
  void rejectsRawNonAsciiQueryBytesInsteadOfDoubleDecoding() throws Exception {
    JobRegistry registry = new JobRegistry();
    registry.register(new EchoJob());
    try (HttpClient client = HttpClient.newHttpClient();
        EngineHttpServer server =
            EngineHttpServer.start(EngineHttpServerConfig.localEphemeral(), registry)) {
      // curl sends UTF-8 bytes unencoded; the JDK reads each request-line byte as one char.
      String response =
          rawPost(server, "/jobs/echo/runs?message=café".getBytes(StandardCharsets.UTF_8));
      HttpResponse<String> encoded =
          send(client, post(server, "/jobs/echo/runs?message=caf%C3%A9"));

      assertTrue(response.startsWith("HTTP/1.1 400"), response);
      assertTrue(response.contains("\"invalid_request\""), response);
      assertFalse(response.contains("cafÃ©"), response);
      assertEquals(200, encoded.statusCode());
      assertTrue(encoded.body().contains("\"message\":\"café\""), encoded.body());
    }
  }

  @Test
  void requestTargetsThatAreNotUrisGet400BeforeAnyHandlerRuns() throws Exception {
    AtomicInteger runs = new AtomicInteger();
    JobRegistry registry = new JobRegistry();
    registry.register(
        new FunctionJob(
            "count",
            request -> {
              runs.incrementAndGet();
              return JobExecutionResult.success(
                  request.jobName(), "counted", request.startedAt(), request.startedAt());
            }));
    try (EngineHttpServer server =
        EngineHttpServer.start(EngineHttpServerConfig.localEphemeral(), registry)) {
      // java.net.URI rejects both, so the JDK server answers itself; the body is its HTML, not
      // JSON.
      for (String target : new String[] {"/jobs/count/runs?x=%zz", "/jobs/count/runs?x=€"}) {
        String response = rawPost(server, target.getBytes(StandardCharsets.UTF_8));

        assertTrue(response.startsWith("HTTP/1.1 400"), target + ": " + response);
        assertFalse(response.contains("invalid_request"), target + ": " + response);
      }
      assertEquals(0, runs.get());
    }
  }

  @Test
  void aRequestTargetWithoutALeadingSlashGetsTheJdkNotFound() throws Exception {
    try (EngineHttpServer server =
        EngineHttpServer.start(EngineHttpServerConfig.localEphemeral(), new JobRegistry())) {
      // "*" matches no context, not even the catch-all at "/", so the JSON 404 cannot answer it.
      String response = rawRequest(server, "OPTIONS", "*".getBytes(StandardCharsets.US_ASCII));

      assertTrue(response.startsWith("HTTP/1.1 404"), response);
      assertFalse(response.contains("not_found"), response);
    }
  }

  @Test
  void refusesBrowserOriginatedRunRequests() throws Exception {
    AtomicInteger runs = new AtomicInteger();
    JobRegistry registry = new JobRegistry();
    registry.register(
        new FunctionJob(
            "count",
            request -> {
              runs.incrementAndGet();
              return JobExecutionResult.success(
                  request.jobName(), "counted", request.startedAt(), request.startedAt());
            }));
    try (HttpClient client = HttpClient.newHttpClient();
        EngineHttpServer server =
            EngineHttpServer.start(EngineHttpServerConfig.localEphemeral(), registry)) {
      for (String origin : new String[] {"https://evil.example", "null"}) {
        HttpResponse<String> refused =
            send(
                client,
                request(server, "/jobs/count/runs?message=x")
                    .header("Origin", origin)
                    .POST(HttpRequest.BodyPublishers.noBody()));

        assertEquals(403, refused.statusCode(), origin);
        assertEquals("{\"error\":\"cross_origin_forbidden\"}", refused.body(), origin);
        assertEquals(0, runs.get(), origin);
      }
      HttpResponse<String> accepted = send(client, post(server, "/jobs/count/runs?message=x"));

      assertEquals(200, accepted.statusCode());
      assertEquals(1, runs.get());
    }
  }

  @Test
  void closeDrainsInFlightRunBeforeStopping() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    JobRegistry registry = new JobRegistry();
    registry.register(
        new FunctionJob(
            "slow",
            request -> {
              started.countDown();
              awaitUninterruptibly(release);
              return JobExecutionResult.success(
                  request.jobName(), "done", request.startedAt(), Instant.now());
            }));
    EngineHttpServer server =
        EngineHttpServer.start(EngineHttpServerConfig.localEphemeral(), registry);
    Thread closer = new Thread(server::close, "engine-http-server-closer");
    HttpClient client = HttpClient.newHttpClient();
    try {
      URI root = server.uri("/");
      CompletableFuture<HttpResponse<String>> inFlight =
          client.sendAsync(
              post(server, "/jobs/slow/runs").build(), HttpResponse.BodyHandlers.ofString());
      assertTrue(started.await(5, TimeUnit.SECONDS), "job did not start");

      closer.start();

      assertTrue(
          connectionRefusedWithin(new InetSocketAddress(root.getHost(), root.getPort()), 5_000),
          "listener still accepts connections during close()");
      assertTrue(closer.isAlive(), "close() returned before the in-flight run finished");
      release.countDown();
      closer.join(5_000);
      assertFalse(closer.isAlive(), "close() did not return after the in-flight run finished");
      HttpResponse<String> response = inFlight.get(5, TimeUnit.SECONDS);
      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("\"status\":\"SUCCEEDED\""), response.body());
    } finally {
      // Release first: closing the client waits for its in-flight request.
      release.countDown();
      if (closer.getState() == Thread.State.NEW) {
        server.close();
      }
      closer.join(10_000);
      client.close();
    }
  }

  /** A catalog whose lookup throws {@code failure} and whose listing throws an AssertionError. */
  private static JobCatalog brokenCatalog(RuntimeException failure) {
    return new JobCatalog() {
      @Override
      public Optional<DataJob> find(String jobName) {
        throw failure;
      }

      @Override
      public List<String> jobNames() {
        return List.of();
      }

      @Override
      public Map<String, DataJob> jobs() {
        throw new AssertionError("broken catalog");
      }
    };
  }

  private static HttpRequest.Builder request(EngineHttpServer server, String path) {
    return HttpRequest.newBuilder(server.uri(path)).timeout(REQUEST_TIMEOUT);
  }

  private static HttpRequest.Builder post(EngineHttpServer server, String path) {
    return request(server, path).POST(HttpRequest.BodyPublishers.noBody());
  }

  private static HttpResponse<String> send(HttpClient client, HttpRequest.Builder request)
      throws IOException, InterruptedException {
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  /** Sends a POST whose request target is exactly these bytes and returns the whole response. */
  private static String rawPost(EngineHttpServer server, byte[] target) throws IOException {
    return rawRequest(server, "POST", target);
  }

  private static String rawRequest(EngineHttpServer server, String method, byte[] target)
      throws IOException {
    URI root = server.uri("/");
    try (Socket socket = new Socket(root.getHost(), root.getPort())) {
      socket.setSoTimeout((int) REQUEST_TIMEOUT.toMillis());
      OutputStream out = socket.getOutputStream();
      out.write((method + " ").getBytes(StandardCharsets.US_ASCII));
      out.write(target);
      out.write(
          " HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
              .getBytes(StandardCharsets.US_ASCII));
      out.flush();
      return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  /** Polls until a connect attempt is refused; Windows takes about 2 s to report a refusal. */
  private static boolean connectionRefusedWithin(InetSocketAddress address, long millis)
      throws IOException, InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
    while (System.nanoTime() < deadline) {
      try (Socket probe = new Socket()) {
        probe.connect(address, 3_000);
      } catch (ConnectException refused) {
        return true;
      } catch (SocketTimeoutException timedOut) {
        continue;
      } catch (SocketException reset) {
        // Linux can reset a connection that reached the backlog just as the listener closed
        // ("Connection reset by peer (connect failed)"); the next attempt is refused.
        continue;
      }
      // Connected: close() has not closed the listener yet.
      Thread.sleep(20);
    }
    return false;
  }

  private static void awaitUninterruptibly(CountDownLatch latch) {
    boolean interrupted = false;
    while (true) {
      try {
        latch.await();
        break;
      } catch (InterruptedException exception) {
        interrupted = true;
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private static final class EchoJob implements DataJob {
    @Override
    public String name() {
      return "echo";
    }

    @Override
    public String description() {
      return "returns message query parameter";
    }

    @Override
    public JobExecutionResult run(JobExecutionRequest request) {
      return JobExecutionResult.success(
          request.jobName(),
          request.parameters().getOrDefault("message", ""),
          request.startedAt(),
          request.startedAt());
    }
  }

  private record FunctionJob(String name, Function<JobExecutionRequest, JobExecutionResult> body)
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

  /**
   * Collects the Errors that handler threads rethrow after answering, so the tests assert them
   * instead of letting the default handler print them. Restores the previous handler on close.
   */
  private static final class UncaughtErrors implements AutoCloseable {
    private final Thread.UncaughtExceptionHandler previous =
        Thread.getDefaultUncaughtExceptionHandler();
    private final BlockingQueue<Throwable> errors = new LinkedBlockingQueue<>();

    UncaughtErrors() {
      Thread.setDefaultUncaughtExceptionHandler((thread, error) -> errors.add(error));
    }

    Throwable next() throws InterruptedException {
      Throwable error = errors.poll(10, TimeUnit.SECONDS);
      assertNotNull(error, "the handler thread did not rethrow the Error");
      return error;
    }

    @Override
    public void close() {
      Thread.setDefaultUncaughtExceptionHandler(previous);
    }
  }

  private static final class CapturingHandler extends Handler {
    private final List<LogRecord> records;
    private final Level threshold;

    CapturingHandler(List<LogRecord> records, Level threshold) {
      this.records = records;
      this.threshold = threshold;
    }

    @Override
    public void publish(LogRecord logRecord) {
      if (logRecord.getLevel().intValue() >= threshold.intValue()) {
        records.add(logRecord);
      }
    }

    @Override
    public void flush() {}

    @Override
    public void close() {}
  }
}
