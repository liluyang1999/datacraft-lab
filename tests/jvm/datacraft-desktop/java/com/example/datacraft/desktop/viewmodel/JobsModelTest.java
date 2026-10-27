package com.example.datacraft.desktop.viewmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.datacraft.desktop.api.StubServer;
import com.example.datacraft.desktop.app.AppContext;
import com.example.datacraft.desktop.app.AppPaths;
import com.example.datacraft.desktop.service.FakeBackends;
import com.example.datacraft.desktop.service.ManualTicker;
import com.example.datacraft.desktop.store.Profile;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JobsModelTest {

  @TempDir Path home;
  private final FakeBackends backends = new FakeBackends().withPipelines();
  private final ManualTicker ticker = new ManualTicker();
  private final UiQueue ui = new UiQueue();
  private AppContext context;
  private JobsModel model;
  private Profile a;

  @BeforeEach
  void open() throws Exception {
    context = AppContext.open(new AppPaths(home));
    a = save("A", backends.engine.uri(), FakeBackends.TOKEN);
    model =
        new JobsModel(
            context.jobs(),
            context.catalog(),
            context.presets(),
            context.session(),
            context.preferences(),
            ticker,
            ui);
    model.shown();
    ticker.advance(Duration.ZERO);
    ui.until("the jobs of A are listed", () -> model.jobs().size() == 7);
  }

  @AfterEach
  void close() {
    model.hidden();
    context.close();
    backends.close();
  }

  private Profile save(String name, URI engine, String token) throws Exception {
    Profile saved =
        context
            .profiles()
            .save(
                new Profile(Profile.NEW, name, engine, backends.airflow.uri(), "admin", true),
                new Profile.Secrets(Optional.of(token), Optional.of(FakeBackends.PASSWORD)))
            .join();
    context.profiles().activate(saved).join();
    return saved;
  }

  private void select(String job) {
    model.select(model.jobs().stream().filter(j -> j.name().equals(job)).findFirst().orElseThrow());
  }

  @Test
  void switchingToAProfileWhoseEngineIsDownLeavesNothingOfThePreviousOne() throws Exception {
    select("csv-profile");
    model.params().getFirst().value().set("/opt/datacraft/data/input.csv");
    model.run();
    ui.until("A's result is shown", () -> model.result().get() != null);

    StubServer gone = new StubServer();
    URI closed = gone.uri();
    gone.close();
    save("B", closed, FakeBackends.TOKEN);
    ui.drain();
    assertTrue(model.jobs().isEmpty(), "A's job list is gone");
    assertNull(model.selected().get());
    assertNull(model.result().get());
    assertTrue(model.params().isEmpty());
    assertFalse(model.canRun().get());

    ticker.advance(Duration.ZERO);
    ui.until("B's failure is reported", () -> !model.listProblem().get().isEmpty());
    assertTrue(model.listProblem().get().startsWith("无法连接引擎 API"), model.listProblem().get());
  }

  @Test
  void aPresetListReloadedForAnotherJobIsNotShown() throws Exception {
    select("echo");
    model.params().getFirst().value().set("hi");
    // Hold the store's only thread, so the answers arrive in the order that shows the problem:
    // noop's preset list first, then echo's list reloaded after the save.
    java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
    context
        .store()
        .submit(
            connection -> {
              try {
                gate.await(10, java.util.concurrent.TimeUnit.SECONDS);
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              }
              return null;
            });
    model.savePreset("greeting");
    select("noop"); // the user moves on before the saved preset list comes back
    gate.countDown();
    // The store runs work in order and completes each future on its own thread, which queues the
    // UI work at once: after two round trips, the reload of echo's list has reached the UI queue.
    context.store().submit(connection -> null).join();
    context.store().submit(connection -> null).join();
    ui.drain();
    assertEquals("noop", model.selected().get().name());
    assertTrue(model.presets().isEmpty(), "noop has no presets; echo's are not shown");
  }

  @Test
  void aPresetThatCannotBeSavedSaysSo() throws Exception {
    select("echo");
    // A write that fails in SQLite, as a damaged or locked database would make it.
    context
        .store()
        .transaction(
            connection -> {
              try (var statement = connection.createStatement()) {
                statement.execute("DROP TABLE job_presets");
              }
              return null;
            })
        .join();
    model.savePreset("greeting");
    ui.until("the failure is reported", () -> !model.presetProblem().get().isEmpty());
    assertEquals("预设没有保存：本机数据库出错，详情见日志。", model.presetProblem().get());
  }
}
