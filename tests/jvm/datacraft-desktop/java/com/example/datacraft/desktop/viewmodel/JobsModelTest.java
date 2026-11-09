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
import com.example.datacraft.desktop.store.RecentRunRepository.RecentRun;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
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
  void changingJobsImmediatelyRemovesThePreviousJobsRecentRuns() throws Exception {
    Instant now = Instant.now();
    context
        .recentRuns()
        .add(
            new RecentRun(
                a.id(),
                "echo",
                Map.of(),
                "SUCCEEDED",
                "echo only",
                Map.of(),
                now,
                Duration.ofMillis(1)),
            now)
        .join();
    select("echo");
    ui.until("echo history loaded", () -> model.recent().size() == 1);
    CompletableFuture<Void> gate = new CompletableFuture<>();
    context
        .store()
        .submit(
            connection -> {
              gate.join();
              return null;
            });
    try {
      select("noop");
      assertTrue(
          model.recent().isEmpty(), "echo's history cannot appear under noop while it loads");
      assertTrue(model.recentLoading().get());
    } finally {
      gate.complete(null);
      context.store().submit(connection -> null).join();
    }
    ui.until("noop history loaded", () -> !model.recentLoading().get());
    assertTrue(model.recent().isEmpty());
  }

  @Test
  void aFailedHistoryReadIsVisibleAndCanBeRetriedWithoutSwitchingJobs() throws Exception {
    select("echo");
    ui.until("echo history loaded", () -> !model.recentLoading().get());
    renameRuns("recent_runs", "unavailable_runs");
    model.refresh();
    ui.until("history failure is shown", () -> !model.recentProblem().get().isEmpty());
    assertFalse(model.recentLoading().get());
    renameRuns("unavailable_runs", "recent_runs");
    Instant now = Instant.now();
    context
        .recentRuns()
        .add(
            new RecentRun(
                a.id(),
                "echo",
                Map.of(),
                "SUCCEEDED",
                "recovered",
                Map.of(),
                now,
                Duration.ofMillis(1)),
            now)
        .join();
    model.refresh();
    ui.until("history recovered", () -> !model.recentLoading().get());
    assertEquals("", model.recentProblem().get());
    assertEquals("recovered", model.recent().getFirst().message());
  }

  @Test
  void aHistoryFailureQueuedBeforeASelectionChangeCannotMarkTheNewJob() throws Exception {
    select("echo");
    ui.until("echo history loaded", () -> !model.recentLoading().get());
    renameRuns("recent_runs", "unavailable_runs");
    model.refresh();
    context.store().submit(connection -> null).join();
    renameRuns("unavailable_runs", "recent_runs");
    select("noop");
    context.store().submit(connection -> null).join();
    ui.drain();
    assertEquals("", model.recentProblem().get());
    assertFalse(model.recentLoading().get());
    assertTrue(model.recent().isEmpty());
  }

  private void renameRuns(String from, String to) {
    context
        .store()
        .transaction(
            connection -> {
              try (var statement = connection.createStatement()) {
                statement.execute("ALTER TABLE " + from + " RENAME TO " + to);
              }
              return null;
            })
        .join();
  }

  @Test
  void duplicateKeysCannotRunOrSaveAndSurviveSwitchingJobs() {
    select("echo");
    model.params().getFirst().value().set("原始内容");
    model.addParam();
    model.params().getLast().key().set(" message ");
    model.params().getLast().value().set("冲突内容");
    assertFalse(model.canRun().get());
    assertEquals("参数名称重复：message", model.missing().get());
    model.run();
    assertFalse(model.running().get());
    model.savePreset("invalid");
    assertEquals("参数名称重复：message", model.presetProblem().get());
    assertTrue(context.presets().list("echo").join().isEmpty());
    select("noop");
    select("echo");
    assertEquals(2, model.params().size());
    assertEquals("原始内容", model.params().getFirst().value().get());
    assertEquals(" message ", model.params().getLast().key().get());
    assertEquals("冲突内容", model.params().getLast().value().get());
    model.removeParam(model.params().getLast());
    assertTrue(model.canRun().get());
  }

  @Test
  void unnamedValuesAreKeptAsDraftsAndCannotBeSilentlyOmitted() {
    select("echo");
    model.addParam();
    model.params().getLast().value().set("尚未命名");
    assertFalse(model.canRun().get());
    assertEquals("请填写参数名称", model.missing().get());
    select("noop");
    select("echo");
    assertEquals("尚未命名", model.params().getLast().value().get());
    model.params().getLast().key().set("custom");
    assertTrue(model.canRun().get());
  }

  @Test
  void aPresetForAnotherJobCannotReplaceTheCurrentForm() {
    select("echo");
    model.params().getFirst().value().set("保留草稿");
    model.applyPreset(
        new com.example.datacraft.desktop.store.PresetRepository.Preset(
            "csv-profile", "other", Map.of("input", "other.csv")));
    assertEquals(1, model.params().size());
    assertEquals("message", model.params().getFirst().key().get());
    assertEquals("保留草稿", model.params().getFirst().value().get());
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

  @Test
  void aPresetFailureForAnEarlierSelectionDoesNotMarkTheCurrentJob() {
    select("echo");
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
    context.store().submit(connection -> null).join();
    context.store().submit(connection -> null).join();
    select("noop");
    ui.drain();
    assertEquals("", model.presetProblem().get());
  }

  @Test
  void aLateRecentRunListCannotCrossConnectionsWithTheSameJobSelected() {
    var job = model.jobs().stream().filter(j -> j.name().equals("echo")).findFirst().orElseThrow();
    Instant now = Instant.now();
    context
        .recentRuns()
        .add(
            new RecentRun(
                a.id(),
                "echo",
                Map.of(),
                "SUCCEEDED",
                "A only",
                Map.of(),
                now,
                Duration.ofMillis(1)),
            now)
        .join();
    Profile b =
        context
            .profiles()
            .save(
                new Profile(
                    Profile.NEW,
                    "B",
                    backends.engine.uri(),
                    backends.airflow.uri(),
                    "admin",
                    false),
                Profile.Secrets.NONE)
            .join();
    CompletableFuture<Void> first = new CompletableFuture<>();
    CompletableFuture<Void> second = new CompletableFuture<>();
    CompletableFuture<Void> reachedSecond = new CompletableFuture<>();
    context
        .store()
        .submit(
            connection -> {
              first.join();
              return null;
            });
    try {
      model.select(job); // A's reads wait behind the first gate
      context
          .store()
          .submit(
              connection -> {
                reachedSecond.complete(null);
                second.join();
                return null;
              });
      context.profiles().activate(b).join();
      ui.drain();
      model.applyJobs(List.of(job)); // B selects the same job while its reads are still blocked
      first.complete(null);
      reachedSecond.join();
      ui.drain();
      assertTrue(model.recent().isEmpty(), "A's cached runs must not appear under B");
    } finally {
      first.complete(null);
      second.complete(null);
      context.store().submit(connection -> null).join();
    }
  }
}
