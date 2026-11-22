package com.example.datacraft.desktop.viewmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.datacraft.desktop.app.AppContext;
import com.example.datacraft.desktop.app.AppPaths;
import com.example.datacraft.desktop.service.FakeBackends;
import com.example.datacraft.desktop.service.ManualTicker;
import com.example.datacraft.desktop.store.Profile;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MonitorModelTest {

  @TempDir Path home;
  private final FakeBackends backends = new FakeBackends().withPipelines();
  private final ManualTicker ticker = new ManualTicker();
  private final UiQueue ui = new UiQueue();
  private AppContext context;
  private MonitorModel model;

  @BeforeEach
  void open() {
    context = AppContext.open(new AppPaths(home));
    context
        .profiles()
        .save(
            new Profile(
                Profile.NEW, "A", backends.engine.uri(), backends.airflow.uri(), "admin", true),
            new Profile.Secrets(
                Optional.of(FakeBackends.TOKEN), Optional.of(FakeBackends.PASSWORD)))
        .join();
    model =
        new MonitorModel(
            context.live(),
            new StatusModel(context.monitor(), ui),
            context.session(),
            context.preferences(),
            ticker,
            Clock.systemUTC(),
            ui);
  }

  @AfterEach
  void close() {
    model.windowShown(false);
    context.close();
    backends.close();
  }

  private static String status(String startedAt, int rejected) {
    return "{\"status\":\"UP\",\"service\":\"datacraft-api\",\"version\":\"1.4.2\","
        + "\"startedAt\":\""
        + startedAt
        + "\",\"uptimeMillis\":1000,\"jobs\":7,\"maxConcurrentRuns\":4,"
        + "\"runs\":{\"active\":0,\"started\":9,\"succeeded\":9,\"failed\":0,\"rejected\":"
        + rejected
        + "},\"activeRuns\":[]}";
  }

  @Test
  void aPollFillsEveryPartAndTheBackendVersions() throws Exception {
    model.shown();
    ticker.advance(Duration.ZERO);
    ui.until("the live data arrived", () -> model.loaded().get());

    assertEquals("1.4.2", model.engine().get().version());
    assertEquals(1, model.engine().get().active().size());
    assertEquals(List.of("csv_to_parquet"), model.tasks().stream().map(t -> t.taskId()).toList());
    assertEquals("default_pool", model.pools().getFirst().name());
    assertTrue(model.importErrors().isEmpty());
    assertEquals("", model.tasksProblem().get());
    assertEquals(0, model.alertCount().get());
    assertEquals("1.4.2", model.engineVersion().get());
    ui.until("Airflow's version arrived", () -> !model.airflowVersion().get().isEmpty());
    assertEquals("3.3.2", model.airflowVersion().get());
  }

  @Test
  void importErrorsRaiseAnAlertAndAMissingStatusRouteIsNotAProblem() throws Exception {
    backends.airflow.on(
        "GET /api/v2/importErrors",
        200,
        "{\"import_errors\":[{\"import_error_id\":3,\"timestamp\":\"2026-11-20T09:00:00Z\","
            + "\"filename\":\"/dags/broken.py\",\"stack_trace\":\"SyntaxError\"}],"
            + "\"total_entries\":1}");
    backends.engine.on("GET /status", 404, "{\"error\":\"not_found\"}");
    model.shown();
    ticker.advance(Duration.ZERO);
    ui.until("the live data arrived", () -> model.loaded().get());

    assertTrue(model.engineUnsupported().get());
    assertEquals("", model.engineProblem().get());
    assertNull(model.engine().get());
    assertEquals(1, model.importErrors().size());
    assertEquals(List.of("import-errors"), model.alerts().stream().map(Alerts.Alert::key).toList());
    assertEquals(1, model.alertCount().get());
  }

  @Test
  void onlyRunsRejectedWithinOneEngineProcessCount() throws Exception {
    backends.engine.on("GET /status", 200, status("2026-11-20T07:00:00Z", 2));
    model.shown();
    ticker.advance(Duration.ZERO);
    ui.until("the first status arrived", () -> model.engine().get() != null);
    assertEquals(0, model.alertCount().get(), "rejections before the app looked do not count");

    backends.engine.on("GET /status", 200, status("2026-11-20T07:00:00Z", 5));
    model.refresh();
    ticker.advance(Duration.ZERO);
    ui.until("the rejections are counted", () -> model.alertCount().get() == 1);
    assertEquals("引擎拒绝了 3 次运行", model.alerts().getFirst().title());

    // A restarted engine starts from zero: a lower count is a new process, not a negative change.
    backends.engine.on("GET /status", 200, status("2026-11-20T09:30:00Z", 1));
    model.refresh();
    ticker.advance(Duration.ZERO);
    ui.until(
        "the restarted engine is shown",
        () -> model.engine().get().startedAt().toString().startsWith("2026-11-20T09:30"));
    assertEquals(1, model.alertCount().get(), "the earlier rejections stay within their window");
  }

  @Test
  void itPollsFasterWhileShownAndNotAtAllWhileMinimised() throws Exception {
    model.start();
    ticker.advance(Duration.ZERO);
    ui.until("the background poll arrived", () -> model.loaded().get());
    assertTrue(model.polling());
    assertEquals(List.of(MonitorModel.BACKGROUND_INTERVAL), pollDelays());

    model.shown();
    ticker.advance(Duration.ZERO);
    ui.until("the shown poll is scheduled", () -> pollDelays().contains(MonitorModel.INTERVAL));

    model.windowShown(false);
    assertFalse(model.polling());
    model.windowShown(true);
    assertTrue(model.polling());
  }

  @Test
  void switchingTheConnectionClearsEverything() throws Exception {
    model.shown();
    ticker.advance(Duration.ZERO);
    ui.until("the live data arrived", () -> model.loaded().get());
    context.session().clear();
    ui.until("the model is cleared", () -> !model.connected().get());
    assertNull(model.engine().get());
    assertTrue(model.tasks().isEmpty());
    assertTrue(model.pools().isEmpty());
    assertEquals("", model.engineVersion().get());
    assertFalse(model.loaded().get());
  }

  /** The poller's own delays among the ticker's tasks (the connection monitor does not run). */
  private List<Duration> pollDelays() {
    return ticker.pending().stream()
        .filter(
            delay ->
                delay.equals(MonitorModel.INTERVAL)
                    || delay.equals(MonitorModel.BACKGROUND_INTERVAL))
        .toList();
  }
}
