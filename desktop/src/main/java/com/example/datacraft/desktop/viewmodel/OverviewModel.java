package com.example.datacraft.desktop.viewmodel;

import com.example.datacraft.desktop.api.AirflowModels.DagRun;
import com.example.datacraft.desktop.api.RunTone;
import com.example.datacraft.desktop.app.Preferences;
import com.example.datacraft.desktop.service.JobService;
import com.example.datacraft.desktop.service.PipelineService;
import com.example.datacraft.desktop.service.Poller;
import com.example.datacraft.desktop.service.Session;
import com.example.datacraft.desktop.service.Ticker;
import com.example.datacraft.desktop.store.RecentRunRepository.RecentRun;
import com.example.datacraft.desktop.util.Lists;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
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
import javafx.collections.ObservableList;

/** The overview: every DAG with its last ten runs, and the newest runs of jobs and pipelines. */
public final class OverviewModel implements Visibility {

  private static final System.Logger LOG = System.getLogger(OverviewModel.class.getName());

  public static final int RIBBON = 10;
  static final int RECENT = 8;
  static final Duration INTERVAL = Duration.ofSeconds(10);

  /** One line of "最近运行": a job run of this app or a DAG run. */
  public record RecentItem(
      String key,
      String name,
      boolean pipeline,
      RunTone tone,
      String state,
      Instant startedAt,
      Optional<Duration> duration) {}

  /** How the runs on screen ended: the donut of the overview. */
  public record Mix(int success, int failed, int running, int waiting) {
    static final Mix NONE = new Mix(0, 0, 0, 0);

    public int total() {
      return success + failed + running + waiting;
    }

    /** The share of finished runs that succeeded, 0 to 1, or -1 when none has finished. */
    public double successShare() {
      int finished = success + failed;
      return finished == 0 ? -1 : (double) success / finished;
    }

    Mix with(RunTone tone) {
      return switch (tone) {
        case SUCCESS -> new Mix(success + 1, failed, running, waiting);
        case FAILED -> new Mix(success, failed + 1, running, waiting);
        case RUNNING -> new Mix(success, failed, running + 1, waiting);
        case WAITING -> new Mix(success, failed, running, waiting + 1);
        case NONE -> this;
      };
    }
  }

  /** What one poll brings: the DAG rows (absent when Airflow failed) and the cached job runs. */
  record Data(
      Optional<List<DagRow>> dags, String airflowProblem, Optional<List<RecentRun>> jobRuns) {}

  private final ObservableList<DagRow> dags = FXCollections.observableArrayList();
  private final ObservableList<RecentItem> recent = FXCollections.observableArrayList();
  private final ObjectProperty<Mix> mix = new SimpleObjectProperty<>(Mix.NONE);
  private final StringProperty problem = new SimpleStringProperty("");
  private final StringProperty recentProblem = new SimpleStringProperty("");
  private List<RecentRun> knownJobRuns = List.of();
  private final ObjectProperty<Instant> staleSince = new SimpleObjectProperty<>();
  private final BooleanProperty loaded = new SimpleBooleanProperty(false);
  private final BooleanProperty connected = new SimpleBooleanProperty(false);
  private final javafx.beans.property.LongProperty applied =
      new javafx.beans.property.SimpleLongProperty();
  private final Poller<Data> poller;
  private final Clock clock;

  public OverviewModel(
      PipelineService pipelines,
      JobService jobs,
      Session session,
      Preferences preferences,
      Ticker ticker,
      Clock clock,
      Executor ui) {
    this.clock = clock;
    this.poller =
        new Poller<>(
            ticker,
            () -> fetch(pipelines, jobs),
            this::apply,
            failure -> {},
            () -> preferences.refresh().scale(INTERVAL),
            ui);
    connected.set(session.current().isPresent());
    jobs.onRecentCleared(
        () ->
            ui.execute(
                () -> {
                  knownJobRuns = List.of();
                  recentProblem.set("");
                  applyRuns(knownJobRuns);
                  if (poller.running()) {
                    poller.restart();
                  }
                }));
    session.subscribe(
        connection ->
            ui.execute(
                () -> {
                  connected.set(connection.isPresent());
                  dags.clear();
                  recent.clear();
                  mix.set(Mix.NONE);
                  problem.set("");
                  recentProblem.set("");
                  knownJobRuns = List.of();
                  staleSince.set(null);
                  loaded.set(false);
                  if (poller.running()) {
                    poller.restart();
                  }
                }));
  }

  static CompletableFuture<Data> fetch(PipelineService pipelines, JobService jobs) {
    CompletableFuture<Data> airflow =
        DagRow.fetch(pipelines, RIBBON)
            .thenApply(rows -> new Data(Optional.of(rows), "", Optional.empty()))
            .exceptionally(
                failure ->
                    new Data(
                        Optional.empty(), PipelineService.describe(failure), Optional.empty()));
    CompletableFuture<Optional<List<RecentRun>>> history =
        jobs.recent(null, RECENT)
            .thenApply(Optional::of)
            .exceptionally(
                failure -> {
                  LOG.log(
                      System.Logger.Level.WARNING, "Recent job runs could not be loaded", failure);
                  return Optional.empty();
                });
    return airflow.thenCombine(
        history, (data, runs) -> new Data(data.dags(), data.airflowProblem(), runs));
  }

  void apply(Data data) {
    data.dags()
        .ifPresentOrElse(
            rows -> {
              Lists.reconcile(dags, rows, DagRow::dagId);
              staleSince.set(null);
            },
            () -> {
              if (staleSince.get() == null) {
                staleSince.set(clock.instant());
              }
            });
    problem.set(data.airflowProblem());
    data.jobRuns().ifPresent(runs -> knownJobRuns = List.copyOf(runs));
    recentProblem.set(
        data.jobRuns().isPresent()
            ? ""
            : "本机运行记录暂时无法读取" + (knownJobRuns.isEmpty() ? "" : "，已保留上次记录") + "，请刷新重试。");
    applyRuns(knownJobRuns);
    loaded.set(true);
    applied.set(applied.get() + 1);
  }

  private void applyRuns(List<RecentRun> jobRuns) {
    List<RecentItem> items = new ArrayList<>();
    for (RecentRun run : jobRuns) {
      items.add(
          new RecentItem(
              "job:" + run.jobName() + ":" + run.startedAt().toEpochMilli(),
              run.jobName(),
              false,
              RunTone.of(run.status()),
              Labels.state(run.status()),
              run.startedAt(),
              Optional.of(run.duration())));
    }
    for (DagRow row : dags) {
      for (DagRun run : row.runs()) {
        items.add(
            new RecentItem(
                "dag:" + run.dagId() + ":" + run.runId(),
                run.dagId(),
                true,
                run.tone(),
                Labels.state(run.state()),
                run.startDate().orElse(run.runAfter()),
                run.duration()));
      }
    }
    Mix counted = Mix.NONE;
    for (RecentItem item : items) {
      counted = counted.with(item.tone());
    }
    mix.set(counted);
    items.sort(Comparator.comparing(RecentItem::startedAt).reversed());
    Lists.reconcile(recent, items.subList(0, Math.min(RECENT, items.size())), RecentItem::key);
  }

  @Override
  public void shown() {
    poller.start();
  }

  @Override
  public void hidden() {
    poller.stop();
  }

  public void refresh() {
    poller.refresh();
  }

  public ObservableList<DagRow> dags() {
    return dags;
  }

  public ObservableList<RecentItem> recent() {
    return recent;
  }

  /** How every run on screen (each DAG's newest, the recent job runs) ended or stands. */
  public ReadOnlyObjectProperty<Mix> mix() {
    return mix;
  }

  /** Why Airflow's data is missing or old, or empty when it is current. */
  public ReadOnlyStringProperty problem() {
    return problem;
  }

  /** A failed local history read never turns known runs into an empty history. */
  public ReadOnlyStringProperty recentProblem() {
    return recentProblem;
  }

  /** When the shown DAG data stopped being refreshed; null while it is current. */
  public ReadOnlyObjectProperty<Instant> staleSince() {
    return staleSince;
  }

  public ReadOnlyBooleanProperty loaded() {
    return loaded;
  }

  public ReadOnlyBooleanProperty connected() {
    return connected;
  }

  /** Counts applied polls, so that relative times can move on even when nothing changed. */
  public javafx.beans.property.ReadOnlyLongProperty applied() {
    return applied;
  }
}
