package com.example.datacraft.api;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What the server has run since it started: the runs in progress now, and how many runs started,
 * succeeded, failed or were turned away because the limit was reached. {@code GET /status} reports
 * it. Only job names and times are kept, never parameters, which may hold paths or secrets.
 *
 * <p>Thread-safe: every request thread records its own run; a snapshot is consistent per counter,
 * not across counters, which is enough for a monitor that polls.
 */
final class RunActivity {

  /** One run in progress. */
  record ActiveRun(String job, Instant startedAt) {}

  /** The counters and runs at one moment. */
  record Snapshot(
      Instant startedAt,
      Instant now,
      long started,
      long succeeded,
      long failed,
      long rejected,
      List<ActiveRun> active) {

    Snapshot {
      active = List.copyOf(active);
    }

    Duration uptime() {
      return Duration.between(startedAt, now);
    }
  }

  private final Clock clock;
  private final Instant startedAt;
  private final AtomicLong ids = new AtomicLong();
  private final Map<Long, ActiveRun> active = new ConcurrentHashMap<>();
  private final AtomicLong started = new AtomicLong();
  private final AtomicLong succeeded = new AtomicLong();
  private final AtomicLong failed = new AtomicLong();
  private final AtomicLong rejected = new AtomicLong();

  RunActivity(Clock clock) {
    this.clock = clock;
    this.startedAt = clock.instant();
  }

  /** A run starts; the returned id ends it. */
  long begin(String job) {
    long id = ids.incrementAndGet();
    active.put(id, new ActiveRun(job, clock.instant()));
    started.incrementAndGet();
    return id;
  }

  /** The run {@code id} ended; {@code success} when its result was SUCCEEDED. */
  void end(long id, boolean success) {
    if (active.remove(id) == null) {
      return; // ended twice: count it once
    }
    (success ? succeeded : failed).incrementAndGet();
  }

  /** A run request was refused with 503 because the limit was reached or the server closes. */
  void rejected() {
    rejected.incrementAndGet();
  }

  Snapshot snapshot() {
    List<ActiveRun> runs =
        active.values().stream()
            .sorted(Comparator.comparing(ActiveRun::startedAt).thenComparing(ActiveRun::job))
            .toList();
    return new Snapshot(
        startedAt,
        clock.instant(),
        started.get(),
        succeeded.get(),
        failed.get(),
        rejected.get(),
        runs);
  }
}
