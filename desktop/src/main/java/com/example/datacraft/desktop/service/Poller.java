package com.example.datacraft.desktop.service;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Fetches one kind of data again and again: at {@code interval} while it works, and after a failure
 * at 1x, 2x, 4x the base, at most {@link #MAX_BACKOFF}. Only one request is in flight at a time; a
 * tick that finds the previous one still running is skipped instead of queued. Results of a request
 * started before {@link #stop} or {@link #restart} are dropped.
 */
public final class Poller<T> {

  public static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

  private final Ticker ticker;
  private final Supplier<CompletableFuture<T>> fetch;
  private final Consumer<T> onResult;
  private final Consumer<Throwable> onFailure;
  private final Supplier<Duration> interval;

  private final Object lock = new Object();
  private long generation;
  private boolean running;
  private boolean inFlight;
  private int failures;
  private Ticker.Scheduled next;

  public Poller(
      Ticker ticker,
      Supplier<CompletableFuture<T>> fetch,
      Consumer<T> onResult,
      Consumer<Throwable> onFailure,
      Supplier<Duration> interval) {
    this.ticker = ticker;
    this.fetch = fetch;
    this.onResult = onResult;
    this.onFailure = onFailure;
    this.interval = interval;
  }

  /** Starts polling now; does nothing when already running. */
  public void start() {
    synchronized (lock) {
      if (running) {
        return;
      }
      running = true;
      generation++;
      schedule(Duration.ZERO);
    }
  }

  /** Stops polling; a request in flight completes, but its result is dropped. */
  public void stop() {
    synchronized (lock) {
      running = false;
      generation++;
      inFlight = false;
      if (next != null) {
        next.cancel();
        next = null;
      }
    }
  }

  /** Forgets failures and fetches again now (e.g. after the profile changed). */
  public void restart() {
    stop();
    synchronized (lock) {
      failures = 0;
    }
    start();
  }

  /** Fetches now, unless a request is already in flight. */
  public void refresh() {
    synchronized (lock) {
      if (running && !inFlight) {
        if (next != null) {
          next.cancel();
        }
        schedule(Duration.ZERO);
      }
    }
  }

  public boolean running() {
    synchronized (lock) {
      return running;
    }
  }

  /** The delay after {@code failures} consecutive failures for a given base interval. */
  public static Duration backoff(Duration base, int failures) {
    if (failures <= 0) {
      return base;
    }
    long factor = 1L << Math.min(failures - 1, 10);
    Duration delay = base.multipliedBy(factor);
    return delay.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : delay;
  }

  private void schedule(Duration delay) {
    long current = generation;
    next = ticker.schedule(delay, () -> tick(current));
  }

  private void tick(long scheduledFor) {
    CompletableFuture<T> request;
    synchronized (lock) {
      if (!running || scheduledFor != generation || inFlight) {
        return;
      }
      inFlight = true;
      try {
        request = fetch.get();
      } catch (RuntimeException failure) {
        request = CompletableFuture.failedFuture(failure);
      }
    }
    request.whenComplete(
        (value, failure) -> {
          Duration delay;
          synchronized (lock) {
            if (!running || scheduledFor != generation) {
              return;
            }
            inFlight = false;
            failures = failure == null ? 0 : failures + 1;
            delay = backoff(interval.get(), failures);
            schedule(delay);
          }
          if (failure == null) {
            onResult.accept(value);
          } else {
            onFailure.accept(failure);
          }
        });
  }
}
