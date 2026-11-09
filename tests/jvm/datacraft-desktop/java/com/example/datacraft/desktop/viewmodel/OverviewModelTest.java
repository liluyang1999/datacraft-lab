package com.example.datacraft.desktop.viewmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.datacraft.desktop.app.AppContext;
import com.example.datacraft.desktop.app.AppPaths;
import com.example.datacraft.desktop.service.FakeBackends;
import com.example.datacraft.desktop.service.ManualTicker;
import com.example.datacraft.desktop.store.Profile;
import com.example.datacraft.desktop.store.RecentRunRepository.RecentRun;
import com.example.datacraft.desktop.viewmodel.OverviewModel.Mix;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OverviewModelTest {

  @TempDir Path home;
  private final FakeBackends backends = new FakeBackends().withPipelines();
  private final ManualTicker ticker = new ManualTicker();
  private final UiQueue ui = new UiQueue();
  private AppContext context;
  private OverviewModel model;

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
        new OverviewModel(
            context.pipelines(),
            context.jobs(),
            context.session(),
            context.preferences(),
            ticker,
            Clock.systemUTC(),
            ui);
  }

  @AfterEach
  void close() {
    model.hidden();
    context.close();
    backends.close();
  }

  @Test
  void aFailedLocalHistoryReadRetainsTheLastKnownRunsAndMix() throws Exception {
    long profileId = context.session().current().orElseThrow().profile().id();
    Instant now = Instant.now();
    context
        .recentRuns()
        .add(
            new RecentRun(
                profileId,
                "echo",
                Map.of(),
                "SUCCEEDED",
                "cached",
                Map.of(),
                now,
                Duration.ofMillis(1)),
            now)
        .join();
    model.shown();
    ticker.advance(Duration.ZERO);
    ui.until("overview with cached job loaded", () -> model.loaded().get());
    Mix known = model.mix().get();
    assertTrue(model.recent().stream().anyMatch(item -> !item.pipeline()));
    context
        .store()
        .transaction(
            connection -> {
              try (var statement = connection.createStatement()) {
                statement.execute("ALTER TABLE recent_runs RENAME TO unavailable_runs");
              }
              return null;
            })
        .join();
    long applied = model.applied().get();
    model.refresh();
    ticker.advance(Duration.ZERO);
    ui.until("failed history read was applied", () -> model.applied().get() > applied);
    assertEquals(known, model.mix().get(), "a read failure is not an empty history");
    assertTrue(model.recent().stream().anyMatch(item -> !item.pipeline()));
    assertFalse(model.recentProblem().get().isEmpty());
    assertEquals("", model.problem().get(), "Airflow stays healthy independently");
    context
        .store()
        .transaction(
            connection -> {
              try (var statement = connection.createStatement()) {
                statement.execute("ALTER TABLE unavailable_runs RENAME TO recent_runs");
                statement.execute("DELETE FROM recent_runs");
              }
              return null;
            })
        .join();
    long failedPoll = model.applied().get();
    model.refresh();
    ticker.advance(Duration.ZERO);
    ui.until(
        "history recovered with a genuinely empty result",
        () -> model.applied().get() > failedPoll);
    assertEquals("", model.recentProblem().get());
    assertFalse(model.recent().stream().anyMatch(item -> !item.pipeline()));
    assertEquals(known.success() - 1, model.mix().get().success());
  }

  @Test
  void clearingHistoryAlsoDiscardsTheFallbackUsedAfterAReadFailure() {
    Instant now = Instant.now();
    RecentRun cached =
        new RecentRun(1, "echo", Map.of(), "SUCCEEDED", "old", Map.of(), now, Duration.ofMillis(1));
    model.apply(
        new OverviewModel.Data(
            Optional.of(java.util.List.of()), "", Optional.of(java.util.List.of(cached))));
    assertEquals(1, model.mix().get().success());
    context.recentRuns().clear().join();
    ui.drain();
    model.apply(new OverviewModel.Data(Optional.of(java.util.List.of()), "", Optional.empty()));
    assertTrue(model.recent().isEmpty());
    assertEquals(0, model.mix().get().total());
    assertFalse(model.recentProblem().get().isEmpty());
  }

  @Test
  void theRunMixCountsEveryRunOnScreenByTone() throws Exception {
    assertEquals(new Mix(0, 0, 0, 0), model.mix().get(), "nothing before the first answer");
    model.shown();
    ticker.advance(Duration.ZERO);
    ui.until("the overview is loaded", () -> model.loaded().get());
    // engine jobs: 11 successes and 1 failure; spark ETL: 1 running, 2 failed, 3 successes.
    Mix mix = model.mix().get();
    assertEquals(new Mix(14, 3, 1, 0), mix);
    assertEquals(18, mix.total());
    assertEquals(14.0 / 17.0, mix.successShare(), 1e-9, "running runs are not finished yet");
    assertEquals(-1.0, new Mix(0, 0, 2, 1).successShare(), "nothing finished: no share");
  }
}
