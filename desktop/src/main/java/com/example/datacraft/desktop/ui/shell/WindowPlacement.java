package com.example.datacraft.desktop.ui.shell;

import com.example.datacraft.desktop.app.Preferences.Bounds;
import java.util.List;
import java.util.Optional;
import javafx.geometry.Rectangle2D;

/** Window geometry in JavaFX logical coordinates, already adjusted for each screen's scale. */
final class WindowPlacement {
  static final double MIN_WIDTH = 960;
  static final double MIN_HEIGHT = 640;
  private static final double PREFERRED_WIDTH = 1280;
  private static final double PREFERRED_HEIGHT = 800;

  record Placement(Rectangle2D area, Bounds bounds) {}

  private WindowPlacement() {}

  static Placement resolve(Optional<Bounds> saved, List<Rectangle2D> screens, Rectangle2D primary) {
    Bounds previous = saved.filter(WindowPlacement::valid).orElse(null);
    Rectangle2D area = primary;
    double largest = 0;
    if (previous != null) {
      for (Rectangle2D screen : screens) {
        double overlap = overlap(previous, screen);
        if (overlap > largest) {
          largest = overlap;
          area = screen;
        }
      }
    }
    if (previous == null || largest == 0) {
      double width = initial(PREFERRED_WIDTH, MIN_WIDTH, area.getWidth());
      double height = initial(PREFERRED_HEIGHT, MIN_HEIGHT, area.getHeight());
      return new Placement(
          area,
          new Bounds(
              area.getMinX() + (area.getWidth() - width) / 2,
              area.getMinY() + (area.getHeight() - height) / 2,
              width,
              height,
              previous != null && previous.maximized()));
    }
    double width =
        Math.clamp(previous.width(), Math.min(MIN_WIDTH, area.getWidth()), area.getWidth());
    double height =
        Math.clamp(previous.height(), Math.min(MIN_HEIGHT, area.getHeight()), area.getHeight());
    return new Placement(
        area,
        new Bounds(
            Math.clamp(previous.x(), area.getMinX(), area.getMaxX() - width),
            Math.clamp(previous.y(), area.getMinY(), area.getMaxY() - height),
            width,
            height,
            previous.maximized()));
  }

  private static double initial(double preferred, double minimum, double available) {
    return Math.min(available, Math.max(minimum, Math.min(preferred, Math.floor(available * 0.9))));
  }

  private static boolean valid(Bounds bounds) {
    return Double.isFinite(bounds.x())
        && Double.isFinite(bounds.y())
        && Double.isFinite(bounds.width())
        && Double.isFinite(bounds.height())
        && bounds.width() > 0
        && bounds.height() > 0;
  }

  private static double overlap(Bounds bounds, Rectangle2D area) {
    return Math.max(
            0,
            Math.min(bounds.x() + bounds.width(), area.getMaxX())
                - Math.max(bounds.x(), area.getMinX()))
        * Math.max(
            0,
            Math.min(bounds.y() + bounds.height(), area.getMaxY())
                - Math.max(bounds.y(), area.getMinY()));
  }
}
