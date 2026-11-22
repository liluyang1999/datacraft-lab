package com.example.datacraft.desktop.ui.component;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.datacraft.desktop.service.BackendStatus.State;
import com.example.datacraft.desktop.service.ConnectionMonitor.Sample;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javafx.scene.shape.Rectangle;
import org.junit.jupiter.api.Test;

/** The monitor's two graphics, laid out without a window. */
class MeterAndSparklineTest {

  @Test
  void aSmallTotalIsDrawnCellByCellInThePartsOrder() {
    Meter meter = new Meter(10);
    meter.set(4, List.of(new Meter.Part(1, "running"), new Meter.Part(2, "waiting")));
    assertEquals(4, meter.shapeCount());
    assertEquals(
        List.of("running", "waiting", "waiting", "free"),
        List.of(meter.styleOf(0), meter.styleOf(1), meter.styleOf(2), meter.styleOf(3)));
    meter.resize(100, 10);
    meter.layout();
    Rectangle last = (Rectangle) meter.getChildrenUnmodifiable().getLast();
    assertEquals(100, last.getX() + last.getWidth(), 1e-6);
  }

  @Test
  void aLargeOrUnlimitedTotalIsOneBarWhoseFillsNeverOverflow() {
    Meter meter = new Meter(10);
    meter.set(128, List.of(new Meter.Part(64, "running"), new Meter.Part(200, "waiting")));
    assertEquals(3, meter.shapeCount());
    meter.resize(200, 10);
    meter.layout();
    double filled = 0;
    for (int index = 1; index < 3; index++) {
      filled += ((Rectangle) meter.getChildrenUnmodifiable().get(index)).getWidth();
    }
    assertEquals(200, filled, 1e-6); // over-full parts are scaled into the bar

    meter.set(-1, List.of(new Meter.Part(3, "running"), new Meter.Part(1, "deferred")));
    meter.resize(200, 10);
    meter.layout();
    assertEquals(150, ((Rectangle) meter.getChildrenUnmodifiable().get(1)).getWidth(), 1e-6);
  }

  @Test
  void missedChecksBreakTheLineAndLeaveMarks() {
    Instant at = Instant.parse("2026-11-20T10:00:00Z");
    List<Sample> samples = new ArrayList<>();
    for (int index = 0; index < 10; index++) {
      boolean missed = index == 4 || index == 5;
      samples.add(
          new Sample(
              at.plusSeconds(index * 5L),
              missed ? State.DOWN : State.UP,
              missed ? Optional.empty() : Optional.of(Duration.ofMillis(5 + index))));
    }
    Sparkline line = new Sparkline(20, 40);
    line.set(samples);
    line.resize(300, 40);
    line.layout();
    assertEquals(2, line.missCount());
    line.set(samples.subList(0, 4));
    line.layout();
    assertEquals(0, line.missCount());
    assertEquals(4, line.samples().size());
  }
}
