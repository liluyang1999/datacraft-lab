package com.example.datacraft.desktop.ui.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.datacraft.desktop.app.Preferences.Bounds;
import java.util.List;
import java.util.Optional;
import javafx.geometry.Rectangle2D;
import org.junit.jupiter.api.Test;

class WindowPlacementTest {
  private static final Rectangle2D DESKTOP = new Rectangle2D(0, 0, 1920, 1040);

  @Test
  void firstOpenIsCenteredAndLeavesRoomForTheDesktop() {
    var bounds = place(null, DESKTOP);
    assertEquals(new Bounds(320, 120, 1280, 800, false), bounds);
  }

  @Test
  void laptopAndScaledWorkAreasKeepEveryEdgeAndTheTitleBarReachable() {
    for (var area :
        List.of(
            new Rectangle2D(0, 0, 1366, 728),
            new Rectangle2D(0, 0, 1280, 680),
            new Rectangle2D(0, 0, 1024, 680),
            new Rectangle2D(0, 24, 853, 456))) {
      var bounds = place(null, area);
      inside(bounds, area);
      assertTrue(bounds.width() <= 1280);
      assertTrue(bounds.height() <= 800);
    }
  }

  @Test
  void aSavedWindowStaysOnItsScreenAndItsValidSizeIsPreserved() {
    var secondary = new Rectangle2D(-1920, 40, 1920, 1040);
    var saved = new Bounds(-1800, 90, 1100, 720, false);
    assertEquals(
        saved,
        WindowPlacement.resolve(Optional.of(saved), List.of(DESKTOP, secondary), DESKTOP).bounds());
  }

  @Test
  void restoreUsesTheScreenWithTheLargestVisibleAreaAndClampsAllEdges() {
    var secondary = new Rectangle2D(1920, 0, 1280, 720);
    var saved = new Bounds(1850, -20, 1600, 900, true);
    var placement =
        WindowPlacement.resolve(Optional.of(saved), List.of(DESKTOP, secondary), DESKTOP);
    assertEquals(secondary, placement.area());
    inside(placement.bounds(), secondary);
    assertTrue(placement.bounds().maximized());
  }

  @Test
  void aDisconnectedMonitorFallsBackToCenteredPrimaryBounds() {
    assertEquals(
        new Bounds(320, 120, 1280, 800, true),
        place(new Bounds(8000, 2000, 3000, 2000, true), DESKTOP));
  }

  @Test
  void corruptSavedNumbersCannotReachTheWindowingSystem() {
    for (var saved :
        List.of(
            new Bounds(Double.NaN, 0, 1200, 800, false),
            new Bounds(0, Double.POSITIVE_INFINITY, 1200, 800, false),
            new Bounds(0, 0, -1, 800, false),
            new Bounds(0, 0, 1200, Double.NaN, false))) {
      assertEquals(place(null, DESKTOP), place(saved, DESKTOP));
    }
  }

  private static Bounds place(Bounds saved, Rectangle2D area) {
    return WindowPlacement.resolve(Optional.ofNullable(saved), List.of(area), area).bounds();
  }

  private static void inside(Bounds bounds, Rectangle2D area) {
    assertTrue(
        area.contains(bounds.x(), bounds.y(), bounds.width(), bounds.height()), bounds.toString());
  }
}
