package com.example.datacraft.desktop.viewmodel;

import com.example.datacraft.desktop.service.BackendStatus;
import com.example.datacraft.desktop.service.ConnectionMonitor;
import com.example.datacraft.desktop.service.ConnectionMonitor.Snapshot;
import com.example.datacraft.desktop.util.Latest;
import java.util.concurrent.Executor;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;

/** The status of both backends for the top bar and the overview, updated on the UI thread. */
public final class StatusModel {

  private final ConnectionMonitor monitor;
  private final ObjectProperty<BackendStatus> engine = new SimpleObjectProperty<>();
  private final ObjectProperty<BackendStatus> airflow = new SimpleObjectProperty<>();
  private final ObjectProperty<Snapshot> snapshot = new SimpleObjectProperty<>();

  public StatusModel(ConnectionMonitor monitor, Executor ui) {
    this.monitor = monitor;
    apply(monitor.snapshot());
    Latest<Snapshot> latest = new Latest<>(ui, this::apply);
    monitor.subscribe(latest::offer);
  }

  private void apply(Snapshot next) {
    snapshot.set(next);
    engine.set(next.engine());
    airflow.set(next.airflow());
  }

  public ReadOnlyObjectProperty<BackendStatus> engine() {
    return engine;
  }

  public ReadOnlyObjectProperty<BackendStatus> airflow() {
    return airflow;
  }

  public ReadOnlyObjectProperty<Snapshot> snapshot() {
    return snapshot;
  }

  /** Checks both backends now (Ctrl+R, the refresh button). */
  public void refresh() {
    monitor.refresh();
  }
}
