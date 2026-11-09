package com.example.datacraft.desktop.viewmodel;

import com.example.datacraft.desktop.api.ApiException;
import com.example.datacraft.desktop.api.EngineModels.JobInfo;
import com.example.datacraft.desktop.api.EngineModels.JobResult;
import com.example.datacraft.desktop.api.HttpTransport;
import com.example.datacraft.desktop.app.Preferences;
import com.example.datacraft.desktop.service.ConnectionMonitor;
import com.example.datacraft.desktop.service.JobCatalog;
import com.example.datacraft.desktop.service.JobService;
import com.example.datacraft.desktop.service.Messages;
import com.example.datacraft.desktop.service.Poller;
import com.example.datacraft.desktop.service.Session;
import com.example.datacraft.desktop.service.Ticker;
import com.example.datacraft.desktop.store.PresetRepository;
import com.example.datacraft.desktop.store.PresetRepository.Preset;
import com.example.datacraft.desktop.store.RecentRunRepository.RecentRun;
import com.example.datacraft.desktop.util.Lists;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import javafx.beans.Observable;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;

/**
 * The jobs view: the engine's job list, a parameter form per job (drafts are kept while the app
 * runs), presets, running a job and stopping the wait, the result and the job's recent runs.
 */
public final class JobsModel implements Visibility {

  private static final System.Logger LOG = System.getLogger(JobsModel.class.getName());

  public static final int RIBBON = 20;
  static final Duration INTERVAL = Duration.ofSeconds(60);

  /** What the model remembers for each job while the app runs. */
  private static final class JobState {
    List<ParamDraft> draft;
    JobService.Handle handle;
    JobResult result;
    String error = "";
  }

  /** Preserve unfinished rows as rows: a map would silently collapse duplicate or empty keys. */
  private record ParamDraft(Optional<JobCatalog.Param> spec, String key, String value) {
    ParamRow row() {
      return new ParamRow(spec, key, value);
    }
  }

  private List<ParamDraft> draft() {
    return params.stream()
        .map(row -> new ParamDraft(row.spec(), row.key().get(), row.value().get()))
        .toList();
  }

  private final JobService service;
  private final JobCatalog catalog;
  private final PresetRepository presetRepository;
  private final Executor ui;
  private final Poller<List<JobInfo>> poller;
  private final Map<String, JobState> states = new HashMap<>();
  private long selectionGeneration;
  private long recentGeneration;

  private final ObservableList<JobInfo> jobs = FXCollections.observableArrayList();
  private final ObjectProperty<JobInfo> selected = new SimpleObjectProperty<>();
  private final ObservableList<ParamRow> params =
      FXCollections.observableArrayList(row -> new Observable[] {row.key(), row.value()});
  private final StringProperty missing = new SimpleStringProperty("");
  private final BooleanProperty running = new SimpleBooleanProperty(false);
  private final ObjectProperty<Instant> runningSince = new SimpleObjectProperty<>();
  private final ObjectProperty<JobResult> result = new SimpleObjectProperty<>();
  private final StringProperty runError = new SimpleStringProperty("");
  private final ObservableList<Preset> presets = FXCollections.observableArrayList();
  private final ObservableList<RecentRun> recent = FXCollections.observableArrayList();
  private final BooleanProperty recentLoading = new SimpleBooleanProperty(false);
  private final StringProperty recentProblem = new SimpleStringProperty("");
  private final StringProperty listProblem = new SimpleStringProperty("");
  private final StringProperty presetProblem = new SimpleStringProperty("");
  private final BooleanProperty loaded = new SimpleBooleanProperty(false);
  private final BooleanProperty connected = new SimpleBooleanProperty(false);
  private final BooleanBinding canRun;

  public JobsModel(
      JobService service,
      JobCatalog catalog,
      PresetRepository presetRepository,
      Session session,
      Preferences preferences,
      Ticker ticker,
      Executor ui) {
    this.service = service;
    this.catalog = catalog;
    this.presetRepository = presetRepository;
    this.ui = ui;
    this.poller =
        new Poller<>(
            ticker,
            service::jobs,
            this::applyJobs,
            failure -> listProblem.set(describe(failure)), // keep the last list on screen
            () -> preferences.refresh().scale(INTERVAL),
            ui);
    params.addListener((ListChangeListener<ParamRow>) change -> updateMissing());
    canRun =
        Bindings.createBooleanBinding(
            () ->
                selected.get() != null
                    && missing.get().isEmpty()
                    && !running.get()
                    && connected.get(),
            selected,
            missing,
            running,
            connected);
    connected.set(session.current().isPresent());
    session.subscribe(connection -> ui.execute(() -> reset(connection.isPresent())));
    service.onRecentCleared(
        () ->
            ui.execute(
                () -> {
                  recent.clear();
                  loadRecent();
                }));
  }

  /**
   * Another profile became active: nothing of the previous one stays on screen, neither its job
   * list nor a selection, draft, result or run, and the list is fetched again for the new one.
   */
  private void reset(boolean isConnected) {
    selectionGeneration++;
    connected.set(isConnected);
    states.clear();
    selected.set(null);
    params.clear();
    presets.clear();
    recent.clear();
    recentLoading.set(false);
    recentProblem.set("");
    jobs.clear();
    listProblem.set("");
    presetProblem.set("");
    loaded.set(false);
    showState(new JobState());
    if (poller.running()) {
      poller.restart();
    }
  }

  void applyJobs(List<JobInfo> fresh) {
    listProblem.set("");
    Lists.reconcile(jobs, fresh, JobInfo::name);
    loaded.set(true);
    if (selected.get() == null && !jobs.isEmpty()) {
      select(jobs.getFirst());
    }
  }

  /** Shows a job: its draft, its last result, its presets and its recent runs. */
  public void select(JobInfo job) {
    long selection = ++selectionGeneration;
    JobInfo previous = selected.get();
    if (previous != null) {
      state(previous.name()).draft = draft();
    }
    selected.set(job);
    presetProblem.set("");
    recent.clear();
    recentProblem.set("");
    if (job == null) {
      params.clear();
      recentLoading.set(false);
      showState(new JobState());
      return;
    }
    JobState state = state(job.name());
    params.setAll(
        state.draft == null
            ? rows(job.name(), Map.of())
            : state.draft.stream().map(ParamDraft::row).toList());
    showState(state);
    presets.clear();
    presetRepository
        .list(job.name())
        .thenAccept(
            list ->
                ui.execute(
                    () -> {
                      if (selection == selectionGeneration) {
                        presets.setAll(list);
                      }
                    }));
    loadRecent();
  }

  private List<ParamRow> rows(String jobName, Map<String, String> values) {
    return ParamForm.rows(catalog.job(jobName), values);
  }

  /** The valid form's request values; blank values use backend defaults. */
  private Map<String, String> values() {
    return ParamForm.collect(params).values();
  }

  private void updateMissing() {
    JobInfo job = selected.get();
    if (job == null) {
      missing.set("");
      return;
    }
    String problem = parameterProblem();
    if (!problem.isEmpty()) {
      missing.set(problem);
      return;
    }
    List<String> keys =
        catalog.job(job.name()).map(entry -> entry.missing(values())).orElse(List.of());
    missing.set(keys.isEmpty() ? "" : "缺少必填参数：" + String.join("、", keys));
  }

  private String parameterProblem() {
    return ParamForm.collect(params).error();
  }

  public void addParam() {
    params.add(new ParamRow(Optional.empty(), "", ""));
  }

  public void removeParam(ParamRow row) {
    if (!row.known()) {
      params.remove(row);
    }
  }

  /** Runs the selected job with the form's values; blank values use the job's defaults. */
  public void run() {
    JobInfo job = selected.get();
    if (!canRun.get()) {
      return;
    }
    JobState state = state(job.name());
    state.draft = draft();
    state.error = "";
    state.handle = service.run(job.name(), values());
    showState(state);
    JobService.Handle handle = state.handle;
    handle
        .result()
        .whenComplete(
            (value, failure) ->
                ui.execute(
                    () -> {
                      if (state.handle != handle || states.get(job.name()) != state) {
                        return; // a newer run, or a run of the profile that was active before
                      }
                      state.handle = null;
                      if (failure == null) {
                        state.result = value;
                        state.error = "";
                      } else if (HttpTransport.cancelled(failure)) {
                        state.error = "已停止等待：作业仍在引擎上运行，结果不会显示在这里。";
                      } else if (ApiException.of(failure)
                          .map(
                              api ->
                                  api.error()
                                      == com.example.datacraft.desktop.api.ApiError.TIMED_OUT)
                          .orElse(false)) {
                        state.error = "等待超过了设置里的上限：作业仍在引擎上运行，结果不会显示在这里。";
                      } else {
                        state.error = describe(failure);
                      }
                      if (job.equals(selected.get())) {
                        showState(state);
                        loadRecent();
                      }
                    }));
  }

  /** Stops waiting for the selected job's run; the engine keeps running it. */
  public void stopWaiting() {
    JobInfo job = selected.get();
    if (job != null && state(job.name()).handle != null) {
      state(job.name()).handle.stopWaiting();
    }
  }

  public void applyPreset(Preset preset) {
    JobInfo job = selected.get();
    if (job != null && job.name().equals(preset.jobName())) {
      params.setAll(rows(job.name(), preset.params()));
    }
  }

  public void savePreset(String name) {
    JobInfo job = selected.get();
    if (job == null || name == null || name.isBlank()) {
      return;
    }
    String problem = parameterProblem();
    if (!problem.isEmpty()) {
      presetProblem.set(problem);
      return;
    }
    Preset preset = new Preset(job.name(), name.strip(), values());
    presetProblem.set("");
    reloadPresetsAfter(job.name(), presetRepository.save(preset), "预设没有保存");
  }

  public void deletePreset(Preset preset) {
    presetProblem.set("");
    reloadPresetsAfter(
        preset.jobName(), presetRepository.delete(preset.jobName(), preset.name()), "预设没有删除");
  }

  /** Shows the job's presets once the change is stored, if that job is still the one shown. */
  private void reloadPresetsAfter(
      String jobName, java.util.concurrent.CompletableFuture<Void> change, String failureText) {
    long selection = selectionGeneration;
    change
        .thenCompose(ignored -> presetRepository.list(jobName))
        .whenComplete(
            (list, failure) ->
                ui.execute(
                    () -> {
                      if (failure != null) {
                        LOG.log(System.Logger.Level.WARNING, failureText + ": " + jobName, failure);
                        if (selection == selectionGeneration) {
                          presetProblem.set(failureText + "：本机数据库出错，详情见日志。");
                        }
                        return;
                      }
                      JobInfo shown = selected.get();
                      if (selection == selectionGeneration
                          && shown != null
                          && shown.name().equals(jobName)) {
                        presets.setAll(list);
                      }
                    }));
  }

  private void showState(JobState state) {
    running.set(state.handle != null);
    runningSince.set(state.handle == null ? null : state.handle.startedAt());
    result.set(state.result);
    runError.set(state.error);
  }

  private void loadRecent() {
    long selection = selectionGeneration;
    long requested = ++recentGeneration;
    JobInfo job = selected.get();
    if (job == null) {
      recent.clear();
      recentLoading.set(false);
      return;
    }
    recentLoading.set(true);
    service
        .recent(job.name(), RIBBON)
        .whenComplete(
            (runs, failure) ->
                ui.execute(
                    () -> {
                      if (selection == selectionGeneration && requested == recentGeneration) {
                        recentLoading.set(false);
                        if (failure == null) {
                          recentProblem.set("");
                          Lists.reconcile(
                              recent, runs, run -> run.startedAt().toEpochMilli() + run.jobName());
                        } else {
                          LOG.log(
                              System.Logger.Level.WARNING,
                              "Recent job runs could not be loaded",
                              failure);
                          recentProblem.set("本机运行记录暂时无法读取，请刷新重试。");
                        }
                      }
                    }));
  }

  private JobState state(String jobName) {
    return states.computeIfAbsent(jobName, name -> new JobState());
  }

  static String describe(Throwable failure) {
    return ApiException.of(failure)
        .map(api -> Messages.describe(ConnectionMonitor.ENGINE, api))
        .orElseGet(
            () ->
                failure.getCause() == null
                    ? failure.getMessage()
                    : failure.getCause().getMessage());
  }

  @Override
  public void shown() {
    poller.start();
    if (selected.get() != null) {
      loadRecent();
    }
  }

  @Override
  public void hidden() {
    poller.stop();
  }

  public void refresh() {
    poller.refresh();
    loadRecent();
  }

  public ObservableList<JobInfo> jobs() {
    return jobs;
  }

  public ReadOnlyObjectProperty<JobInfo> selected() {
    return selected;
  }

  public ObservableList<ParamRow> params() {
    return params;
  }

  public ReadOnlyStringProperty missing() {
    return missing;
  }

  public BooleanBinding canRun() {
    return canRun;
  }

  public ReadOnlyBooleanProperty running() {
    return running;
  }

  public ReadOnlyObjectProperty<Instant> runningSince() {
    return runningSince;
  }

  public ReadOnlyObjectProperty<JobResult> result() {
    return result;
  }

  public ReadOnlyStringProperty runError() {
    return runError;
  }

  public ObservableList<Preset> presets() {
    return presets;
  }

  public ObservableList<RecentRun> recent() {
    return recent;
  }

  public ReadOnlyBooleanProperty recentLoading() {
    return recentLoading;
  }

  public ReadOnlyStringProperty recentProblem() {
    return recentProblem;
  }

  public ReadOnlyStringProperty listProblem() {
    return listProblem;
  }

  /** Why the last preset save or delete did not happen; empty when it did. */
  public ReadOnlyStringProperty presetProblem() {
    return presetProblem;
  }

  public ReadOnlyBooleanProperty loaded() {
    return loaded;
  }

  public ReadOnlyBooleanProperty connected() {
    return connected;
  }

  /** The catalogue's Chinese summary of a job, or the engine's own description. */
  public String summary(JobInfo job) {
    return catalog
        .job(job.name())
        .map(JobCatalog.Entry::summary)
        .filter(text -> !text.isEmpty())
        .orElse(job.description());
  }

  /** Where a job runs, for its glyph; a job the catalogue does not know is OTHER. */
  public JobCatalog.Kind kind(JobInfo job) {
    return catalog.job(job.name()).map(JobCatalog.Entry::kind).orElse(JobCatalog.Kind.OTHER);
  }

  public Optional<JobCatalog.Entry> catalogEntry() {
    return Optional.ofNullable(selected.get()).flatMap(job -> catalog.job(job.name()));
  }
}
