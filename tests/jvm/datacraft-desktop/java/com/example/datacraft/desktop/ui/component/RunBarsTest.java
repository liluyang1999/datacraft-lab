package com.example.datacraft.desktop.ui.component;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.datacraft.desktop.api.RunTone;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RunBarsTest {
  @Test
  void negativeDurationsRemainFiniteAndDoNotDrawBelowTheBaseline() {
    var segment =
        new RunBars.Segment(RunTone.SUCCESS, Optional.of(Duration.ofSeconds(-1)), "", "a");
    double height = RunBars.heightOf(segment, 1000, 48);
    assertTrue(Double.isFinite(height));
    assertEquals(8, height);
  }

  @Test
  void durationsBeyondLongMillisecondsStillFitTheChart() {
    var segment =
        new RunBars.Segment(
            RunTone.SUCCESS, Optional.of(Duration.ofSeconds(Long.MAX_VALUE)), "", "a");
    double height = RunBars.heightOf(segment, Double.MAX_VALUE, 48);
    assertTrue(Double.isFinite(height));
    assertTrue(height >= 8 && height <= 48);
  }
}
