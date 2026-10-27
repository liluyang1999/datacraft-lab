package com.example.datacraft.desktop.viewmodel;

import com.example.datacraft.desktop.api.AirflowModels.Dag;
import com.example.datacraft.desktop.api.AirflowModels.DagRun;
import com.example.datacraft.desktop.api.AirflowModels.LogChunk;
import com.example.datacraft.desktop.api.AirflowModels.TaskInstance;
import com.example.datacraft.desktop.app.Preferences;
import com.example.datacraft.desktop.service.JobCatalog;
import com.example.datacraft.desktop.service.PipelineService;
import com.example.datacraft.desktop.service.Poller;
import com.example.datacraft.desktop.service.Session;
import com.example.datacraft.desktop.service.Ticker;
import com.example.datacraft.desktop.util.Latest;
import com.example.datacraft.desktop.util.Lists;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/**
 * The pipelines view: the DAG table with ribbons and pause switches, the runs of the selected DAG
 * and the tasks of the selected run, polled faster while something is still happening, plus
 * triggering and task logs read in parts.
 */
public final class PipelinesModel implements Visibility {

  public static final int RIBBON = 20;
  static final int RUNS = 25;
  static final Duration DAGS = Duration.ofSeconds(10);
  static final Duration ACTIVE = Duration.ofSeconds(3);
  static final Duration IDLE = Duration.ofSeconds(15);

  /** About 2 MB of text: older lines are dropped from the top beyond it. */
  public static final int LOG_LIMIT_CHARS = 1024 * 1024;

  /** The runs fetched for one DAG: an answer for a DAG no longer selected is not shown. */
  record DagRuns(String dagId, List<DagRun> runs) {}

  /** The tasks fetched for one run: an answer for a run no longer selected is not shown. */
  record RunTasks(String runId, List<TaskInstance> tasks) {}

  /** A task log being read: which try, and the lines so far. */
  public record LogTarget(String dagId, String runId, String taskId, int mapIndex, int tryNumber) {}

  private final PipelineService service;
  private final JobCatalog catalog;
  private final ObjectMapper json;
  private final Executor ui;
  private final Poller<List<DagRow>> dagPoller;
  private final Poller<DagRuns> runPoller;
  private final Poller<RunTasks> taskPoller;
  private boolean visible;
  private int pausesInFlight;
  // Read by the pollers off the UI thread, written on it.
  private volatile String polledDag;
  private volatile String polledRun;
  private volatile boolean runsActive;

  private final ObservableList<DagRow> dags = FXCollections.observableArrayList();
  private final ObservableList<DagRun> runs = FXCollections.observableArrayList();
  private final ObservableList<TaskInstance> tasks = FXCollections.observableArrayList();
  private final StringProperty selectedDag = new SimpleStringProperty();
  private final StringProperty selectedRun = new SimpleStringProperty();
  private final StringProperty dagProblem = new SimpleStringProperty("");
  private final StringProperty detailProblem = new SimpleStringProperty("");
  private final BooleanProperty loaded = new SimpleBooleanProperty(false);
  private final BooleanProperty connected = new SimpleBooleanProperty(false);

  private final ObservableList<String> logLines = FXCollections.observableArrayList();
  private final StringProperty logNext = new SimpleStringProperty("");
  private final StringProperty logProblem = new SimpleStringProperty("");
  private final BooleanProperty logLoading = new SimpleBooleanProperty(false);
  private LogTarget logTarget;
  private LogTarget logLoadingFor;
  private int logChars;

  public PipelinesModel(
      PipelineService service,
      JobCatalog catalog,
      ObjectMapper json,
      Session session,
      Preferences preferences,
      Ticker ticker,
      Executor ui) {
    this.service = service;
    this.catalog = catalog;
    this.json = json;
    this.ui = ui;
    Latest<List<DagRow>> latestDags = new Latest<>(ui, this::applyDags);
    this.dagPoller =
        new Poller<>(
            ticker,
            () -> DagRow.fetch(service, RIBBON),
            latestDags::offer,
            failure -> ui.execute(() -> dagProblem.set(PipelineService.describe(failure))),
            () -> preferences.refresh().scale(DAGS));
    Latest<DagRuns> latestRuns = new Latest<>(ui, this::applyRuns);
    this.runPoller =
        new Poller<>(
            ticker,
            () -> {
              String dagId = polledDag;
              return dagId == null
                  ? CompletableFuture.completedFuture(new DagRuns(null, List.of()))
                  : service.runs(dagId, RUNS).thenApply(list -> new DagRuns(dagId, list));
            },
            latestRuns::offer,
            failure -> ui.execute(() -> detailProblem.set(PipelineService.describe(failure))),
            () -> preferences.refresh().scale(runsActive ? ACTIVE : IDLE));
    Latest<RunTasks> latestTasks = new Latest<>(ui, this::applyTasks);
    this.taskPoller =
        new Poller<>(
            ticker,
            () -> {
              String dagId = polledDag;
              String runId = polledRun;
              return dagId == null || runId == null
                  ? CompletableFuture.completedFuture(new RunTasks(null, List.of()))
                  : service
                      .taskInstances(dagId, runId)
                      .thenApply(list -> new RunTasks(runId, list));
            },
            latestTasks::offer,
            failure -> ui.execute(() -> detailProblem.set(PipelineService.describe(failure))),
            () -> preferences.refresh().scale(ACTIVE));
    connected.set(session.current().isPresent());
    session.subscribe(
        connection ->
            ui.execute(
                () -> {
                  connected.set(connection.isPresent());
                  dags.clear();
                  selectDag(null);
                  loaded.set(false);
                  if (visible) {
                    dagPoller.restart();
                  }
                }));
  }

  void applyDags(List<DagRow> fresh) {
    dagProblem.set("");
    Lists.reconcile(dags, fresh, DagRow::dagId);
    loaded.set(true);
    if (selectedDag.get() == null && !dags.isEmpty()) {
      selectDag(dags.getFirst().dagId());
    }
  }

  void applyRuns(DagRuns answer) {
    // The answer may have waited for the UI while the user selected another DAG.
    if (!java.util.Objects.equals(answer.dagId(), selectedDag.get())) {
      return;
    }
    List<DagRun> fresh = answer.runs();
    detailProblem.set("");
    Lists.reconcile(runs, fresh, DagRun::runId);
    runsActive = runs.stream().anyMatch(run -> run.tone().active());
    if (selectedRun.get() == null && !runs.isEmpty()) {
      selectRun(runs.getFirst().runId());
    }
    // A finished run that starts again (cleared tasks) needs its tasks polled again.
    selected()
        .ifPresent(
            run -> {
              if (visible && run.tone().active() && !taskPoller.running()) {
                taskPoller.start();
              }
            });
  }

  void applyTasks(RunTasks answer) {
    // The answer may have waited for the UI while the user selected another run.
    if (!java.util.Objects.equals(answer.runId(), selectedRun.get())) {
      return;
    }
    List<TaskInstance> fresh = answer.tasks();
    Lists.reconcile(tasks, fresh, task -> task.taskId() + "#" + task.mapIndex());
    boolean finished = selected().map(run -> !run.tone().active()).orElse(true);
    boolean tasksSettled = fresh.stream().noneMatch(task -> task.tone().active());
    if (finished && tasksSettled) {
      taskPoller.stop();
    }
  }

  public Optional<DagRun> selected() {
    String runId = selectedRun.get();
    return runs.stream().filter(run -> run.runId().equals(runId)).findFirst();
  }

  public void selectDag(String dagId) {
    if (dagId != null && dagId.equals(selectedDag.get())) {
      return;
    }
    selectedDag.set(dagId);
    polledDag = dagId;
    selectedRun.set(null);
    polledRun = null;
    runs.clear();
    runsActive = false;
    tasks.clear();
    detailProblem.set("");
    if (visible && dagId != null) {
      runPoller.restart();
    } else {
      runPoller.stop();
    }
    taskPoller.stop();
  }

  public void selectRun(String runId) {
    if (runId != null && runId.equals(selectedRun.get())) {
      return;
    }
    selectedRun.set(runId);
    polledRun = runId;
    tasks.clear();
    if (visible && runId != null) {
      taskPoller.restart();
    } else {
      taskPoller.stop();
    }
  }

  /**
   * Pauses or resumes a DAG; the switch moves at once and moves back if Airflow refuses. The DAG
   * list is not polled while the change is on its way: a poll that started before it would answer
   * with the old state and move the switch back. Polling resumes, with a fresh read, once every
   * change has been answered.
   */
  public CompletableFuture<Void> setPaused(String dagId, boolean paused) {
    replaceDag(dagId, paused);
    pausesInFlight++;
    dagPoller.stop();
    return service
        .setPaused(dagId, paused)
        .whenComplete(
            (ignored, failure) ->
                ui.execute(
                    () -> {
                      if (failure != null) {
                        replaceDag(dagId, !paused);
                      }
                      pausesInFlight--;
                      if (pausesInFlight == 0 && visible) {
                        dagPoller.start();
                      }
                    }));
  }

  private void replaceDag(String dagId, boolean paused) {
    for (int index = 0; index < dags.size(); index++) {
      DagRow row = dags.get(index);
      if (row.dagId().equals(dagId) && row.dag().paused() != paused) {
        Dag dag = row.dag();
        dags.set(
            index,
            new DagRow(
                new Dag(
                    dag.dagId(),
                    dag.displayName(),
                    paused,
                    dag.description(),
                    dag.schedule(),
                    dag.tags(),
                    dag.importErrors(),
                    dag.nextRun()),
                row.runs()));
      }
    }
  }

  /** Triggers a run of the selected DAG; on success the new run is selected and followed. */
  public CompletableFuture<DagRun> trigger(String dagId, Map<String, Object> conf, String note) {
    return service
        .trigger(dagId, conf, note)
        .whenComplete(
            (run, failure) -> {
              if (failure == null) {
                ui.execute(
                    () -> {
                      if (dagId.equals(selectedDag.get())) {
                        selectRun(run.runId());
                        runPoller.refresh();
                      }
                      dagPoller.refresh();
                    });
              }
            });
  }

  /** The known parameters of a DAG, for the trigger form. */
  public Optional<JobCatalog.Entry> catalogEntry(String dagId) {
    return catalog.dag(dagId);
  }

  /** The non-blank form values as a run configuration. */
  public static Map<String, Object> conf(Map<String, String> values) {
    Map<String, Object> conf = new LinkedHashMap<>();
    values.forEach(
        (key, value) -> {
          if (key != null && !key.isBlank() && value != null && !value.isBlank()) {
            conf.put(key.strip(), value);
          }
        });
    return conf;
  }

  /** A JSON object typed by the user, or the reason it is not one. */
  public Result<Map<String, Object>> parseConf(String text) {
    if (text == null || text.isBlank()) {
      return Result.ok(Map.of());
    }
    try {
      JsonNode node = json.readTree(text);
      if (node == null || !node.isObject()) {
        return Result.error("配置必须是一个 JSON 对象，例如 {\"input\": \"…\"}。");
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> conf = json.treeToValue(node, LinkedHashMap.class);
      return Result.ok(conf);
    } catch (JsonProcessingException malformed) {
      return Result.error(
          "JSON 无法解析：第 "
              + malformed.getLocation().getLineNr()
              + " 行第 "
              + malformed.getLocation().getColumnNr()
              + " 列。");
    }
  }

  /** A value or the sentence explaining why there is none. */
  public record Result<T>(Optional<T> value, String error) {
    static <T> Result<T> ok(T value) {
      return new Result<>(Optional.of(value), "");
    }

    static <T> Result<T> error(String error) {
      return new Result<>(Optional.empty(), error);
    }
  }

  /** Starts reading a task's log from the beginning. */
  public void openLog(TaskInstance task) {
    String dagId = selectedDag.get();
    String runId = selectedRun.get();
    if (dagId == null || runId == null) {
      return;
    }
    logTarget =
        new LogTarget(dagId, runId, task.taskId(), task.mapIndex(), Math.max(1, task.tryNumber()));
    logLines.clear();
    logChars = 0;
    logNext.set("");
    logProblem.set("");
    loadLog();
  }

  /** Reads the next part of the open log. */
  public void loadMoreLog() {
    if (logTarget != null && !logNext.get().isEmpty()) {
      loadLog();
    }
  }

  public void closeLog() {
    logTarget = null;
    logLoadingFor = null;
    logLoading.set(false);
    logLines.clear();
    logChars = 0;
    logNext.set("");
  }

  private void loadLog() {
    LogTarget target = logTarget;
    if (logLoadingFor == target) {
      return; // this log's next part is already on its way
    }
    // A request for a log that was closed meanwhile is superseded, not waited for.
    logLoadingFor = target;
    logLoading.set(true);
    service
        .log(
            target.dagId(),
            target.runId(),
            target.taskId(),
            target.mapIndex(),
            target.tryNumber(),
            logNext.get())
        .whenComplete(
            (chunk, failure) ->
                ui.execute(
                    () -> {
                      if (logLoadingFor == target) {
                        logLoadingFor = null;
                        logLoading.set(false);
                      }
                      if (target != logTarget) {
                        return;
                      }
                      if (failure != null) {
                        logProblem.set(PipelineService.describe(failure));
                        return;
                      }
                      appendLog(chunk);
                    }));
  }

  private void appendLog(LogChunk chunk) {
    logLines.addAll(chunk.lines());
    for (String line : chunk.lines()) {
      logChars += line.length();
    }
    int drop = 0;
    while (logChars > LOG_LIMIT_CHARS && drop < logLines.size()) {
      logChars -= logLines.get(drop).length();
      drop++;
    }
    if (drop > 0) {
      logLines.remove(0, drop);
    }
    logNext.set(chunk.next().orElse(""));
  }

  @Override
  public void shown() {
    visible = true;
    dagPoller.start();
    if (selectedDag.get() != null) {
      runPoller.start();
    }
    if (selectedRun.get() != null) {
      taskPoller.start();
    }
  }

  @Override
  public void hidden() {
    visible = false;
    dagPoller.stop();
    runPoller.stop();
    taskPoller.stop();
  }

  public void refresh() {
    dagPoller.refresh();
    runPoller.refresh();
    if (selectedRun.get() != null && !taskPoller.running() && visible) {
      taskPoller.start();
    } else {
      taskPoller.refresh();
    }
  }

  public ObservableList<DagRow> dags() {
    return dags;
  }

  public ObservableList<DagRun> runs() {
    return runs;
  }

  public ObservableList<TaskInstance> tasks() {
    return tasks;
  }

  public ReadOnlyStringProperty selectedDag() {
    return selectedDag;
  }

  public ReadOnlyStringProperty selectedRun() {
    return selectedRun;
  }

  public ReadOnlyStringProperty dagProblem() {
    return dagProblem;
  }

  public ReadOnlyStringProperty detailProblem() {
    return detailProblem;
  }

  public ReadOnlyBooleanProperty loaded() {
    return loaded;
  }

  public ReadOnlyBooleanProperty connected() {
    return connected;
  }

  public ObservableList<String> logLines() {
    return logLines;
  }

  /** The continuation token of the open log; empty when it is read to its current end. */
  public ReadOnlyStringProperty logNext() {
    return logNext;
  }

  public ReadOnlyStringProperty logProblem() {
    return logProblem;
  }

  public ReadOnlyBooleanProperty logLoading() {
    return logLoading;
  }
}
