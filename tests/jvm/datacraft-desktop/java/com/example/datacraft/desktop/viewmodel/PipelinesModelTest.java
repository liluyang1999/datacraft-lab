package com.example.datacraft.desktop.viewmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.datacraft.desktop.api.AirflowModels.DagRun;
import com.example.datacraft.desktop.api.AirflowModels.TaskInstance;
import com.example.datacraft.desktop.api.RunTone;
import com.example.datacraft.desktop.api.StubServer;
import com.example.datacraft.desktop.app.AppContext;
import com.example.datacraft.desktop.app.AppPaths;
import com.example.datacraft.desktop.service.FakeBackends;
import com.example.datacraft.desktop.service.ManualTicker;
import com.example.datacraft.desktop.store.Profile;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PipelinesModelTest {

  private static final String ETL = "datacraft_spark_etl";

  @TempDir Path home;
  private final FakeBackends backends = new FakeBackends().withPipelines();
  private final ManualTicker ticker = new ManualTicker();
  private final UiQueue ui = new UiQueue();
  private AppContext context;
  private PipelinesModel model;

  @BeforeEach
  void open() throws Exception {
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
        new PipelinesModel(
            context.pipelines(),
            context.catalog(),
            context.json(),
            context.session(),
            context.preferences(),
            ticker,
            ui);
    model.shown();
    ticker.advance(Duration.ZERO);
    ui.until("the DAGs are listed", () -> model.dags().size() == 3);
  }

  @AfterEach
  void close() {
    model.hidden();
    context.close();
    backends.close();
  }

  /** Selects the spark ETL DAG and waits for its runs; returns them newest first. */
  private List<DagRun> etlRuns() throws Exception {
    model.selectDag(ETL);
    ticker.advance(Duration.ZERO);
    ui.until("the ETL runs are listed", () -> model.runs().size() == 6);
    return List.copyOf(model.runs());
  }

  private static String runPath(DagRun run) {
    return "/api/v2/dags/"
        + ETL
        + "/dagRuns/"
        + com.example.datacraft.desktop.api.HttpTransport.encode(run.runId());
  }

  @Test
  void changingConnectionClosesTheOldLogAndClearsItsContinuation() throws Exception {
    etlRuns();
    ticker.advance(Duration.ZERO);
    ui.until("the tasks are listed", () -> model.tasks().size() == 2);
    model.openLog(model.tasks().getFirst());
    ui.until("the log is read", () -> !model.logLines().isEmpty());
    context.session().clear();
    ui.drain();
    assertTrue(model.logLines().isEmpty(), "the previous connection's log must disappear");
    assertTrue(model.logNext().get().isEmpty());
    assertTrue(!model.logLoading().get());
  }

  @Test
  void aDagListAlreadyQueuedForTheUiCannotUndoAPause() throws Exception {
    backends.airflow.on("PATCH /api/v2/dags/" + ETL, 200, "{}");
    // Let a poll finish, but hold its answer in the UI queue while the user changes the switch.
    ui.until("the initial graph is loaded", () -> !model.structure().isEmpty());
    model.selectDag(null); // only the DAG poller remains, so the queued work is unambiguous
    ticker.advance(Duration.ofSeconds(10));
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (ui.pending() == 0 && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertTrue(ui.pending() > 0, "a poll is waiting for the UI");
    model.setPaused(ETL, true).join();
    ui.drain();
    assertTrue(etlPaused(), "the completed but unapplied poll must not undo the pause");
  }

  @Test
  void aLogOpenedWhileAnotherIsLoadingIsStillRead() throws Exception {
    List<DagRun> runs = etlRuns();
    ticker.advance(Duration.ZERO);
    ui.until("the newest run's tasks are listed", () -> model.tasks().size() == 2);
    CountDownLatch release = new CountDownLatch(1);
    backends.airflow.on(
        "GET " + runPath(runs.getFirst()) + "/taskInstances/csv_to_parquet/logs/1",
        request -> {
          try {
            release.await(10, TimeUnit.SECONDS);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
          return StubServer.Answer.json(
              200, "{\"content\":[\"slow\"],\"continuation_token\":null}");
        });
    TaskInstance first = model.tasks().getFirst();
    TaskInstance second = model.tasks().get(1);
    model.openLog(first); // its answer is slow
    model.closeLog();
    model.openLog(second);
    release.countDown();
    ui.until("the second task's log is read", () -> model.logLines().size() == 3);
    assertTrue(
        model.logLines().getFirst().contains("Task started: row_count"),
        model.logLines().toString());
  }

  @Test
  void tasksFetchedForTheRunSelectedBeforeAreNotShownForTheNextOne() throws Exception {
    List<DagRun> runs = etlRuns();
    DagRun running = runs.getFirst();
    DagRun failed = runs.get(1);
    assertEquals(RunTone.RUNNING, running.tone());
    assertEquals(RunTone.FAILED, failed.tone());
    // The running run's tasks arrive and wait for the UI...
    ticker.advance(Duration.ZERO);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (ui.pending() == 0 && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertEquals(1, ui.pending(), "the running run's tasks wait for the UI");
    // ...while the user, on the UI thread, selects the failed run.
    model.selectRun(failed.runId());
    ui.drain();
    assertTrue(
        model.tasks().stream().noneMatch(task -> task.tone() == RunTone.RUNNING),
        "the running run's tasks are not shown under the failed run: " + model.tasks());
    ticker.advance(Duration.ZERO);
    ui.until("the failed run's tasks are listed", () -> model.tasks().size() == 2);
    assertEquals(RunTone.FAILED, model.tasks().get(1).tone());
  }

  @Test
  void aDagsRunsCountAsReadOnlyOnceItsOwnAnswerArrived() throws Exception {
    // "No runs yet" must not show while the first answer is still on its way.
    model.selectDag(ETL);
    assertTrue(!model.runsLoaded().get(), "nothing is known about the ETL runs yet");
    ticker.advance(Duration.ZERO);
    ui.until("the ETL runs are read", () -> model.runsLoaded().get());
    assertEquals(6, model.runs().size());
    model.selectDag("datacraft_sftp_ingest");
    assertTrue(!model.runsLoaded().get(), "another DAG starts unknown again");
    ticker.advance(Duration.ZERO);
    ui.until("the SFTP runs are read", () -> model.runsLoaded().get());
    assertTrue(model.runs().isEmpty(), "and it has none");
  }

  @Test
  void theSelectedDagsShapeIsReadOnceAndAnAnswerForAnotherDagIsDropped() throws Exception {
    // The first DAG is selected when the list arrives; its shape follows.
    ui.until(
        "the engine-jobs graph is known",
        () ->
            model.structure().stream()
                .map(task -> task.taskId())
                .toList()
                .equals(List.of("echo", "noop", "spark_version")));
    CountDownLatch release = new CountDownLatch(1);
    backends.airflow.on(
        "GET /api/v2/dags/" + ETL + "/tasks",
        request -> {
          try {
            release.await(10, TimeUnit.SECONDS);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
          return StubServer.Answer.json(
              200,
              "{\"tasks\":[{\"task_id\":\"late\",\"task_display_name\":\"late\","
                  + "\"downstream_task_ids\":[],\"operator_name\":\"x\"}],\"total_entries\":1}");
        });
    model.selectDag(ETL); // its shape is slow
    assertTrue(model.structure().isEmpty(), "the previous DAG's shape is not kept");
    model.selectDag("datacraft_sftp_ingest");
    ui.until("the SFTP graph is known", () -> model.structure().size() == 3);
    release.countDown();
    Thread.sleep(300); // the ETL answer arrives late
    ui.drain();
    assertEquals(
        List.of("csv_to_parquet", "download", "row_count"),
        model.structure().stream().map(task -> task.taskId()).toList());
    // Back to engine jobs: its shape is known already and is not asked for again.
    model.selectDag("datacraft_engine_jobs");
    ui.drain();
    assertEquals(3, model.structure().size());
    assertEquals(
        1,
        backends.airflow.requests().stream()
            .filter(r -> r.path().equals("/api/v2/dags/datacraft_engine_jobs/tasks"))
            .count());
  }

  @Test
  void aPollThatStartedBeforeAPauseDoesNotFlipTheSwitchBack() throws Exception {
    AtomicBoolean paused = new AtomicBoolean(false);
    AtomicReference<CountDownLatch> hold = new AtomicReference<>();
    backends.airflow.on(
        "GET /api/v2/dags",
        request -> {
          // The answer is what Airflow knew when the request arrived.
          String body = dagList(paused.get());
          CountDownLatch latch = hold.get();
          if (latch != null) {
            try {
              latch.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
            }
          }
          return StubServer.Answer.json(200, body);
        });
    backends.airflow.on(
        "PATCH /api/v2/dags/" + ETL,
        request -> {
          paused.set(request.body().contains("true"));
          return StubServer.Answer.json(200, "{}");
        });
    // The first poll (in open()) scheduled the next one 10 s on; that one starts now and is held,
    // with the answer "not paused" that Airflow gave when it arrived.
    CountDownLatch release = new CountDownLatch(1);
    hold.set(release);
    ticker.advance(Duration.ofSeconds(10));
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (dagPolls() < 2 && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertEquals(2, dagPolls(), "the second poll is out and held");
    model.setPaused(ETL, true).join();
    hold.set(null);
    release.countDown();
    Thread.sleep(300); // the held poll's stale answer arrives
    ui.drain();
    assertTrue(etlPaused(), "the switch stays where the user put it");
    ticker.advance(Duration.ZERO);
    ui.until("a fresh poll confirms it", () -> etlPaused());
  }

  private long dagPolls() {
    return backends.airflow.requests().stream()
        .filter(r -> r.path().equals("/api/v2/dags"))
        .count();
  }

  private boolean etlPaused() {
    return model.dags().stream()
        .filter(row -> row.dagId().equals(ETL))
        .findFirst()
        .orElseThrow()
        .dag()
        .paused();
  }

  private static String dagList(boolean etlPaused) {
    return "{\"total_entries\":1,\"dags\":[{\"dag_id\":\""
        + ETL
        + "\",\"dag_display_name\":\""
        + ETL
        + "\",\"is_paused\":"
        + etlPaused
        + ",\"description\":null,\"timetable_summary\":null,\"tags\":[],"
        + "\"has_import_errors\":false,\"next_dagrun_run_after\":null}]}";
  }
}
