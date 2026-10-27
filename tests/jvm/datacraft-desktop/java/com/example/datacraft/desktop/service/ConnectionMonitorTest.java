package com.example.datacraft.desktop.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.datacraft.desktop.api.HttpTransport;
import com.example.datacraft.desktop.api.StubServer;
import com.example.datacraft.desktop.app.AppPaths;
import com.example.datacraft.desktop.service.BackendStatus.State;
import com.example.datacraft.desktop.service.ConnectionMonitor.Snapshot;
import com.example.datacraft.desktop.store.ActivityRepository;
import com.example.datacraft.desktop.store.ActivityRepository.Entry;
import com.example.datacraft.desktop.store.LocalStore;
import com.example.datacraft.desktop.store.Profile;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConnectionMonitorTest {

  private static final Duration INTERVAL = Duration.ofSeconds(5);

  @TempDir Path home;
  private final FakeBackends backends = new FakeBackends();
  private final HttpTransport transport = new HttpTransport();
  private final ManualTicker ticker = new ManualTicker();
  private final BlockingQueue<Snapshot> snapshots = new LinkedBlockingQueue<>();
  private LocalStore store;
  private ActivityLog activity;
  private Session session;
  private ConnectionMonitor monitor;

  @BeforeEach
  void open() {
    store = LocalStore.open(new AppPaths(home).ensureCreated().database());
    activity = new ActivityLog(new ActivityRepository(store), Clock.systemUTC());
    session = new Session(transport, Clock.systemUTC());
    monitor = new ConnectionMonitor(session, ticker, Clock.systemUTC(), activity, () -> INTERVAL);
    monitor.subscribe(snapshots::add);
  }

  @AfterEach
  void close() {
    monitor.pause();
    transport.close();
    backends.close();
    store.close();
  }

  private void connect(URI engine, String token, URI airflow, String password) {
    Profile profile = new Profile(1, "测试", engine, airflow, "admin", false);
    session.activate(
        profile, new Profile.Secrets(Optional.ofNullable(token), Optional.ofNullable(password)));
    ticker.advance(Duration.ZERO);
  }

  private Snapshot await(Predicate<Snapshot> condition) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      Snapshot snapshot = snapshots.poll(100, TimeUnit.MILLISECONDS);
      if (snapshot != null && condition.test(snapshot)) {
        return snapshot;
      }
    }
    throw new AssertionError("no snapshot matched; the last is " + monitor.snapshot());
  }

  private List<String> activityTexts() {
    return activity.recent(50).join().stream().map(Entry::text).toList();
  }

  @Test
  void bothBackendsUpWithWorkingCredentials() throws InterruptedException {
    connect(backends.engine.uri(), FakeBackends.TOKEN, backends.airflow.uri(), "pw");
    Snapshot up = await(s -> s.engine().state() == State.UP && s.airflow().state() == State.UP);
    assertEquals("作业 2 个", up.engine().summary());
    assertTrue(up.airflow().summary().startsWith("调度器心跳 "), up.airflow().summary());
    assertTrue(up.engine().latency().isPresent());
    assertEquals(1, up.engineLatency().size());
    assertTrue(activityTexts().contains("引擎 API 已连接"));
    assertTrue(activityTexts().contains("Airflow 已连接"));
  }

  @Test
  void aWrongTokenShowsAsUnauthorizedNotAsUp() throws InterruptedException {
    connect(backends.engine.uri(), "w".repeat(40), backends.airflow.uri(), "wrong");
    Snapshot snapshot =
        await(
            s ->
                s.engine().state() == State.UNAUTHORIZED
                    && s.airflow().state() == State.UNAUTHORIZED);
    assertEquals("引擎令牌不正确或缺失：在设置里更新令牌。", snapshot.engine().summary());
    assertEquals("Airflow 用户名或口令不正确：在设置里检查连接。", snapshot.airflow().summary());
    assertTrue(activityTexts().contains("引擎 API 认证失败"));
  }

  @Test
  void anUnhealthySchedulerIsDegraded() throws InterruptedException {
    backends.schedulerDown();
    connect(backends.engine.uri(), FakeBackends.TOKEN, backends.airflow.uri(), "pw");
    Snapshot snapshot = await(s -> s.airflow().state() == State.DEGRADED);
    assertEquals("调度器异常", snapshot.airflow().summary());
  }

  @Test
  void anUnreachableEngineIsDownBacksOffAndRecovers() throws InterruptedException {
    StubServer gone = new StubServer();
    URI closed = gone.uri();
    gone.close();
    Profile profile = new Profile(1, "测试", closed, backends.airflow.uri(), "admin", false);
    session.activate(
        profile, new Profile.Secrets(Optional.of(FakeBackends.TOKEN), Optional.of("pw")));
    ticker.advance(Duration.ZERO);
    Snapshot down = await(s -> s.engine().state() == State.DOWN);
    assertTrue(
        down.engine().summary().startsWith("无法连接引擎 API（127.0.0.1:"), down.engine().summary());
    // Refused or timed out, depending on the platform's loopback behaviour.
    assertTrue(down.engine().summary().endsWith("）。确认服务已启动，或在设置里检查地址。"));
    await(s -> s.airflow().state() == State.UP);
    assertTrue(ticker.pending().contains(INTERVAL), "first retry after the base interval");

    ticker.advance(INTERVAL);
    await(s -> s.engine().state() == State.DOWN && s.engine() != down.engine());
    assertTrue(ticker.pending().contains(INTERVAL.multipliedBy(2)), "then twice the interval");
    assertTrue(activityTexts().contains("引擎 API 不可达"));

    session.activate(
        new Profile(1, "测试", backends.engine.uri(), backends.airflow.uri(), "admin", false),
        new Profile.Secrets(Optional.of(FakeBackends.TOKEN), Optional.of("pw")));
    ticker.advance(Duration.ZERO);
    await(s -> s.engine().state() == State.UP);
  }

  @Test
  void aCheckOfThePreviousProfileCannotVouchForTheNextOne() throws Exception {
    java.util.concurrent.CountDownLatch arrived = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch answered = new java.util.concurrent.CountDownLatch(1);
    backends.engine.on(
        "GET /jobs",
        request -> {
          if (!("Bearer " + FakeBackends.TOKEN).equals(request.header("Authorization"))) {
            return StubServer.Answer.json(401, "{\"error\":\"unauthorized\"}");
          }
          arrived.countDown();
          try {
            release.await(10, TimeUnit.SECONDS);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
          answered.countDown();
          return StubServer.Answer.json(200, "{\"jobs\":[]}");
        });
    // Profile A, with the right token: its first check waits inside GET /jobs.
    connect(backends.engine.uri(), FakeBackends.TOKEN, backends.airflow.uri(), "pw");
    assertTrue(arrived.await(10, TimeUnit.SECONDS));
    // Profile B, with a wrong token, becomes active while A's check is still out.
    session.activate(
        new Profile(2, "B", backends.engine.uri(), backends.airflow.uri(), "admin", false),
        new Profile.Secrets(Optional.of("w".repeat(40)), Optional.of("pw")));
    release.countDown();
    assertTrue(answered.await(10, TimeUnit.SECONDS));
    Thread.sleep(300); // let A's late answer reach the monitor
    ticker.advance(Duration.ZERO);
    await(s -> s.engine().state() == State.UNAUTHORIZED);
    ticker.advance(INTERVAL);
    Snapshot later =
        await(s -> s.engine().state() != State.CONNECTING && s.engine().checkedAt() != null);
    assertEquals(
        State.UNAUTHORIZED, later.engine().state(), "B never passes with A's verification");
  }

  @Test
  void withoutAProfileNothingIsConfigured() throws InterruptedException {
    session.clear();
    ticker.advance(Duration.ZERO);
    Snapshot snapshot =
        await(
            s ->
                s.engine().state() == State.NOT_CONFIGURED
                    && s.airflow().state() == State.NOT_CONFIGURED);
    assertEquals("还没有连接配置", snapshot.engine().summary());
  }
}
