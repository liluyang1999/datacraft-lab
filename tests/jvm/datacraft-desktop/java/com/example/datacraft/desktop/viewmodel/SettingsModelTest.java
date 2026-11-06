package com.example.datacraft.desktop.viewmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.datacraft.desktop.api.StubServer;
import com.example.datacraft.desktop.app.AppContext;
import com.example.datacraft.desktop.app.AppPaths;
import com.example.datacraft.desktop.service.BackendStatus;
import com.example.datacraft.desktop.service.FakeBackends;
import com.example.datacraft.desktop.store.Profile;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.beans.property.StringProperty;
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
  void changingAnyConnectionInputClearsTheResultForItsPreviousValue() {
    for (StringProperty field : connectionInputs()) {
      var tested = model.test().join();
      ui.drain();
      assertNotNull(model.testResult().get());
      model.name().set("Renamed connection");
      model.remember().set(!model.remember().get());
      assertSame(
          tested, model.testResult().get(), "metadata does not change the tested connection");
      String before = field.get();
      try {
        field.set(before + "-edited");
        assertNull(model.testResult().get(), "changed connection inputs require a new test");
      } finally {
        field.set(before);
      }
    }
  }

  @Test
  void aQueuedTestResultCannotRestoreItsStatusAfterAnyConnectionInputWasEdited() {
    for (StringProperty field : connectionInputs()) {
      model.test().join(); // its completion waits in UiQueue, so editing wins deterministically
      assertTrue(model.busy().get());
      String before = field.get();
      field.set(before + "-edited");
      field.set(before); // returning to the old text is still a different editing revision
      ui.drain();
      assertNull(model.testResult().get(), "an earlier test cannot publish into an edited draft");
      assertFalse(model.busy().get(), "discarded results still finish their operation");
    }
  }

  @Test
  void anEarlierTestCannotReplaceTheResultOfALaterTestForTheSameInputs() throws Exception {
    model.engineToken().set(FakeBackends.TOKEN);
    AtomicInteger requests = new AtomicInteger();
    CompletableFuture<Void> entered = new CompletableFuture<>();
    CompletableFuture<Void> release = new CompletableFuture<>();
    backends.engine.on(
        "GET /health",
        request -> {
          if (requests.getAndIncrement() == 0) {
            entered.complete(null);
            release.join();
            return StubServer.Answer.json(500, "{\"error\":\"earlier check failed\"}");
          }
          return StubServer.Answer.json(200, "{\"status\":\"UP\",\"service\":\"datacraft-api\"}");
        });
    var earlier = model.test();
    try {
      entered.get(10, TimeUnit.SECONDS);
      var latest = model.test().join();
      ui.drain();
      assertEquals(BackendStatus.State.UP, latest.engine().state());
      assertSame(latest, model.testResult().get());
      assertTrue(model.busy().get(), "the earlier check still owns its operation");
      release.complete(null);
      earlier.join();
      ui.drain();
      assertSame(latest, model.testResult().get(), "completion order must not select the result");
      assertFalse(model.busy().get());
    } finally {
      release.complete(null);
      earlier.join();
      ui.drain();
    }
  }

  private List<StringProperty> connectionInputs() {
    return List.of(
        model.engineUrl(),
        model.engineToken(),
        model.airflowUrl(),
        model.airflowUser(),
        model.airflowPassword());
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

  @Test
  void delayedSecretsDoNotReplaceCredentialsTheUserHasAlreadyTyped() {
    model.edit(a);
    awaitStore();
    model.engineToken().set("new-token");
    model.airflowPassword().set("new-password");
    ui.drain();
    assertEquals("new-token", model.engineToken().get());
    assertEquals("new-password", model.airflowPassword().get());
  }

  @Test
  void aDecryptionFailureForAnEarlierFormCannotSetTheCurrentFormsError() {
    context
        .store()
        .submit(
            connection -> {
              try (var statement =
                  connection.prepareStatement(
                      "UPDATE profiles SET engine_token = ? WHERE id = ?")) {
                statement.setBytes(1, new byte[] {1, 2, 3});
                statement.setLong(2, a.id());
                statement.executeUpdate();
              }
              return null;
            })
        .join();
    model.edit(a);
    awaitStore();
    model.newProfile();
    ui.drain();
    assertEquals("", model.formError().get());
  }

  @Test
  void savingAnEarlierFormDoesNotReplaceTheProfileNowBeingEdited() {
    Profile b =
        context
            .profiles()
            .save(
                new Profile(
                    Profile.NEW, "B", backends.engine.uri(), backends.airflow.uri(), "admin", true),
                Profile.Secrets.NONE)
            .join();
    model.name().set("A updated");
    model.save().join();
    model.edit(b);
    awaitStore();
    ui.drain();
    assertEquals(b, model.editing().get());
    assertEquals("B", model.name().get());
    assertEquals("", model.engineToken().get());
  }

  private void awaitStore() {
    context.store().submit(connection -> null).join();
    context.store().submit(connection -> null).join();
  }

  @Test
  void anEarlierSaveAndActivateCannotOverrideALaterConnectionChoice() {
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
    CompletableFuture<Void> release = new CompletableFuture<>();
    var held =
        context
            .store()
            .submit(
                connection -> {
                  release.join();
                  return null;
                });
    try {
      var earlier = model.activate();
      context.profiles().activate(b).join();
      release.complete(null);
      earlier.join();
      assertEquals(b, context.session().current().orElseThrow().profile());
      assertEquals(Optional.of(b.id()), context.preferences().activeProfile());
    } finally {
      release.complete(null);
      held.join();
    }
  }

  @Test
  void aSlowRememberedConnectionCannotOverrideALaterInMemoryChoice() {
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
    CompletableFuture<Void> release = new CompletableFuture<>();
    var held =
        context
            .store()
            .submit(
                connection -> {
                  release.join();
                  return null;
                });
    try {
      var earlier = context.profiles().activate(a);
      context.profiles().activate(b).join();
      release.complete(null);
      earlier.join();
      assertEquals(b, context.session().current().orElseThrow().profile());
      assertEquals(Optional.of(b.id()), context.preferences().activeProfile());
    } finally {
      release.complete(null);
      held.join();
    }
  }

  @Test
  void deletingAnEarlierProfileKeepsTheCurrentDraft() {
    Profile b =
        context
            .profiles()
            .save(
                new Profile(
                    Profile.NEW, "B", backends.engine.uri(), backends.airflow.uri(), "admin", true),
                Profile.Secrets.NONE)
            .join();
    model.delete().join();
    model.edit(b);
    model.name().set("B draft");
    ui.drain();
    awaitStore();
    ui.drain();
    assertEquals(b, model.editing().get());
    assertEquals("B draft", model.name().get());
  }

  @Test
  void completingOneOperationDoesNotClearBusyForAnotherOperation() {
    CompletableFuture<Void> release = new CompletableFuture<>();
    backends.engine.on(
        "GET /health",
        request -> {
          release.join();
          return StubServer.Answer.json(200, "{\"status\":\"UP\",\"service\":\"datacraft-api\"}");
        });
    model.save().join(); // its completion is waiting in the UI queue
    var testing = model.test();
    try {
      ui.drain();
      assertTrue(model.busy().get(), "the connection test has not finished");
    } finally {
      release.complete(null);
      testing.join();
      ui.drain();
    }
    assertFalse(model.busy().get());
  }
}
