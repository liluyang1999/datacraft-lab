package com.example.datacraft.desktop.ui.component;

import com.example.datacraft.desktop.service.ConnectionMonitor.Sample;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import javafx.scene.layout.Region;
import javafx.scene.shape.Circle;
import javafx.scene.shape.ClosePath;
import javafx.scene.shape.Line;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;
import javafx.scene.shape.PathElement;
import javafx.scene.shape.Rectangle;

/**
 * A backend's recent checks as a line: one point per check, newest on the right, its height the
 * latency against the largest one shown. A check the backend did not answer breaks the line and
 * leaves a short mark on the baseline in the failure colour, so an outage reads as a gap. The line,
 * its soft fill and the marks are styled by CSS ({@code .spark-line}, {@code .spark-area}, {@code
 * .spark-miss}). Redrawn only when the samples or the width change.
 */
public final class Sparkline extends Region {

  static final double PADDING = 3;

  private final Line base = new Line();
  private final Path area = new Path();
  private final Path line = new Path();
  private final Circle latest = new Circle(2.6);
  private final List<Rectangle> misses = new ArrayList<>();
  private List<Sample> samples = List.of();
  private int capacity;
  private double drawnWidth = -1;
  private double drawnHeight = -1;

  /**
   * @param capacity how many checks the full width holds; fewer samples fill it from the right
   */
  public Sparkline(int capacity, double height) {
    this.capacity = Math.max(2, capacity);
    getStyleClass().add("sparkline");
    base.getStyleClass().add("spark-base");
    area.getStyleClass().add("spark-area");
    line.getStyleClass().add("spark-line");
    latest.getStyleClass().add("spark-dot");
    for (javafx.scene.Node part : List.of(base, area, line, latest)) {
      part.setMouseTransparent(true);
    }
    latest.setVisible(false);
    getChildren().addAll(base, area, line, latest);
    setMinHeight(height);
    setPrefHeight(height);
    setMaxHeight(height);
    setMinWidth(80);
    setPrefWidth(320);
  }

  /** Shows these samples, oldest first. */
  public void set(List<Sample> next) {
    if (next.equals(samples)) {
      return;
    }
    samples =
        List.copyOf(
            next.size() > capacity ? next.subList(next.size() - capacity, next.size()) : next);
    drawnWidth = -1;
    requestLayout();
  }

  public List<Sample> samples() {
    return samples;
  }

  @Override
  protected void layoutChildren() {
    double width = getWidth();
    double height = getHeight();
    if (width == drawnWidth && height == drawnHeight) {
      return;
    }
    drawnWidth = width;
    drawnHeight = height;
    draw(width, height);
  }

  private void draw(double width, double height) {
    getChildren().removeAll(misses);
    misses.clear();
    List<PathElement> lineParts = new ArrayList<>();
    List<PathElement> areaParts = new ArrayList<>();
    double top = PADDING;
    double bottom = height - PADDING;
    double step = (width - 2 * PADDING) / (capacity - 1);
    base.setStartX(PADDING);
    base.setEndX(width - PADDING);
    base.setStartY(bottom + 0.5);
    base.setEndY(bottom + 0.5);
    long worst = 1;
    for (Sample sample : samples) {
      worst = Math.max(worst, sample.latency().map(Duration::toNanos).orElse(0L));
    }
    // Headroom above the slowest check, so a steady latency does not hug the top edge.
    double ceiling = worst * 1.25;
    latest.setVisible(false);
    int offset = capacity - samples.size();
    boolean open = false;
    double runStart = 0;
    double lastX = 0;
    for (int index = 0; index < samples.size(); index++) {
      Sample sample = samples.get(index);
      double x = PADDING + (offset + index) * step;
      if (!sample.reachable() || sample.latency().isEmpty()) {
        if (open) {
          closeArea(areaParts, runStart, lastX, bottom);
          open = false;
        }
        Rectangle miss = new Rectangle(x - 1.5, bottom - 6, 3, 6);
        miss.getStyleClass().add("spark-miss");
        miss.setMouseTransparent(true);
        misses.add(miss);
        continue;
      }
      double share = sample.latency().get().toNanos() / ceiling;
      // At least a sliver above the baseline, so a fast check is still visible.
      double y = bottom - Math.max(1.5, share * (bottom - top));
      if (!open) {
        lineParts.add(new MoveTo(x, y));
        areaParts.add(new MoveTo(x, bottom));
        areaParts.add(new LineTo(x, y));
        runStart = x;
        open = true;
      } else {
        lineParts.add(new LineTo(x, y));
        areaParts.add(new LineTo(x, y));
      }
      lastX = x;
      latest.setCenterX(x);
      latest.setCenterY(y);
      latest.setVisible(index == samples.size() - 1);
    }
    if (open) {
      closeArea(areaParts, runStart, lastX, bottom);
    }
    line.getElements().setAll(lineParts);
    area.getElements().setAll(areaParts);
    getChildren().addAll(misses);
  }

  private static void closeArea(List<PathElement> parts, double start, double end, double bottom) {
    parts.add(new LineTo(end, bottom));
    parts.add(new LineTo(start, bottom));
    parts.add(new ClosePath());
  }

  /** The marks of unanswered checks now drawn (for tests). */
  int missCount() {
    return misses.size();
  }
}
