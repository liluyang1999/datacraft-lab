package com.example.datacraft.desktop.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.datacraft.desktop.api.EngineModels.JobResult;
import com.example.datacraft.desktop.api.EngineModels.JobStatus;
import com.example.datacraft.desktop.api.HttpTransport;
import com.example.datacraft.desktop.api.StubServer;
import com.example.datacraft.desktop.app.AppPaths;
import com.example.datacraft.desktop.store.ActivityRepository;
import com.example.datacraft.desktop.store.ActivityRepository.Entry;
import com.example.datacraft.desktop.store.ActivityRepository.Level;
import com.example.datacraft.desktop.store.LocalStore;
import com.example.datacraft.desktop.store.Profile;
import com.example.datacraft.desktop.store.ProfileRepository;
import com.example.datacraft.desktop.store.RecentRunRepository;
import com.example.datacraft.desktop.store.SecretVault;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JobServiceTest {

  /** No secrets are stored in these tests, so the vault need not encrypt. */
  private static final SecretVault PLAIN =
      new SecretVault() {
        @Override
        public byte[] seal(byte[] plain) {
          return plain.clone();
        }

        @Override
        public byte[] open(byte[] sealed) {
          return sealed.clone();
        }

        @Override
        public String description() {
          return "plain";
        }
      };

  @TempDir Path home;
  private final FakeBackends backends = new FakeBackends();
  private final HttpTransport transport = new HttpTransport();
  private LocalStore store;
  private ActivityLog activity;
  private RecentRunRepository runs;
  private JobService jobs;
  private Session session;

  @BeforeEach
  void open() {
    store = LocalStore.open(new AppPaths(home).ensureCreated().database());
    activity = new ActivityLog(new ActivityRepository(store), Clock.systemUTC());
    runs = new RecentRunRepository(store);
    session = new Session(transport, Clock.systemUTC());
    // Cached runs belong to a stored profile.
    Profile profile =
        new ProfileRepository(store, PLAIN)
            .save(
                new Profile(
                    Profile.NEW,
                    "测试",
                    backends.engine.uri(),
                    backends.airflow.uri(),
                    "admin",
                    false),
                Profile.Secrets.NONE)
            .join();
    session.activate(
        profile, new Profile.Secrets(Optional.of(FakeBackends.TOKEN), Optional.of("pw")));
    jobs = new JobService(session, runs, activity, Clock.systemUTC(), () -> Duration.ofSeconds(10));
  }

  @AfterEach
  void close() {
    transport.close();
    backends.close();
    store.close();
  }

  private List<Entry> logged() {
    // The store is single-threaded, so this read follows every write queued before it.
    return activity.recent(50).join();
  }

  @Test
  void aSucceededRunIsCachedAndLoggedAndBlankValuesAreNotSent() {
    backends.engine.on(
        "POST /jobs/echo/runs",
        200,
        "{\"jobName\":\"echo\",\"status\":\"SUCCEEDED\",\"message\":\"你好 world\","
            + "\"metrics\":{\"durationMillis\":\"12\"}}");
    Map<String, String> params = new LinkedHashMap<>();
    params.put("message", "你好 world");
    params.put("unused", " ");
    var result = jobs.run("echo", params).result().join();
    assertEquals(JobStatus.SUCCEEDED, result.status());
    assertEquals(
        "message=%E4%BD%A0%E5%A5%BD%20world", backends.engine.requests().getLast().query());

    var cached = jobs.recent("echo", 10).join();
    assertEquals(1, cached.size());
    assertEquals(Map.of("message", "你好 world"), cached.getFirst().params());
    assertEquals(Duration.ofMillis(12), cached.getFirst().duration());
    assertEquals("SUCCEEDED", cached.getFirst().status());
    var entries = logged();
    assertEquals("作业 echo 运行成功", entries.getFirst().text());
    assertEquals(Level.SUCCESS, entries.getFirst().level());
    assertEquals("开始运行作业 echo", entries.get(1).text());
  }

  @Test
  void aFailedRunIsAResultNotAnException() {
    backends.engine.on(
        "POST /jobs/csv-profile/runs",
        500,
        "{\"jobName\":\"csv-profile\",\"status\":\"FAILED\",\"message\":\"Missing required parameter: input\","
            + "\"metrics\":{}}");
    var result = jobs.run("csv-profile", Map.of()).result().join();
    assertEquals(JobStatus.FAILED, result.status());
    jobs.recent(null, 10).join();
    assertEquals("作业 csv-profile 运行失败", logged().getFirst().text());
    assertEquals("Missing required parameter: input", logged().getFirst().detail());
  }

  @Test
  void aBusyEngineIsExplainedAndNothingIsCached() {
    backends.engine.on("POST /jobs/noop/runs", 503, "{\"error\":\"busy\"}");
    var handle = jobs.run("noop", Map.of());
    assertThrows(Exception.class, () -> handle.result().join());
    assertTrue(jobs.recent(null, 10).join().isEmpty());
    Entry entry = logged().getFirst();
    assertEquals("作业 noop 的运行请求失败", entry.text());
    assertEquals("引擎的运行数已满，没有开始运行。稍后再试。", entry.detail());
  }

  @Test
  void aRunThatOutlivesTheWaitLimitIsLoggedAsAWaitNotAFailure() throws InterruptedException {
    CountDownLatch release = new CountDownLatch(1);
    backends.engine.on(
        "POST /jobs/noop/runs",
        request -> {
          try {
            release.await(10, TimeUnit.SECONDS);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
          return StubServer.Answer.json(
              200,
              "{\"jobName\":\"noop\",\"status\":\"SUCCEEDED\",\"message\":\"noop\",\"metrics\":{}}");
        });
    JobService impatient =
        new JobService(session, runs, activity, Clock.systemUTC(), () -> Duration.ofMillis(300));
    try {
      var handle = impatient.run("noop", Map.of());
      assertThrows(Exception.class, () -> handle.result().join());
      Entry entry = logged().getFirst();
      assertEquals("等待作业 noop 超时", entry.text());
      assertEquals(Level.WARNING, entry.level());
      assertTrue(entry.detail().contains("仍在引擎上运行"), entry.detail());
      assertTrue(jobs.recent(null, 10).join().isEmpty());
    } finally {
      release.countDown();
    }
  }

  @Test
  void cancellationIsRecordedOnceBeforeResultObserversAreNotified() {
    var request = new CompletableFuture<JobResult>();
    var handle =
        jobs.track(
            session.current().orElseThrow().profile().id(),
            "noop",
            Map.of(),
            Instant.now(),
            request);
    AtomicReference<List<Entry>> observed = new AtomicReference<>();
    handle.result().whenComplete((result, failure) -> observed.set(logged()));

    handle.stopWaiting();
    handle.stopWaiting();
    assertTrue(handle.result().isCancelled());
    assertTrue(request.isCancelled(), "stopping the wait also aborts the request");
    assertEquals("已停止等待作业 noop", observed.get().getFirst().text());
    assertEquals(2, logged().size(), "one start and one stop, even when cancelled twice");
    assertTrue(jobs.recent(null, 10).join().isEmpty());
  }

  @Test
  void stoppingTheWaitAbortsTheRequestAndSaysTheJobGoesOn() throws InterruptedException {
    CountDownLatch received = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    backends.engine.on(
        "POST /jobs/noop/runs",
        request -> {
          received.countDown();
          try {
            release.await(10, TimeUnit.SECONDS);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
          return StubServer.Answer.json(
              200,
              "{\"jobName\":\"noop\",\"status\":\"SUCCEEDED\",\"message\":\"noop\",\"metrics\":{}}");
        });
    var handle = jobs.run("noop", Map.of());
    assertTrue(received.await(10, TimeUnit.SECONDS));
    handle.stopWaiting();
    Exception stopped = assertThrows(Exception.class, () -> handle.result().join());
    assertTrue(
        HttpTransport.cancelled(stopped), "a cancellation, not a backend failure: " + stopped);
    release.countDown();
    Entry entry = logged().getFirst();
    assertEquals("已停止等待作业 noop", entry.text());
    assertEquals(Level.WARNING, entry.level());
    assertTrue(jobs.recent(null, 10).join().isEmpty());
  }
}
