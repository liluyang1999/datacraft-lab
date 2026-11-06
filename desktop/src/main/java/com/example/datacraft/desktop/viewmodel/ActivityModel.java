package com.example.datacraft.desktop.viewmodel;

import com.example.datacraft.desktop.service.ActivityLog;
import com.example.datacraft.desktop.store.ActivityRepository;
import com.example.datacraft.desktop.store.ActivityRepository.Entry;
import com.example.datacraft.desktop.store.ActivityRepository.Source;
import java.util.List;
import java.util.concurrent.Executor;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;

/** The activity stream, newest first, filtered by source; new entries arrive as they happen. */
public final class ActivityModel implements Visibility {

  private static final System.Logger LOG = System.getLogger(ActivityModel.class.getName());

  private final ActivityLog log;
  private final Executor ui;
  private final ObservableList<Entry> entries = FXCollections.observableArrayList();
  private final FilteredList<Entry> visible = new FilteredList<>(entries);
  private final ObjectProperty<Source> filter = new SimpleObjectProperty<>();
  private boolean loaded;
  private boolean loading;
  private long generation;

  /** Entries that arrive while the first load is on its way; merged when it lands. */
  private final List<Entry> early = new java.util.ArrayList<>();

  public ActivityModel(ActivityLog log, Executor ui) {
    this.log = log;
    this.ui = ui;
    filter.addListener(
        (property, before, source) ->
            visible.setPredicate(source == null ? null : entry -> entry.source() == source));
    log.subscribe(entry -> ui.execute(() -> add(entry)));
    log.onCleared(
        () ->
            ui.execute(
                () -> {
                  generation++;
                  loaded = true;
                  loading = false;
                  early.clear();
                  entries.clear();
                }));
  }

  private void add(Entry entry) {
    if (!loaded) {
      if (loading) {
        early.add(entry);
      }
      return; // before the first load the store has it
    }
    entries.addFirst(entry);
    if (entries.size() > ActivityRepository.KEEP) {
      entries.remove(ActivityRepository.KEEP, entries.size());
    }
  }

  @Override
  public void shown() {
    if (loaded || loading) {
      return;
    }
    loading = true;
    long requested = ++generation;
    log.recent(ActivityRepository.KEEP)
        .whenComplete(
            (list, failure) ->
                ui.execute(
                    () -> {
                      if (requested != generation) {
                        return;
                      }
                      loading = false;
                      if (failure == null) {
                        loaded = true;
                        entries.setAll(list);
                        early.stream().filter(entry -> !list.contains(entry)).forEach(this::add);
                      } else {
                        LOG.log(
                            System.Logger.Level.WARNING, "Activity could not be loaded", failure);
                      }
                      early.clear();
                    }));
  }

  @Override
  public void hidden() {
    // New entries keep arriving while hidden: the list is current when the view returns.
  }

  public void clear() {
    log.clear();
  }

  public ObservableList<Entry> visibleEntries() {
    return visible;
  }

  /** The source to show, or null for all. */
  public ObjectProperty<Source> filter() {
    return filter;
  }
}
