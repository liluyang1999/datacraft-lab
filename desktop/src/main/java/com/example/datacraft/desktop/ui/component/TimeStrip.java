package com.example.datacraft.desktop.ui.component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Region;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;

/**
 * Recent runs on a time line: one lane per kind (pipelines above, jobs below, each marked by its
 * glyph), a dot per run where it started, from the oldest shown on the left to now on the right. A
 * dot's colour is its outcome; hovering tells the rest, a click opens it.
 */
public final class TimeStrip extends Region {

  /** One run: a key, its tone class, when it started, its tooltip, and its lane (0 or 1). */
  public record Mark(String key, String tone, Instant at, String description, int lane) {}

  static final double LANE_HEIGHT = 26;
  static final double LEFT = 32;
  static final double DOT = 5.5;

  private final Line[] lanes = {new Line(), new Line()};
  private final Node[] glyphs;
  private final Label start = new Label();
  private final Label end = new Label("现在");
  private final List<Circle> dots = new ArrayList<>();
  private List<Mark> marks = List.of();
  private Instant from = Instant.EPOCH;
  private Instant now = Instant.EPOCH;
  private Consumer<Mark> onPick = mark -> {};

  public TimeStrip(Node upperGlyph, Node lowerGlyph) {
    getStyleClass().add("time-strip");
    glyphs = new Node[] {upperGlyph, lowerGlyph};
    for (Line lane : lanes) {
      lane.getStyleClass().add("strip-lane");
      lane.setManaged(false);
    }
    start.getStyleClass().add("caption");
    end.getStyleClass().add("caption");
    getChildren().addAll(lanes);
    getChildren().addAll(glyphs);
    getChildren().addAll(start, end);
    setMinHeight(LANE_HEIGHT * 2 + 22);
    setPrefHeight(LANE_HEIGHT * 2 + 22);
  }

  public TimeStrip onPick(Consumer<Mark> pick) {
    onPick = pick;
    return this;
  }

  /** Shows the runs; the left end of the line says {@code oldestText}, the right end "现在". */
  public void set(List<Mark> next, Instant current, String oldestText) {
    marks = List.copyOf(next);
    now = current;
    from = marks.stream().map(Mark::at).min(Instant::compareTo).orElse(current);
    start.setText(oldestText);
    while (dots.size() > marks.size()) {
      getChildren().remove(dots.removeLast());
    }
    while (dots.size() < marks.size()) {
      Circle dot = new Circle(DOT);
      dot.setManaged(false);
      dot.getStyleClass().addAll("strip-dot", "none");
      Tooltip tip = new Tooltip();
      tip.setShowDelay(javafx.util.Duration.millis(120));
      Tooltip.install(dot, tip);
      dot.setUserData(tip);
      int index = dots.size();
      dot.setOnMouseClicked(
          event -> {
            if (event.getButton() == MouseButton.PRIMARY && index < marks.size()) {
              onPick.accept(marks.get(index));
            }
          });
      dots.add(dot);
      getChildren().add(dot);
    }
    for (int index = 0; index < marks.size(); index++) {
      Mark mark = marks.get(index);
      Circle dot = dots.get(index);
      dot.getStyleClass().removeAll(Tones.ALL);
      dot.getStyleClass().add(mark.tone());
      ((Tooltip) dot.getUserData()).setText(mark.description());
      dot.setAccessibleText(mark.description());
    }
    requestLayout();
  }

  /** How many runs are on the line. */
  public int count() {
    return marks.size();
  }

  @Override
  protected void layoutChildren() {
    double width = getWidth();
    double right = width - 8;
    for (int lane = 0; lane < 2; lane++) {
      double y = LANE_HEIGHT / 2 + lane * LANE_HEIGHT;
      lanes[lane].setStartX(LEFT);
      lanes[lane].setEndX(right);
      lanes[lane].setStartY(y);
      lanes[lane].setEndY(y);
      Node glyph = glyphs[lane];
      glyph.autosize();
      glyph.relocate(0, y - glyph.getLayoutBounds().getHeight() / 2);
    }
    long span = Math.max(1, now.toEpochMilli() - from.toEpochMilli());
    for (int index = 0; index < marks.size(); index++) {
      Mark mark = marks.get(index);
      double share = (mark.at().toEpochMilli() - from.toEpochMilli()) / (double) span;
      double x = LEFT + DOT + Math.max(0, Math.min(1, share)) * (right - LEFT - DOT * 2);
      Circle dot = dots.get(index);
      dot.setCenterX(x);
      dot.setCenterY(LANE_HEIGHT / 2 + Math.min(1, Math.max(0, mark.lane())) * LANE_HEIGHT);
    }
    double labelY = LANE_HEIGHT * 2 + 2;
    start.autosize();
    end.autosize();
    start.relocate(LEFT, labelY);
    end.relocate(right - end.getWidth(), labelY);
  }
}
