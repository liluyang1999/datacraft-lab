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
  private final List<Runnable> clearListeners = new CopyOnWriteArrayList<>();

  public ActivityLog(ActivityRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  public void record(Level level, Source source, String text) {
    record(level, source, text, "");
  }

  public synchronized void record(Level level, Source source, String text, String detail) {
    Entry entry = new Entry(clock.instant(), level, source, text, detail == null ? "" : detail);
    repository
        .add(entry)
        .thenRun(() -> listeners.forEach(listener -> listener.accept(entry)))
        .exceptionally(
            failure -> {
              LOG.log(System.Logger.Level.WARNING, "An activity entry was not stored", failure);
              return null;
            });
  }

  public CompletableFuture<List<Entry>> recent(int limit) {
    return repository.list(limit);
  }

  public synchronized CompletableFuture<Void> clear() {
    return repository.clear().thenRun(() -> clearListeners.forEach(Runnable::run));
  }

  /**
   * Committed mutations are delivered in store order; listeners move to the FX thread themselves.
   */
  public void subscribe(Consumer<Entry> listener) {
    listeners.add(listener);
  }

  public void onCleared(Runnable listener) {
    clearListeners.add(listener);
  }
}
