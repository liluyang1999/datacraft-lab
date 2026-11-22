package com.example.datacraft.desktop.viewmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.datacraft.desktop.api.AirflowModels.ActiveTask;
import com.example.datacraft.desktop.api.AirflowModels.ImportError;
import com.example.datacraft.desktop.api.AirflowModels.Pool;
import com.example.datacraft.desktop.api.EngineModels.Status;
import com.example.datacraft.desktop.service.BackendStatus;
import com.example.datacraft.desktop.service.BackendStatus.State;
import com.example.datacraft.desktop.service.MonitorService.Live;
import com.example.datacraft.desktop.service.MonitorService.Part;
import com.example.datacraft.desktop.viewmodel.Alerts.Alert;
import com.example.datacraft.desktop.viewmodel.Alerts.Level;
import com.example.datacraft.desktop.viewmodel.Alerts.Target;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AlertsTest {

  private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
  private static final BackendStatus UP = BackendStatus.up(Duration.ofMillis(4), "", NOW);

  private static BackendStatus state(State state) {
    return new BackendStatus(state, Optional.empty(), "原因", NOW);
  }

  private static Status engine(long rejected) {
    return new Status("1.2.3", NOW, Duration.ZERO, 3, 2, 5, 4, 0, rejected, List.of());
  }

  private static ActiveTask task(String dag, String id, String state) {
    return new ActiveTask(dag, "run", id, id, -1, state, 2, 3, Optional.empty(), Optional.empty());
  }

  private static Live live(
      List<ImportError> errors, List<Pool> pools, List<ActiveTask> tasks, Status status) {
    return new Live(
        new Part<>(Optional.of(status), "", false),
        new Part<>(Optional.of(tasks), "", false),
        new Part<>(Optional.of(pools), "", false),
        new Part<>(Optional.of(errors), "", false));
  }

  private static List<String> keys(List<Alert> alerts) {
    return alerts.stream().map(Alert::key).toList();
  }

  @Test
  void healthyBackendsWithCleanDataNeedNoAttention() {
    Live clean =
        live(List.of(), List.of(new Pool("default_pool", 8, 1, 0, 0, 0, 7)), List.of(), engine(0));
    assertEquals(List.of(), Alerts.of(UP, UP, Optional.of(clean), 0));
    assertEquals(List.of(), Alerts.of(BackendStatus.connecting(NOW), UP, Optional.empty(), 0));
  }

  @Test
  void backendStatesBecomeAlertsThatLeadToSettingsOrTheMonitor() {
    List<Alert> alerts =
        Alerts.of(state(State.DOWN), state(State.UNAUTHORIZED), Optional.empty(), 0);
    assertEquals(List.of("engine:down", "airflow:unauthorized"), keys(alerts));
    alerts.forEach(alert -> assertEquals(Target.SETTINGS, alert.target()));
    assertEquals("原因", alerts.getFirst().detail());

    Alert degraded = Alerts.of(UP, state(State.DEGRADED), Optional.empty(), 0).getFirst();
    assertEquals(Level.WARNING, degraded.level());
    assertEquals(Target.MONITOR, degraded.target());
  }

  @Test
  void dataProblemsFollowFailuresBeforeWarnings() {
    Live troubled =
        live(
            List.of(
                new ImportError(1, "/dags/a.py", Optional.empty(), "trace a"),
                new ImportError(2, "/dags/b.py", Optional.empty(), "trace b")),
            List.of(
                new Pool("default_pool", 2, 2, 1, 0, 0, 0), new Pool("spare", 4, 1, 0, 0, 0, 3)),
            List.of(task("etl", "load", "up_for_retry"), task("etl", "check", "running")),
            engine(3));

    List<Alert> alerts = Alerts.of(UP, state(State.DEGRADED), Optional.of(troubled), 2);

    assertEquals(
        List.of(
            "import-errors", "airflow:degraded", "pool:default_pool", "retries", "engine:rejected"),
        keys(alerts));
    assertEquals("2 个 DAG 文件无法解析", alerts.get(0).title());
    assertEquals("/dags/a.py\n/dags/b.py", alerts.get(0).detail());
    Alert retries = alerts.get(3);
    assertEquals("1 个任务等待重试", retries.title());
    assertEquals(Target.PIPELINE, retries.target());
    assertEquals(Optional.of("etl"), retries.dagId());
    assertEquals("引擎拒绝了 2 次运行", alerts.get(4).title());
  }

  @Test
  void retriesInSeveralDagsLeadToTheMonitorAndAnUnlimitedPoolIsNeverFull() {
    Live live =
        live(
            List.of(),
            List.of(new Pool("unlimited", -1, 9, 4, 0, 0, -1)),
            List.of(task("a", "x", "up_for_retry"), task("b", "y", "up_for_retry")),
            engine(0));
    List<Alert> alerts = Alerts.of(UP, UP, Optional.of(live), 0);
    assertEquals(List.of("retries"), keys(alerts));
    assertEquals(Target.MONITOR, alerts.getFirst().target());
    assertEquals(Optional.empty(), alerts.getFirst().dagId());
  }
}
