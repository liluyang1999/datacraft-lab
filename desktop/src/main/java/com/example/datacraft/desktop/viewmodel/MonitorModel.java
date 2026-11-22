package com.example.datacraft.desktop.viewmodel;

import com.example.datacraft.desktop.api.AirflowModels.ActiveTask;
import com.example.datacraft.desktop.api.AirflowModels.ImportError;
import com.example.datacraft.desktop.api.AirflowModels.Pool;
import com.example.datacraft.desktop.api.EngineModels.Status;
import com.example.datacraft.desktop.app.Preferences;
import com.example.datacraft.desktop.service.ConnectionMonitor.Sample;
import com.example.datacraft.desktop.service.ConnectionMonitor.Snapshot;
import com.example.datacraft.desktop.service.MonitorService;
import com.example.datacraft.desktop.service.MonitorService.Live;
import com.example.datacraft.desktop.service.MonitorService.Part;
import com.example.datacraft.desktop.service.Poller;
import com.example.datacraft.desktop.service.Session;
import com.example.datacraft.desktop.service.Ticker;
import com.example.datacraft.desktop.util.Lists;
import com.example.datacraft.desktop.viewmodel.Alerts.Alert;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.LongProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyLongProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/**
 * The monitor: the engine's load and counters, the tasks running or waiting in Airflow, its pools
 * and DAG files that fail to parse, both backends' latency and availability, and the alerts derived
 * from all of it. While the monitor is on screen it polls every {@link #INTERVAL}; otherwise every
 * {@link #BACKGROUND_INTERVAL}, so the sidebar's alert count stays current; while the window is
 * minimised it does not poll at all.
 */
public final class MonitorModel implements Visibility {

  static final Duration INTERVAL = Duration.ofSeconds(5);
  static final Duration BACKGROUND_INTERVAL = Duration.ofSeconds(60);

  /** How long a rejected engine run stays an alert. */
  static final Duration REJECTION_WINDOW = Duration.ofMinutes(10);

  private record Rejection(Instant at, long count) {}

  private final StatusModel status;
  private final MonitorService service;
  private final Clock clock;
  private final Executor ui;
  private final Poller<Live> poller;
  private final StringProperty engineVersion = new SimpleStringProperty("");
  private final StringProperty airflowVersion = new SimpleStringProperty("");
  private long versionGeneration;
  private boolean versionRequested;
  private final ObjectProperty<Status> engine = new SimpleObjectProperty<>();
  private final StringProperty engineProblem = new SimpleStringProperty("");
  private final BooleanProperty engineUnsupported = new SimpleBooleanProperty(false);
  private final ObservableList<ActiveTask> tasks = FXCollections.observableArrayList();
  private final StringProperty tasksProblem = new SimpleStringProperty("");
  private final ObservableList<Pool> pools = FXCollections.observableArrayList();
  private final StringProperty poolsProblem = new SimpleStringProperty("");
  private final ObservableList<ImportError> importErrors = FXCollections.observableArrayList();
  private final StringProperty importProblem = new SimpleStringProperty("");
  private final ObservableList<Alert> alerts = FXCollections.observableArrayList();
  private final IntegerProperty alertCount = new SimpleIntegerProperty();
  private final ObjectProperty<LatencyStats> engineLatency =
      new SimpleObjectProperty<>(LatencyStats.NONE);
  private final ObjectProperty<LatencyStats> airflowLatency =
      new SimpleObjectProperty<>(LatencyStats.NONE);
  private final ObjectProperty<List<Sample>> engineSamples = new SimpleObjectProperty<>(List.of());
  private final ObjectProperty<List<Sample>> airflowSamples = new SimpleObjectProperty<>(List.of());
  private final BooleanProperty loaded = new SimpleBooleanProperty(false);
  private final BooleanProperty connected = new SimpleBooleanProperty(false);
  private final ObjectProperty<Instant> updatedAt = new SimpleObjectProperty<>();
  private final LongProperty applied = new SimpleLongProperty();
  private final Deque<Rejection> rejections = new ArrayDeque<>();
  private Optional<Live> latest = Optional.empty();
  // Read by the poller's thread when it schedules the next poll.
  private volatile boolean visible;
  private boolean windowShown = true;
  private boolean started;

  public MonitorModel(
      MonitorService service,
      StatusModel status,
      Session session,
      Preferences preferences,
      Ticker ticker,
      Clock clock,
      Executor ui) {
    this.status = status;
    this.service = service;
    this.clock = clock;
    this.ui = ui;
    this.poller =
        new Poller<>(
            ticker,
            service::live,
            this::apply,
            failure -> {},
            () -> preferences.refresh().scale(visible ? INTERVAL : BACKGROUND_INTERVAL),
            ui);
    connected.set(session.current().isPresent());
    status.snapshot().addListener((property, before, now) -> samples(now));
    if (status.snapshot().get() != null) {
      samples(status.snapshot().get());
    }
    session.subscribe(
        connection ->
            ui.execute(
                () -> {
                  connected.set(connection.isPresent());
                  clear();
                  if (poller.running()) {
                    poller.restart();
                  }
                }));
  }

  /** Starts the background polling that keeps the alert count current. */
  public void start() {
    started = true;
    resume();
  }

  @Override
  public void shown() {
    visible = true;
    started = true;
    // Polls at once at the faster rate instead of waiting out the background interval.
    if (poller.running()) {
      poller.refresh();
    } else {
      resume();
    }
  }

  @Override
  public void hidden() {
    visible = false;
  }

  /** The window was minimised or restored: no polling while nothing is on screen. */
  public void windowShown(boolean shown) {
    windowShown = shown;
    if (shown) {
      resume();
    } else {
      poller.stop();
    }
  }

  public void refresh() {
    poller.refresh();
  }

  private void resume() {
    if (started && windowShown && !poller.running()) {
      poller.start();
    }
  }

  private void clear() {
    engine.set(null);
    engineProblem.set("");
    engineUnsupported.set(false);
    tasks.clear();
    tasksProblem.set("");
    pools.clear();
    poolsProblem.set("");
    importErrors.clear();
    importProblem.set("");
    rejections.clear();
    latest = Optional.empty();
    engineVersion.set("");
    airflowVersion.set("");
    versionGeneration++;
    versionRequested = false;
    loaded.set(false);
    updatedAt.set(null);
    recompute();
  }

  void apply(Live live) {
    Status before = engine.get();
    Part<Status> enginePart = live.engine();
    engineUnsupported.set(enginePart.unsupported());
    engineProblem.set(enginePart.unsupported() ? "" : enginePart.problem());
    enginePart
        .value()
        .ifPresentOrElse(
            now -> {
              countRejections(before, now);
              engine.set(now);
              engineVersion.set(now.version());
            },
            () -> {
              if (enginePart.unsupported()) {
                engine.set(null);
              }
            });
    live.tasks().value().ifPresent(fresh -> Lists.reconcile(tasks, fresh, ActiveTask::key));
    tasksProblem.set(live.tasks().problem());
    live.pools().value().ifPresent(fresh -> Lists.reconcile(pools, fresh, Pool::name));
    poolsProblem.set(live.pools().problem());
    live.importErrors()
        .value()
        .ifPresent(fresh -> Lists.reconcile(importErrors, fresh, ImportError::id));
    importProblem.set(live.importErrors().problem());
    latest = Optional.of(live);
    if (!versionRequested && live.tasks().present()) {
      requestAirflowVersion();
    }
    loaded.set(true);
    updatedAt.set(clock.instant());
    applied.set(applied.get() + 1);
    recompute();
  }

  /** Airflow's version, once per connection; a failure is retried on a later poll. */
  private void requestAirflowVersion() {
    versionRequested = true;
    long generation = versionGeneration;
    service
        .airflowVersion()
        .whenComplete(
            (version, failure) ->
                ui.execute(
                    () -> {
                      if (generation != versionGeneration) {
                        return;
                      }
                      if (failure == null) {
                        airflowVersion.set(version);
                      } else {
                        versionRequested = false;
                      }
                    }));
  }

  private void countRejections(Status before, Status now) {
    Instant at = clock.instant();
    // A restarted engine starts its counters again: compare only within one process.
    if (before != null
        && before.startedAt().equals(now.startedAt())
        && now.rejected() > before.rejected()) {
      rejections.addLast(new Rejection(at, now.rejected() - before.rejected()));
    }
    while (!rejections.isEmpty()
        && rejections.getFirst().at().isBefore(at.minus(REJECTION_WINDOW))) {
      rejections.removeFirst();
    }
  }

  private void samples(Snapshot snapshot) {
    engineSamples.set(snapshot.engineSamples());
    airflowSamples.set(snapshot.airflowSamples());
    engineLatency.set(LatencyStats.of(snapshot.engineSamples()));
    airflowLatency.set(LatencyStats.of(snapshot.airflowSamples()));
    recompute();
  }

  private void recompute() {
    if (status.engine().get() == null || status.airflow().get() == null) {
      return;
    }
    long recent = rejections.stream().mapToLong(Rejection::count).sum();
    List<Alert> fresh = Alerts.of(status.engine().get(), status.airflow().get(), latest, recent);
    Lists.reconcile(alerts, fresh, Alert::key);
    alertCount.set(alerts.size());
  }

  /** The connected engine's version from its status; empty until it answered. */
  public ReadOnlyStringProperty engineVersion() {
    return engineVersion;
  }

  /** The connected Airflow's version; empty until it answered. */
  public ReadOnlyStringProperty airflowVersion() {
    return airflowVersion;
  }

  /** The engine's last status; null before the first answer or when it has no status route. */
  public ReadOnlyObjectProperty<Status> engine() {
    return engine;
  }

  public ReadOnlyStringProperty engineProblem() {
    return engineProblem;
  }

  /** The engine answers but predates {@code /status}. */
  public ReadOnlyBooleanProperty engineUnsupported() {
    return engineUnsupported;
  }

  public ObservableList<ActiveTask> tasks() {
    return tasks;
  }

  public ReadOnlyStringProperty tasksProblem() {
    return tasksProblem;
  }

  public ObservableList<Pool> pools() {
    return pools;
  }

  public ReadOnlyStringProperty poolsProblem() {
    return poolsProblem;
  }

  public ObservableList<ImportError> importErrors() {
    return importErrors;
  }

  public ReadOnlyStringProperty importProblem() {
    return importProblem;
  }

  public ObservableList<Alert> alerts() {
    return alerts;
  }

  /** The sidebar badge. */
  public ReadOnlyIntegerProperty alertCount() {
    return alertCount;
  }

  public ReadOnlyObjectProperty<LatencyStats> engineLatency() {
    return engineLatency;
  }

  public ReadOnlyObjectProperty<LatencyStats> airflowLatency() {
    return airflowLatency;
  }

  public ReadOnlyObjectProperty<List<Sample>> engineSamples() {
    return engineSamples;
  }

  public ReadOnlyObjectProperty<List<Sample>> airflowSamples() {
    return airflowSamples;
  }

  public ReadOnlyBooleanProperty loaded() {
    return loaded;
  }

  public ReadOnlyBooleanProperty connected() {
    return connected;
  }

  /** When the live data was last fetched; null before the first poll. */
  public ReadOnlyObjectProperty<Instant> updatedAt() {
    return updatedAt;
  }

  /** Counts applied polls, so that running durations move on even when nothing changed. */
  public ReadOnlyLongProperty applied() {
    return applied;
  }

  /** Whether the monitor polls now (for tests). */
  boolean polling() {
    return poller.running();
  }
}
