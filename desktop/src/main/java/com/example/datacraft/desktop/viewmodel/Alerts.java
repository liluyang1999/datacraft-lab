package com.example.datacraft.desktop.viewmodel;

import com.example.datacraft.desktop.api.AirflowModels.ActiveTask;
import com.example.datacraft.desktop.api.AirflowModels.ImportError;
import com.example.datacraft.desktop.api.AirflowModels.Pool;
import com.example.datacraft.desktop.api.EngineModels.Status;
import com.example.datacraft.desktop.service.BackendStatus;
import com.example.datacraft.desktop.service.ConnectionMonitor;
import com.example.datacraft.desktop.service.MonitorService.Live;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The things that need a person's attention now, derived from the backends' status and the last
 * live data: a backend that is down or refuses the credentials, DAG files that fail to parse, a
 * full pool with tasks waiting, tasks waiting for a retry, and engine runs turned away. A pure
 * function of its inputs; failures come before warnings, then the order of the rules.
 */
public final class Alerts {

  /** How serious an alert is. */
  public enum Level {
    FAILED,
    WARNING
  }

  /** Where an alert's action leads. */
  public enum Target {
    SETTINGS,
    PIPELINE,
    MONITOR
  }

  /**
   * One alert: a stable key (so the row stays while its numbers change), a short title, the full
   * detail for the tooltip and the details dialog, and where its action leads.
   */
  public record Alert(
      String key,
      Level level,
      String title,
      String detail,
      Target target,
      Optional<String> dagId) {}

  private Alerts() {}

  /**
   * @param rejectedRecently runs the engine turned away within the last minutes (the model tracks
   *     increases of its counter), 0 when none
   */
  public static List<Alert> of(
      BackendStatus engine, BackendStatus airflow, Optional<Live> live, long rejectedRecently) {
    List<Alert> alerts = new ArrayList<>();
    backend(alerts, "engine", ConnectionMonitor.ENGINE, engine);
    backend(alerts, "airflow", ConnectionMonitor.AIRFLOW, airflow);
    live.ifPresent(
        data -> {
          data.importErrors().value().ifPresent(errors -> importErrors(alerts, errors));
          data.pools().value().ifPresent(pools -> pools(alerts, pools));
          data.tasks().value().ifPresent(tasks -> retries(alerts, tasks));
          data.engine().value().ifPresent(status -> rejected(alerts, status, rejectedRecently));
        });
    alerts.sort(Comparator.comparing(Alert::level));
    return List.copyOf(alerts);
  }

  private static void backend(List<Alert> alerts, String key, String name, BackendStatus status) {
    switch (status.state()) {
      case DOWN ->
          alerts.add(
              new Alert(
                  key + ":down",
                  Level.FAILED,
                  name + " 不可达",
                  status.summary(),
                  Target.SETTINGS,
                  Optional.empty()));
      case UNAUTHORIZED ->
          alerts.add(
              new Alert(
                  key + ":unauthorized",
                  Level.FAILED,
                  name + " 认证失败",
                  status.summary(),
                  Target.SETTINGS,
                  Optional.empty()));
      case DEGRADED ->
          alerts.add(
              new Alert(
                  key + ":degraded",
                  Level.WARNING,
                  name + " 状态异常",
                  status.summary(),
                  Target.MONITOR,
                  Optional.empty()));
      default -> {}
    }
  }

  private static void importErrors(List<Alert> alerts, List<ImportError> errors) {
    if (errors.isEmpty()) {
      return;
    }
    List<String> files = errors.stream().map(ImportError::filename).toList();
    alerts.add(
        new Alert(
            "import-errors",
            Level.FAILED,
            errors.size() + " 个 DAG 文件无法解析",
            String.join("\n", files),
            Target.MONITOR,
            Optional.empty()));
  }

  private static void pools(List<Alert> alerts, List<Pool> pools) {
    for (Pool pool : pools) {
      if (pool.exhausted()) {
        alerts.add(
            new Alert(
                "pool:" + pool.name(),
                Level.WARNING,
                "资源池 " + pool.name() + " 已满",
                "共 "
                    + pool.slots()
                    + " 个槽位，运行 "
                    + pool.running()
                    + "，排队 "
                    + (pool.queued() + pool.scheduled())
                    + "，空闲 0。",
                Target.MONITOR,
                Optional.empty()));
      }
    }
  }

  private static void retries(List<Alert> alerts, List<ActiveTask> tasks) {
    List<ActiveTask> retrying =
        tasks.stream().filter(task -> "up_for_retry".equals(task.state())).toList();
    if (retrying.isEmpty()) {
      return;
    }
    List<String> names =
        retrying.stream().map(task -> task.dagId() + " · " + task.taskId()).toList();
    String dagId = retrying.getFirst().dagId();
    boolean oneDag = retrying.stream().allMatch(task -> task.dagId().equals(dagId));
    alerts.add(
        new Alert(
            "retries",
            Level.WARNING,
            retrying.size() + " 个任务等待重试",
            String.join("\n", names),
            oneDag ? Target.PIPELINE : Target.MONITOR,
            oneDag ? Optional.of(dagId) : Optional.empty()));
  }

  private static void rejected(List<Alert> alerts, Status status, long fresh) {
    if (fresh > 0) {
      alerts.add(
          new Alert(
              "engine:rejected",
              Level.WARNING,
              "引擎拒绝了 " + fresh + " 次运行",
              "同时运行数已达上限 " + status.maxConcurrentRuns() + "，新的运行请求被拒绝。稍后再试，或提高引擎的并发上限。",
              Target.MONITOR,
              Optional.empty()));
    }
  }
}
