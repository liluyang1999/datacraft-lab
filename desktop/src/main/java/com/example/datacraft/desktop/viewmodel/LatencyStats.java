package com.example.datacraft.desktop.viewmodel;

import com.example.datacraft.desktop.service.ConnectionMonitor.Sample;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * What the recent checks of one backend say: how many answered, and the latest, typical and worst
 * latency of those that did. Computed from the samples alone, so the same history always gives the
 * same figures.
 *
 * @param checks samples considered, at most {@code ConnectionMonitor.HISTORY}
 * @param reachable samples where the backend answered (healthy or degraded)
 * @param latest the newest answered latency
 * @param median the median of the answered latencies
 * @param worst the largest answered latency
 */
public record LatencyStats(
    int checks,
    int reachable,
    Optional<Duration> latest,
    Optional<Duration> median,
    Optional<Duration> worst) {

  public static final LatencyStats NONE =
      new LatencyStats(0, 0, Optional.empty(), Optional.empty(), Optional.empty());

  public static LatencyStats of(List<Sample> samples) {
    if (samples.isEmpty()) {
      return NONE;
    }
    List<Duration> answered =
        samples.stream().flatMap(sample -> sample.latency().stream()).toList();
    int reachable = (int) samples.stream().filter(Sample::reachable).count();
    if (answered.isEmpty()) {
      return new LatencyStats(
          samples.size(), reachable, Optional.empty(), Optional.empty(), Optional.empty());
    }
    List<Duration> sorted = answered.stream().sorted().toList();
    Duration median =
        sorted.size() % 2 == 1
            ? sorted.get(sorted.size() / 2)
            : sorted.get(sorted.size() / 2 - 1).plus(sorted.get(sorted.size() / 2)).dividedBy(2);
    return new LatencyStats(
        samples.size(),
        reachable,
        Optional.of(answered.getLast()),
        Optional.of(median),
        Optional.of(sorted.getLast()));
  }

  /** The share of checks the backend answered, 0 to 1, or -1 when there was none. */
  public double availability() {
    return checks == 0 ? -1 : (double) reachable / checks;
  }
}
