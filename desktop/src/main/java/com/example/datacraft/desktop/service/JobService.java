package com.example.datacraft.desktop.service;

import com.example.datacraft.desktop.api.ApiError;
import com.example.datacraft.desktop.api.ApiException;
import com.example.datacraft.desktop.api.EngineModels.JobInfo;
import com.example.datacraft.desktop.api.EngineModels.JobResult;
import com.example.datacraft.desktop.api.EngineModels.JobStatus;
import com.example.datacraft.desktop.api.HttpTransport;
import com.example.datacraft.desktop.store.ActivityRepository.Level;
import com.example.datacraft.desktop.store.ActivityRepository.Source;
import com.example.datacraft.desktop.store.RecentRunRepository;
import com.example.datacraft.desktop.store.RecentRunRepository.RecentRun;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Lists and runs engine jobs for the active profile. Every finished run is cached in the local
 * store and written to the activity log; a run the user stopped waiting for is logged as such,
 * because the job goes on on the server.
 */
public final class JobService {

  /** A run in progress: its result, and a way to stop waiting for it. */
  public record Handle(String jobName, Instant startedAt, CompletableFuture<JobResult> result) {
    /** Stops waiting; the engine keeps running the job. */
    public void stopWaiting() {
      result.cancel(true);
    }
  }

  private static final System.Logger LOG = System.getLogger(JobService.class.getName());

  private final Session session;
  private final RecentRunRepository runs;
  private final ActivityLog activity;
  private final Clock clock;
  private final Supplier<Duration> runTimeout;

  public JobService(
      Session session,
      RecentRunRepository runs,
      ActivityLog activity,
      Clock clock,
      Supplier<Duration> runTimeout) {
    this.session = session;
    this.runs = runs;
    this.activity = activity;
    this.clock = clock;
    this.runTimeout = runTimeout;
  }

  public CompletableFuture<List<JobInfo>> jobs() {
    return session
        .current()
        .map(connection -> connection.engine().jobs())
        .orElseGet(JobService::notConnected);
  }

  /** Starts a run; blank values are left out so that the job's own defaults apply. */
  public Handle run(String jobName, Map<String, String> params) {
    Instant startedAt = clock.instant();
    Map<String, String> sent = new LinkedHashMap<>();
    params.forEach(
        (key, value) -> {
          if (!key.isBlank() && value != null && !value.isBlank()) {
            sent.put(key.strip(), value);
          }
        });
    var connection = session.current();
    if (connection.isEmpty()) {
      return new Handle(jobName, startedAt, notConnected());
    }
    long profileId = connection.get().profile().id();
    activity.record(Level.INFO, Source.ENGINE, "开始运行作业 " + jobName);
    CompletableFuture<JobResult> request =
        connection.get().engine().run(jobName, sent, runTimeout.get());
    // The handle completes after the run is recorded, and cancelling it aborts the request.
    CompletableFuture<JobResult> recorded =
        request.whenComplete(
            (value, failure) -> finished(profileId, jobName, sent, startedAt, value, failure));
    return new Handle(jobName, startedAt, HttpTransport.linked(request, recorded));
  }

  /** The cached runs of the active profile, optionally of one job only, newest first. */
  public CompletableFuture<List<RecentRun>> recent(String jobName, int limit) {
    return session
        .current()
        .map(connection -> runs.list(connection.profile().id(), jobName, limit))
        .orElseGet(() -> CompletableFuture.completedFuture(List.of()));
  }

  private void finished(
      long profileId,
      String jobName,
      Map<String, String> params,
      Instant startedAt,
      JobResult result,
      Throwable failure) {
    Duration elapsed = Duration.between(startedAt, clock.instant());
    if (failure == null) {
      Duration duration = result.duration().isZero() ? elapsed : result.duration();
      runs.add(
              new RecentRun(
                  profileId,
                  jobName,
                  params,
                  result.status().name(),
                  result.message(),
                  result.metrics(),
                  startedAt,
                  duration),
              clock.instant())
          .exceptionally(
              cacheFailure -> {
                LOG.log(
                    System.Logger.Level.WARNING,
                    "A run of " + jobName + " was not cached",
                    cacheFailure);
                return null;
              });
      if (result.status() == JobStatus.SUCCEEDED) {
        activity.record(Level.SUCCESS, Source.ENGINE, "作业 " + jobName + " 运行成功", result.message());
      } else {
        activity.record(Level.ERROR, Source.ENGINE, "作业 " + jobName + " 运行失败", result.message());
      }
      return;
    }
    if (HttpTransport.cancelled(failure)) {
      activity.record(Level.WARNING, Source.ENGINE, "已停止等待作业 " + jobName, "作业仍在引擎上运行，结果不会记录在这里。");
      return;
    }
    if (ApiException.of(failure).map(api -> api.error() == ApiError.TIMED_OUT).orElse(false)) {
      activity.record(
          Level.WARNING,
          Source.ENGINE,
          "等待作业 " + jobName + " 超时",
          "超过了设置里的作业等待上限。作业仍在引擎上运行，结果不会记录在这里。");
      return;
    }
    String reason =
        ApiException.of(failure)
            .map(api -> Messages.describe(ConnectionMonitor.ENGINE, api))
            .orElse(String.valueOf(failure));
    activity.record(Level.ERROR, Source.ENGINE, "作业 " + jobName + " 的运行请求失败", reason);
  }

  static <T> CompletableFuture<T> notConnected() {
    return CompletableFuture.failedFuture(new IllegalStateException("还没有连接配置"));
  }
}
