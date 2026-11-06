package com.example.datacraft.desktop.viewmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.datacraft.desktop.app.AppContext;
import com.example.datacraft.desktop.app.AppPaths;
import com.example.datacraft.desktop.service.FakeBackends;
import com.example.datacraft.desktop.service.ManualTicker;
import com.example.datacraft.desktop.store.ActivityRepository.Level;
import com.example.datacraft.desktop.store.ActivityRepository.Source;
import com.example.datacraft.desktop.store.Profile;
import com.example.datacraft.desktop.store.RecentRunRepository.RecentRun;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CacheSyncTest {
  @TempDir Path home;
  private final UiQueue ui = new UiQueue();

  @Test
  void settingsClearUpdatesAnAlreadyLoadedActivityPage() throws Exception {
    try (AppContext context = AppContext.open(new AppPaths(home))) {
      context.activity().record(Level.INFO, Source.APP, "old");
      ActivityModel activity = new ActivityModel(context.activity(), ui);
      activity.shown();
      ui.until("activity loaded", () -> !activity.visibleEntries().isEmpty());
      settings(context).clearCache().join();
      settled(context);
      assertTrue(activity.visibleEntries().isEmpty());
      activity.hidden();
      activity.shown();
      settled(context);
      assertTrue(activity.visibleEntries().isEmpty());
    }
  }

  @Test
  void clearingWhileTheInitialActivityAnswerIsQueuedCannotRestoreDeletedEntries() {
    try (AppContext context = AppContext.open(new AppPaths(home))) {
      context.activity().record(Level.INFO, Source.APP, "old");
      ActivityModel activity = new ActivityModel(context.activity(), ui);
      activity.shown();
      context.store().submit(connection -> null).join();
      context.activity().clear().join();
      settled(context);
      assertTrue(activity.visibleEntries().isEmpty());
    }
  }

  @Test
  void entriesRecordedAfterClearRemainVisibleAndStored() throws Exception {
    try (AppContext context = AppContext.open(new AppPaths(home))) {
      ActivityModel activity = new ActivityModel(context.activity(), ui);
      activity.shown();
      settled(context);
      context.activity().record(Level.INFO, Source.APP, "old");
      var cleared = context.activity().clear();
      context.activity().record(Level.INFO, Source.APP, "new");
      cleared.join();
      settled(context);
      assertEquals(
          List.of("new"), activity.visibleEntries().stream().map(entry -> entry.text()).toList());
      assertEquals(
          List.of("new"),
          context.activity().recent(10).join().stream().map(entry -> entry.text()).toList());
    }
  }

  @Test
  void settingsClearUpdatesJobsAndOverviewWithoutRemovingPipelineHistory() throws Exception {
    try (FakeBackends backends = new FakeBackends().withPipelines();
        AppContext context = AppContext.open(new AppPaths(home))) {
      Profile profile =
          context
              .profiles()
              .save(
                  new Profile(
                      Profile.NEW,
                      "A",
                      backends.engine.uri(),
                      backends.airflow.uri(),
                      "admin",
                      false),
                  new Profile.Secrets(
                      Optional.of(FakeBackends.TOKEN), Optional.of(FakeBackends.PASSWORD)))
              .join();
      Instant now = Instant.now();
      context
          .recentRuns()
          .add(
              new RecentRun(
                  profile.id(),
                  "echo",
                  Map.of(),
                  "SUCCESS",
                  "",
                  Map.of(),
                  now,
                  Duration.ofMillis(1)),
              now)
          .join();
      ManualTicker ticker = new ManualTicker();
      JobsModel jobs =
          new JobsModel(
              context.jobs(),
              context.catalog(),
              context.presets(),
              context.session(),
              context.preferences(),
              ticker,
              ui);
      OverviewModel overview =
          new OverviewModel(
              context.pipelines(),
              context.jobs(),
              context.session(),
              context.preferences(),
              ticker,
              Clock.systemUTC(),
              ui);
      try {
        jobs.shown();
        overview.shown();
        ticker.advance(Duration.ZERO);
        ui.until(
            "jobs and overview loaded", () -> !jobs.jobs().isEmpty() && overview.loaded().get());
        jobs.select(
            jobs.jobs().stream()
                .filter(job -> job.name().equals("echo"))
                .findFirst()
                .orElseThrow());
        ui.until("recent job loaded", () -> jobs.recent().size() == 1);
        assertTrue(overview.recent().stream().anyMatch(item -> !item.pipeline()));
        int pipelineRuns = overview.mix().get().total() - 1;
        settings(context).clearCache().join();
        settled(context);
        assertTrue(jobs.recent().isEmpty());
        assertFalse(overview.recent().stream().anyMatch(item -> !item.pipeline()));
        assertEquals(pipelineRuns, overview.mix().get().total());
        assertFalse(overview.dags().isEmpty());
      } finally {
        jobs.hidden();
        overview.hidden();
      }
    }
  }

  private SettingsModel settings(AppContext context) {
    return new SettingsModel(
        context.profiles(),
        context.session(),
        context.preferences(),
        context.activity(),
        context.recentRuns(),
        new AppPaths(home),
        context.vault().description(),
        ui);
  }

  private void settled(AppContext context) {
    context.store().submit(connection -> null).join();
    ui.drain();
    context.store().submit(connection -> null).join();
    ui.drain();
  }
}
