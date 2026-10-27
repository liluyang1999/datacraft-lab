package com.example.datacraft.desktop.service;

import com.example.datacraft.desktop.store.ActivityRepository;
import com.example.datacraft.desktop.store.ActivityRepository.Entry;
import com.example.datacraft.desktop.store.ActivityRepository.Level;
import com.example.datacraft.desktop.store.ActivityRepository.Source;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Records what happened, keeps it in the store, and tells the views (activity list, toasts). */
public final class ActivityLog {

  private static final System.Logger LOG = System.getLogger(ActivityLog.class.getName());

  private final ActivityRepository repository;
  private final Clock clock;
  private final List<Consumer<Entry>> listeners = new CopyOnWriteArrayList<>();

  public ActivityLog(ActivityRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  public void record(Level level, Source source, String text) {
    record(level, source, text, "");
  }

  public void record(Level level, Source source, String text, String detail) {
    Entry entry = new Entry(clock.instant(), level, source, text, detail == null ? "" : detail);
    repository
        .add(entry)
        .exceptionally(
            failure -> {
              LOG.log(System.Logger.Level.WARNING, "An activity entry was not stored", failure);
              return null;
            });
    listeners.forEach(listener -> listener.accept(entry));
  }

  public CompletableFuture<List<Entry>> recent(int limit) {
    return repository.list(limit);
  }

  public CompletableFuture<Void> clear() {
    return repository.clear();
  }

  /** Called on the thread that recorded the entry; listeners move to the FX thread themselves. */
  public void subscribe(Consumer<Entry> listener) {
    listeners.add(listener);
  }
}
