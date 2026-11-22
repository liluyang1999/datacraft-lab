package com.example.datacraft.desktop.service;

import com.example.datacraft.desktop.api.AirflowClient;
import com.example.datacraft.desktop.api.AirflowModels.ActiveTask;
import com.example.datacraft.desktop.api.AirflowModels.ImportError;
import com.example.datacraft.desktop.api.AirflowModels.Pool;
import com.example.datacraft.desktop.api.ApiError;
import com.example.datacraft.desktop.api.ApiException;
import com.example.datacraft.desktop.api.EngineModels.Status;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * The live picture of both backends for the monitor: the engine's load and counters, and Airflow's
 * running tasks, pools and DAG files that fail to parse. Each part is fetched on its own, so one
 * failing request never hides what the others found. Reads only; nothing is logged.
 */
public final class MonitorService {

  /**
   * One part of the picture: its value, or why it is missing. {@code unsupported} marks a backend
   * that has no such route (an engine that predates {@code /status}), which is not a fault.
   */
  public record Part<T>(Optional<T> value, String problem, boolean unsupported) {

    static <T> Part<T> of(T value) {
      return new Part<>(Optional.of(value), "", false);
    }

    static <T> Part<T> failed(String backend, Throwable failure) {
      Optional<ApiException> api = ApiException.of(failure);
      boolean missingRoute = api.map(error -> error.error() == ApiError.NOT_FOUND).orElse(false);
      String problem =
          missingRoute
              ? backend + "不提供这项数据"
              : api.map(error -> Messages.describe(backend, error))
                  .orElseGet(() -> backend + "读取失败：" + rootMessage(failure));
      return new Part<>(Optional.empty(), problem, missingRoute);
    }

    public boolean present() {
      return value.isPresent();
    }
  }

  /** Everything one poll brought. */
  public record Live(
      Part<Status> engine,
      Part<List<ActiveTask>> tasks,
      Part<List<Pool>> pools,
      Part<List<ImportError>> importErrors) {}

  private final Session session;

  public MonitorService(Session session) {
    this.session = session;
  }

  /** Fetches the four parts at once; the future never fails, each part carries its own problem. */
  public CompletableFuture<Live> live() {
    Optional<Session.Connection> connection = session.current();
    if (connection.isEmpty()) {
      Part<Status> engine = new Part<>(Optional.empty(), "还没有连接配置", false);
      Part<List<ActiveTask>> tasks = new Part<>(Optional.empty(), "还没有连接配置", false);
      Part<List<Pool>> pools = new Part<>(Optional.empty(), "还没有连接配置", false);
      Part<List<ImportError>> errors = new Part<>(Optional.empty(), "还没有连接配置", false);
      return CompletableFuture.completedFuture(new Live(engine, tasks, pools, errors));
    }
    AirflowClient airflow = connection.get().airflow();
    CompletableFuture<Part<Status>> engine =
        part(ConnectionMonitor.ENGINE, connection.get().engine().status());
    CompletableFuture<Part<List<ActiveTask>>> tasks =
        part(ConnectionMonitor.AIRFLOW, airflow.activeTasks());
    CompletableFuture<Part<List<Pool>>> pools = part(ConnectionMonitor.AIRFLOW, airflow.pools());
    CompletableFuture<Part<List<ImportError>>> errors =
        part(ConnectionMonitor.AIRFLOW, airflow.importErrors());
    return CompletableFuture.allOf(engine, tasks, pools, errors)
        .thenApply(ignored -> new Live(engine.join(), tasks.join(), pools.join(), errors.join()));
  }

  /** Airflow's own version ({@code GET /api/v2/version}), for the settings' about section. */
  public CompletableFuture<String> airflowVersion() {
    return call(connection -> connection.airflow().version());
  }

  private <T> CompletableFuture<T> call(
      Function<Session.Connection, CompletableFuture<T>> request) {
    return session.current().map(request).orElseGet(JobService::notConnected);
  }

  private static <T> CompletableFuture<Part<T>> part(String backend, CompletableFuture<T> call) {
    return call.handle(
        (value, failure) -> failure == null ? Part.of(value) : Part.failed(backend, failure));
  }

  private static String rootMessage(Throwable failure) {
    Throwable root = failure;
    while (root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }
    return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
  }
}
