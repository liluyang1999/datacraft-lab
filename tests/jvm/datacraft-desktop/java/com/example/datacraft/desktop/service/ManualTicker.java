package com.example.datacraft.desktop.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** A ticker that runs nothing until the test advances its time: polling tests never wait. */
public final class ManualTicker implements Ticker {

  private static final class Task implements Scheduled {
    final long due;
    final long order;
    final Runnable action;
    boolean cancelled;

    Task(long due, long order, Runnable action) {
      this.due = due;
      this.order = order;
      this.action = action;
    }

    @Override
    public void cancel() {
      cancelled = true;
    }
  }

  private final List<Task> tasks = new ArrayList<>();
  private long now;
  private long order;

  @Override
  public synchronized Scheduled schedule(Duration delay, Runnable action) {
    Task task = new Task(now + delay.toMillis(), order++, action);
    tasks.add(task);
    return task;
  }

  /** Moves time forward and runs every task that falls due, in order. */
  public void advance(Duration step) {
    long target;
    synchronized (this) {
      target = now + step.toMillis();
    }
    while (true) {
      Task next;
      synchronized (this) {
        tasks.removeIf(task -> task.cancelled);
        next =
            tasks.stream()
                .filter(task -> task.due <= target)
                .min(
                    Comparator.<Task>comparingLong(task -> task.due)
                        .thenComparingLong(task -> task.order))
                .orElse(null);
        if (next == null) {
          now = target;
          return;
        }
        tasks.remove(next);
        now = Math.max(now, next.due);
      }
      next.action.run();
    }
  }

  /** The delays of the tasks still waiting, from now. */
  public synchronized List<Duration> pending() {
    tasks.removeIf(task -> task.cancelled);
    return tasks.stream().map(task -> Duration.ofMillis(task.due - now)).sorted().toList();
  }

  @Override
  public void close() {}
}
