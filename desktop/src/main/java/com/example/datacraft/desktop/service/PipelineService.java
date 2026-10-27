package com.example.datacraft.desktop.service;

import com.example.datacraft.desktop.api.AirflowModels.Dag;
import com.example.datacraft.desktop.api.AirflowModels.DagRun;
import com.example.datacraft.desktop.api.AirflowModels.LogChunk;
import com.example.datacraft.desktop.api.AirflowModels.Task;
import com.example.datacraft.desktop.api.AirflowModels.TaskInstance;
import com.example.datacraft.desktop.api.ApiException;
import com.example.datacraft.desktop.store.ActivityRepository.Level;
import com.example.datacraft.desktop.store.ActivityRepository.Source;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Airflow for the active profile: DAGs, their runs and tasks, triggering, pausing and task logs.
 * Actions the user takes (trigger, pause, resume) are written to the activity log; reads are not.
 */
public final class PipelineService {

  private final Session session;
  private final ActivityLog activity;

  public PipelineService(Session session, ActivityLog activity) {
    this.session = session;
    this.activity = activity;
  }

  public CompletableFuture<List<Dag>> dags() {
    return call(client -> client.dags());
  }

  public CompletableFuture<List<DagRun>> runs(String dagId, int limit) {
    return call(client -> client.runs(dagId, limit));
  }

  public CompletableFuture<List<Task>> tasks(String dagId) {
    return call(client -> client.tasks(dagId));
  }

  public CompletableFuture<List<TaskInstance>> taskInstances(String dagId, String runId) {
    return call(client -> client.taskInstances(dagId, runId));
  }

  public CompletableFuture<LogChunk> log(
      String dagId, String runId, String taskId, int mapIndex, int tryNumber, String next) {
    return call(client -> client.log(dagId, runId, taskId, mapIndex, tryNumber, next));
  }

  public CompletableFuture<DagRun> trigger(String dagId, Map<String, Object> conf, String note) {
    return logged(
        call(client -> client.trigger(dagId, conf, note)),
        run -> "已触发 " + dagId,
        run -> run.runId(),
        "触发 " + dagId + " 失败");
  }

  public CompletableFuture<Void> setPaused(String dagId, boolean paused) {
    String action = paused ? "暂停调度" : "恢复调度";
    return logged(
        call(client -> client.setPaused(dagId, paused)),
        ignored -> "已" + action + " " + dagId,
        ignored -> "",
        action + " " + dagId + " 失败");
  }

  private <T> CompletableFuture<T> call(
      Function<com.example.datacraft.desktop.api.AirflowClient, CompletableFuture<T>> request) {
    return session
        .current()
        .map(connection -> request.apply(connection.airflow()))
        .orElseGet(JobService::notConnected);
  }

  private <T> CompletableFuture<T> logged(
      CompletableFuture<T> action,
      Function<T, String> success,
      Function<T, String> detail,
      String failureText) {
    return action.whenComplete(
        (value, failure) -> {
          if (failure == null) {
            activity.record(
                Level.SUCCESS, Source.AIRFLOW, success.apply(value), detail.apply(value));
          } else {
            activity.record(Level.ERROR, Source.AIRFLOW, failureText, describe(failure));
          }
        });
  }

  /** The sentence a failed Airflow request is shown with. */
  public static String describe(Throwable failure) {
    return ApiException.of(failure)
        .map(api -> Messages.describe(ConnectionMonitor.AIRFLOW, api))
        .orElseGet(
            () ->
                failure.getCause() == null
                    ? failure.getMessage()
                    : failure.getCause().getMessage());
  }
}
