package com.example.datacraft.desktop.service;

import com.example.datacraft.desktop.api.AirflowModels;
import com.example.datacraft.desktop.service.BackendStatus.State;
import com.example.datacraft.desktop.store.ActivityRepository.Level;
import com.example.datacraft.desktop.store.ActivityRepository.Source;
import com.example.datacraft.desktop.util.Formats;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Checks both backends on a schedule: health first, and once per profile the credentials too (the
 * engine's {@code /jobs} with the token, an Airflow sign-in), so a wrong token shows as such and
 * not as "up". Changes of state are written to the activity log.
 */
public final class ConnectionMonitor {

  public static final String ENGINE = "引擎 API";
  public static final String AIRFLOW = "Airflow";
  static final int HISTORY = 60;

  /** Both statuses and the latency history, newest last. */
  public record Snapshot(
      BackendStatus engine,
      BackendStatus airflow,
      List<Duration> engineLatency,
      List<Duration> airflowLatency) {}

  private final Session session;
  private final Clock clock;
  private final ActivityLog activity;
  private final List<Consumer<Snapshot>> listeners = new CopyOnWriteArrayList<>();
  private final Poller<Checked> enginePoller;
  private final Poller<Checked> airflowPoller;
  private final Deque<Duration> engineHistory = new ArrayDeque<>();
  private final Deque<Duration> airflowHistory = new ArrayDeque<>();

  private volatile BackendStatus engine;
  private volatile BackendStatus airflow;
  // The connection whose credentials were last confirmed; a switch of profile clears them, and a
  // check made for an earlier connection can never confirm the current one.
  private volatile Session.Connection tokenVerifiedFor;
  private volatile Session.Connection signInVerifiedFor;
  private volatile int jobCount;

  /** What one check found, and the connection it was made with. */
  private record Checked(
      Session.Connection connection, BackendStatus status, boolean verified, int jobCount) {}

  /** A check that failed, with the connection it was made with. */
  private static final class CheckFailed extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final transient Session.Connection connection;

    CheckFailed(Session.Connection connection, Throwable cause) {
      super(cause);
      this.connection = connection;
    }
  }

  public ConnectionMonitor(
      Session session,
      Ticker ticker,
      Clock clock,
      ActivityLog activity,
      Supplier<Duration> interval) {
    this.session = session;
    this.clock = clock;
    this.activity = activity;
    this.engine = BackendStatus.connecting(clock.instant());
    this.airflow = BackendStatus.connecting(clock.instant());
    this.enginePoller =
        new Poller<>(
            ticker,
            this::checkEngine,
            checked -> update(true, checked),
            failure -> failed(true, failure),
            interval);
    this.airflowPoller =
        new Poller<>(
            ticker,
            this::checkAirflow,
            checked -> update(false, checked),
            failure -> failed(false, failure),
            interval);
    session.subscribe(connection -> restart());
  }

  public void start() {
    enginePoller.start();
    airflowPoller.start();
  }

  /** Stops checking, e.g. while the window is minimized. */
  public void pause() {
    enginePoller.stop();
    airflowPoller.stop();
  }

  /** Checks both now. */
  public void refresh() {
    enginePoller.refresh();
    airflowPoller.refresh();
  }

  public Snapshot snapshot() {
    synchronized (engineHistory) {
      return new Snapshot(engine, airflow, List.copyOf(engineHistory), List.copyOf(airflowHistory));
    }
  }

  public void subscribe(Consumer<Snapshot> listener) {
    listeners.add(listener);
  }

  private void restart() {
    tokenVerifiedFor = null;
    signInVerifiedFor = null;
    synchronized (engineHistory) {
      engineHistory.clear();
      airflowHistory.clear();
    }
    engine = BackendStatus.connecting(clock.instant());
    airflow = BackendStatus.connecting(clock.instant());
    publish();
    enginePoller.restart();
    airflowPoller.restart();
  }

  private CompletableFuture<Checked> checkEngine() {
    Session.Connection connection = session.current().orElse(null);
    if (connection == null) {
      return CompletableFuture.completedFuture(new Checked(null, notConfigured(), false, 0));
    }
    boolean verified = tokenVerifiedFor == connection;
    var client = connection.engine();
    return client
        .health()
        .thenCompose(
            health -> {
              if (!health.up()) {
                return CompletableFuture.completedFuture(
                    new Checked(
                        connection,
                        new BackendStatus(
                            State.DEGRADED,
                            Optional.of(health.latency()),
                            "健康检查未返回 UP",
                            clock.instant()),
                        verified,
                        jobCount));
              }
              if (verified) {
                return CompletableFuture.completedFuture(
                    new Checked(
                        connection,
                        BackendStatus.up(
                            health.latency(), engineSummary(jobCount), clock.instant()),
                        true,
                        jobCount));
              }
              // The health check needs no token: the job list shows whether the token works.
              return client
                  .jobs()
                  .thenApply(
                      jobs ->
                          new Checked(
                              connection,
                              BackendStatus.up(
                                  health.latency(), engineSummary(jobs.size()), clock.instant()),
                              true,
                              jobs.size()));
            })
        .exceptionally(
            failure -> {
              throw new CheckFailed(connection, failure);
            });
  }

  private CompletableFuture<Checked> checkAirflow() {
    Session.Connection connection = session.current().orElse(null);
    if (connection == null) {
      return CompletableFuture.completedFuture(new Checked(null, notConfigured(), false, 0));
    }
    boolean verified = signInVerifiedFor == connection;
    var client = connection.airflow();
    return client
        .health()
        .thenCompose(
            health -> {
              // The health endpoint needs no sign-in: signing in shows whether the password works.
              CompletableFuture<Void> signIn =
                  verified ? CompletableFuture.completedFuture(null) : client.login();
              return signIn.thenApply(
                  ignored -> new Checked(connection, airflowStatus(health), true, 0));
            })
        .exceptionally(
            failure -> {
              throw new CheckFailed(connection, failure);
            });
  }

  private static String engineSummary(int jobs) {
    return "作业 " + jobs + " 个";
  }

  private BackendStatus airflowStatus(AirflowModels.Health health) {
    if (health.healthy()) {
      String summary =
          health
              .scheduler()
              .heartbeat()
              .map(beat -> "调度器心跳 " + Formats.ago(beat, clock.instant()))
              .orElse("正常");
      return BackendStatus.up(health.latency(), summary, clock.instant());
    }
    List<String> unhealthy = new ArrayList<>();
    if (!health.metadatabase().healthy()) {
      unhealthy.add("元数据库");
    }
    if (!health.scheduler().healthy()) {
      unhealthy.add("调度器");
    }
    if (!health.triggerer().healthy()) {
      unhealthy.add("触发器");
    }
    if (health.dagProcessor().map(component -> !component.healthy()).orElse(false)) {
      unhealthy.add("DAG 处理器");
    }
    return new BackendStatus(
        State.DEGRADED,
        Optional.of(health.latency()),
        String.join("、", unhealthy) + "异常",
        clock.instant());
  }

  private BackendStatus notConfigured() {
    return new BackendStatus(State.NOT_CONFIGURED, Optional.empty(), "还没有连接配置", clock.instant());
  }

  /** A failed check: its status replaces the current one if it was made for this connection. */
  private void failed(boolean isEngine, Throwable failure) {
    Session.Connection connection = null;
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof CheckFailed checkFailed) {
        connection = checkFailed.connection;
        break;
      }
    }
    BackendStatus status =
        BackendStatus.failed(isEngine ? ENGINE : AIRFLOW, failure, clock.instant());
    update(isEngine, new Checked(connection, status, false, 0));
  }

  private void update(boolean isEngine, Checked checked) {
    if (checked.connection() != session.current().orElse(null)) {
      return; // made for a connection that is no longer the active one
    }
    BackendStatus status = checked.status();
    BackendStatus before = isEngine ? engine : airflow;
    if (isEngine) {
      if (checked.verified()) {
        tokenVerifiedFor = checked.connection();
        jobCount = checked.jobCount();
      } else if (status.state() == State.UNAUTHORIZED) {
        tokenVerifiedFor = null; // a later success must check the token again
      }
    } else if (checked.verified()) {
      signInVerifiedFor = checked.connection();
    } else if (status.state() == State.UNAUTHORIZED) {
      signInVerifiedFor = null;
    }
    synchronized (engineHistory) {
      Deque<Duration> history = isEngine ? engineHistory : airflowHistory;
      status.latency().ifPresent(history::addLast);
      while (history.size() > HISTORY) {
        history.removeFirst();
      }
    }
    if (isEngine) {
      engine = status;
    } else {
      airflow = status;
    }
    logTransition(
        isEngine ? ENGINE : AIRFLOW, isEngine ? Source.ENGINE : Source.AIRFLOW, before, status);
    publish();
  }

  private void logTransition(
      String name, Source source, BackendStatus before, BackendStatus after) {
    if (before.state() == after.state()) {
      return;
    }
    switch (after.state()) {
      case UP -> {
        if (before.state() != State.CONNECTING) {
          activity.record(Level.SUCCESS, source, name + " 已恢复");
        } else {
          activity.record(Level.INFO, source, name + " 已连接");
        }
      }
      case DOWN -> activity.record(Level.WARNING, source, name + " 不可达", after.summary());
      case DEGRADED -> activity.record(Level.WARNING, source, name + " 状态异常", after.summary());
      case UNAUTHORIZED -> activity.record(Level.ERROR, source, name + " 认证失败", after.summary());
      default -> {}
    }
  }

  private void publish() {
    Snapshot snapshot = snapshot();
    listeners.forEach(listener -> listener.accept(snapshot));
  }
}
