package com.example.datacraft.desktop.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import javafx.application.Platform;

/**
 * Watches the FX thread for stalls: a background thread posts a ping every 100 ms; when one is not
 * answered within the threshold it samples the FX thread's stack, so the log names the code that
 * blocked the UI. Tests read {@link #stalls()} and fail on any.
 */
public final class FxWatchdog {

  /** A stall: how long the FX thread did not answer, and where it was. */
  public record Stall(long millis, List<StackTraceElement> where) {}

  private static final System.Logger LOG = System.getLogger(FxWatchdog.class.getName());
  static final long PERIOD_MS = 100;

  private final long thresholdMs;
  private final List<Stall> stalls = new ArrayList<>();
  private final AtomicLong answered = new AtomicLong();
  private volatile Thread fxThread;
  private volatile boolean running;
  private Thread watcher;

  public FxWatchdog(long thresholdMs) {
    this.thresholdMs = thresholdMs;
  }

  /** Starts watching; call on the FX thread. */
  public void start() {
    fxThread = Thread.currentThread();
    running = true;
    watcher = Thread.ofPlatform().daemon().name("datacraft-fx-watchdog").start(this::watch);
  }

  public void stop() {
    running = false;
    if (watcher != null) {
      watcher.interrupt();
    }
  }

  private void watch() {
    long sequence = 0;
    while (running) {
      long ping = ++sequence;
      long sent = System.nanoTime();
      Platform.runLater(() -> answered.set(ping));
      boolean reported = false;
      try {
        while (answered.get() < ping && running) {
          Thread.sleep(5);
          long waited = (System.nanoTime() - sent) / 1_000_000;
          // The watcher itself can be descheduled while sleeping. Recheck the answer before
          // blaming the FX thread for that delay (and sampling an unrelated later frame).
          if (!reported && running && answered.get() < ping && waited > thresholdMs) {
            reported = true;
            Thread thread = fxThread;
            List<StackTraceElement> where =
                thread == null ? List.of() : List.of(thread.getStackTrace());
            record(waited, where);
          }
        }
        Thread.sleep(PERIOD_MS);
      } catch (InterruptedException stopped) {
        return;
      }
    }
  }

  private void record(long waited, List<StackTraceElement> where) {
    // CSS and skin frames can hide the native accessibility caller in a short stack.
    Stall stall = new Stall(waited, where.subList(0, Math.min(48, where.size())));
    synchronized (stalls) {
      stalls.add(stall);
    }
    StringBuilder trace = new StringBuilder();
    stall.where().forEach(frame -> trace.append("\n    at ").append(frame));
    LOG.log(
        System.Logger.Level.WARNING,
        "The FX thread did not answer for more than " + thresholdMs + " ms" + trace);
  }

  public List<Stall> stalls() {
    synchronized (stalls) {
      return List.copyOf(stalls);
    }
  }

  public void clear() {
    synchronized (stalls) {
      stalls.clear();
    }
  }
}
