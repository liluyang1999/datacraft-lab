package com.example.datacraft.desktop.viewmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.example.datacraft.desktop.api.StubServer;
import com.example.datacraft.desktop.app.AppContext;
import com.example.datacraft.desktop.app.AppPaths;
import com.example.datacraft.desktop.service.FakeBackends;
import com.example.datacraft.desktop.store.Profile;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsModelTest {

  @TempDir Path home;
  private final FakeBackends backends = new FakeBackends();
  private final UiQueue ui = new UiQueue();
  private AppContext context;
  private SettingsModel model;
  private Profile a;

  @BeforeEach
  void open() throws Exception {
    context = AppContext.open(new AppPaths(home));
    a =
        context
            .profiles()
            .save(
                new Profile(
                    Profile.NEW, "A", backends.engine.uri(), backends.airflow.uri(), "admin", true),
                new Profile.Secrets(
                    Optional.of("w".repeat(40)), Optional.of(FakeBackends.PASSWORD)))
            .join();
    model =
        new SettingsModel(
            context.profiles(),
            context.session(),
            context.preferences(),
            context.activity(),
            context.recentRuns(),
            context.paths(),
            "test",
            ui);
    model.load();
    ui.until(
        "A is in the form",
        () -> "A".equals(model.name().get()) && !model.engineToken().get().isEmpty());
  }

  @AfterEach
  void close() {
    context.close();
    backends.close();
  }

  @Test
  void usingAConnectionUsesWhatTheFormSaysNotWhatWasStored() throws Exception {
    model.engineToken().set(FakeBackends.TOKEN); // the right token, pasted but not saved
    model.activate().join();
    ui.drain();
    // The job list needs the token: it answers only if the session uses the pasted one.
    assertEquals(2, context.jobs().jobs().join().size());
    assertEquals(
        Optional.of(FakeBackends.TOKEN),
        context
            .profiles()
            .secrets(context.session().current().orElseThrow().profile())
            .join()
            .engineToken());
  }

  @Test
  void aTestResultArrivingAfterAnotherProfileWasOpenedIsNotShownForIt() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    StubServer slow = new StubServer();
    slow.on(
        "GET /health",
        request -> {
          try {
            release.await(10, TimeUnit.SECONDS);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
          return StubServer.Answer.json(200, "{\"status\":\"UP\",\"service\":\"datacraft-api\"}");
        });
    try {
      model.engineUrl().set(slow.uri().toString());
      var test = model.test();
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
                      true),
                  Profile.Secrets.NONE)
              .join();
      model.edit(b); // the user opens B while A's test is still out
      release.countDown();
      test.handle((result, failure) -> null).join();
      ui.drain();
      assertEquals("B", model.name().get());
      assertNull(model.testResult().get(), "A's result is not shown under B");
    } finally {
      release.countDown();
      slow.close();
    }
  }

  @Test
  void returningToSettingsKeepsANewConnectionBeingTypedIn() throws Exception {
    model.newProfile();
    model.name().set("远程测试");
    model.engineUrl().set("http://10.0.0.5:8088");
    model.engineToken().set("x".repeat(40));
    model.load(); // the settings view is shown again
    // The list is read on the store's thread, which queues the UI work as it completes it: after
    // two round trips the reload has reached the UI queue.
    context.store().submit(connection -> null).join();
    context.store().submit(connection -> null).join();
    ui.drain();
    assertEquals(1, model.profiles().size());
    assertEquals("远程测试", model.name().get());
    assertEquals("http://10.0.0.5:8088", model.engineUrl().get());
    assertEquals("x".repeat(40), model.engineToken().get());
  }
}
