package com.example.datacraft.desktop.viewmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.datacraft.desktop.service.BackendStatus.State;
import com.example.datacraft.desktop.service.ConnectionMonitor.Sample;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LatencyStatsTest {

  private static final Instant AT = Instant.parse("2026-11-20T10:00:00Z");

  private static Sample answered(long millis) {
    return new Sample(AT, State.UP, Optional.of(Duration.ofMillis(millis)));
  }

  private static Sample missed() {
    return new Sample(AT, State.DOWN, Optional.empty());
  }

  @Test
  void noChecksMeanNoFigures() {
    assertEquals(LatencyStats.NONE, LatencyStats.of(List.of()));
    assertEquals(-1, LatencyStats.NONE.availability());
  }

  @Test
  void figuresComeFromTheAnsweredChecksAndAvailabilityFromAllOfThem() {
    LatencyStats stats =
        LatencyStats.of(List.of(answered(10), missed(), answered(30), answered(20), missed()));
    assertEquals(5, stats.checks());
    assertEquals(3, stats.reachable());
    assertEquals(Optional.of(Duration.ofMillis(20)), stats.latest());
    assertEquals(Optional.of(Duration.ofMillis(20)), stats.median());
    assertEquals(Optional.of(Duration.ofMillis(30)), stats.worst());
    assertEquals(0.6, stats.availability(), 1e-9);
  }

  @Test
  void anEvenCountTakesTheMeanOfTheMiddlePairAndADegradedAnswerCountsAsReachable() {
    Sample degraded = new Sample(AT, State.DEGRADED, Optional.of(Duration.ofMillis(40)));
    LatencyStats stats = LatencyStats.of(List.of(answered(10), degraded));
    assertEquals(Optional.of(Duration.ofMillis(25)), stats.median());
    assertEquals(1.0, stats.availability());
  }

  @Test
  void onlyMissedChecksHaveNoLatencyAndNoAvailability() {
    LatencyStats stats = LatencyStats.of(List.of(missed(), missed()));
    assertEquals(Optional.empty(), stats.latest());
    assertEquals(0.0, stats.availability());
  }
}
