package com.example.datacraft.desktop.service;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * When polls are due. One thread only says "now"; the work itself goes to the HTTP client's virtual
 * threads. Tests use a manual ticker instead, so no test waits for real time.
 */
public interface Ticker extends AutoCloseable {

  /** A scheduled task that can be cancelled. */
  interface Scheduled {
    void cancel();
  }

  Scheduled schedule(Duration delay, Runnable task);

  @Override
  void close();

  static Ticker system() {
    ScheduledExecutorService executor =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "datacraft-ticker");
              thread.setDaemon(true);
              return thread;
            });
    return new Ticker() {
      @Override
      public Scheduled schedule(Duration delay, Runnable task) {
        ScheduledFuture<?> future =
            executor.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS);
        return () -> future.cancel(false);
      }

      @Override
      public void close() {
        executor.shutdownNow();
      }
    };
  }
}
