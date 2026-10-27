package com.example.datacraft.desktop.viewmodel;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Stands in for the FX thread in view-model tests: work handed to the "UI" waits in a queue and
 * runs on the test thread when the test drains it, so a test decides exactly what the UI has seen.
 */
final class UiQueue implements Executor {

  private final BlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();

  @Override
  public void execute(Runnable task) {
    tasks.add(task);
  }

  /** How much work waits for the UI. */
  int pending() {
    return tasks.size();
  }

  /** Runs what is queued now, without waiting for more. */
  void drain() {
    Runnable task;
    while ((task = tasks.poll()) != null) {
      task.run();
    }
  }

  /** Runs queued work as it arrives until the condition holds; fails after ten seconds. */
  void until(String what, Callable<Boolean> condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (true) {
      drain();
      if (Boolean.TRUE.equals(condition.call())) {
        return;
      }
      long left = deadline - System.nanoTime();
      if (left <= 0) {
        throw new AssertionError("timed out waiting until " + what);
      }
      Runnable task =
          tasks.poll(Math.min(left, TimeUnit.MILLISECONDS.toNanos(50)), TimeUnit.NANOSECONDS);
      if (task != null) {
        task.run();
      }
    }
  }
}
