package com.example.datacraft.desktop.util;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Hands values to the UI thread, keeping only the newest: a value offered while an older one is
 * still waiting replaces it, so the UI applies each kind of data at most once per pulse and a slow
 * frame never builds a queue.
 */
public final class Latest<T> {

  private final AtomicReference<T> pending = new AtomicReference<>();
  private final Executor ui;
  private final Consumer<T> apply;

  public Latest(Executor ui, Consumer<T> apply) {
    this.ui = ui;
    this.apply = apply;
  }

  public void offer(T value) {
    Objects.requireNonNull(value, "value");
    if (pending.getAndSet(value) == null) {
      ui.execute(
          () -> {
            T newest = pending.getAndSet(null);
            if (newest != null) {
              apply.accept(newest);
            }
          });
    }
  }
}
