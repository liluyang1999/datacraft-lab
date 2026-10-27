package com.example.datacraft.desktop.viewmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.datacraft.desktop.app.AppContext;
import com.example.datacraft.desktop.app.AppPaths;
import com.example.datacraft.desktop.service.FakeBackends;
import com.example.datacraft.desktop.service.ManualTicker;
import com.example.datacraft.desktop.store.Profile;
import com.example.datacraft.desktop.viewmodel.OverviewModel.Mix;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
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
