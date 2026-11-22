package com.example.datacraft.desktop.ui.component;

import java.util.ArrayList;
import java.util.List;
import javafx.scene.layout.Region;
import javafx.scene.shape.Rectangle;

/**
 * A capacity as cells or a bar: how much of {@code total} each part takes (the engine's running
 * jobs of its limit, a pool's running and queued tasks of its slots). Up to {@link #MAX_CELLS} the
 * total is drawn as that many cells, each filled with the part it belongs to; above it, or when
 * unlimited, as one continuous bar. Parts are styled by CSS class ({@code .meter-cell.running}).
 */
public final class Meter extends Region {

  /** Totals up to this are drawn cell by cell. */
  public static final int MAX_CELLS = 16;

  static final double GAP = 3;

  /** One part: how many units and the CSS class of its fill. */
  public record Part(int units, String style) {
    public Part {
      units = Math.max(0, units);
    }
  }

  private List<Part> parts = List.of();
  private int total;
  private final List<Rectangle> shapes = new ArrayList<>();

  public Meter(double height) {
    getStyleClass().add("meter");
    setMinHeight(height);
    setPrefHeight(height);
    setMaxHeight(height);
    setMinWidth(48);
    setPrefWidth(160);
    rebuild(); // the empty track, until the first value arrives
  }

  /** Shows {@code parts} of {@code total}; a total of 0 or less draws only the track. */
  public void set(int newTotal, List<Part> newParts) {
    if (newTotal == total && newParts.equals(parts)) {
      return;
    }
    total = newTotal;
    parts = List.copyOf(newParts);
    rebuild();
    requestLayout();
  }

  private void rebuild() {
    getChildren().clear();
    shapes.clear();
    if (cells()) {
      for (int cell = 0; cell < total; cell++) {
        Rectangle rectangle = new Rectangle();
        rectangle.getStyleClass().addAll("meter-cell", styleOf(cell));
        shapes.add(rectangle);
      }
    } else {
      Rectangle track = new Rectangle();
      track.getStyleClass().addAll("meter-cell", "free");
      shapes.add(track);
      for (Part part : parts) {
        Rectangle fill = new Rectangle();
        fill.getStyleClass().addAll("meter-cell", part.style());
        shapes.add(fill);
      }
    }
    getChildren().addAll(shapes);
  }

  private boolean cells() {
    return total > 0 && total <= MAX_CELLS;
  }

  /** The style of cell {@code index}: the part it falls into, or free. */
  String styleOf(int index) {
    int end = 0;
    for (Part part : parts) {
      end += part.units();
      if (index < end) {
        return part.style();
      }
    }
    return "free";
  }

  @Override
  protected void layoutChildren() {
    double width = getWidth();
    double height = getHeight();
    double radius = Math.min(4, height / 2);
    if (shapes.isEmpty()) {
      return;
    }
    if (cells()) {
      double cell = (width - GAP * (total - 1)) / total;
      for (int index = 0; index < shapes.size(); index++) {
        Rectangle rectangle = shapes.get(index);
        rectangle.setX(index * (cell + GAP));
        rectangle.setY(0);
        rectangle.setWidth(Math.max(1, cell));
        rectangle.setHeight(height);
        rectangle.setArcWidth(radius);
        rectangle.setArcHeight(radius);
      }
      return;
    }
    Rectangle track = shapes.getFirst();
    track.setWidth(width);
    track.setHeight(height);
    track.setArcWidth(height);
    track.setArcHeight(height);
    int units = parts.stream().mapToInt(Part::units).sum();
    // Unlimited (total below 0): the parts share the bar among themselves.
    double scale = total > 0 ? (double) Math.min(units, total) / Math.max(units, 1) : 1;
    double denominator = total > 0 ? total : Math.max(units, 1);
    double x = 0;
    for (int index = 0; index < parts.size(); index++) {
      Rectangle fill = shapes.get(index + 1);
      double share = parts.get(index).units() * scale / denominator;
      fill.setX(x);
      fill.setWidth(Math.max(0, share * width));
      fill.setHeight(height);
      fill.setArcWidth(height);
      fill.setArcHeight(height);
      x += share * width;
    }
  }

  int shapeCount() {
    return shapes.size();
  }
}
