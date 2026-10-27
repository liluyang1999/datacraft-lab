package com.example.datacraft.desktop.ui.component;

import com.example.datacraft.desktop.ui.Motion;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Circle;
import javafx.scene.shape.SVGPath;

/**
 * The outcome of a run as a mark in a circle: a tick for success, a cross for failure. When a new
 * result arrives the circle settles in and the mark draws itself (320 ms).
 */
public final class RunMark extends StackPane {

  private static final String TICK = "M12 22.6l6.6 6.6L30.4 15.2";
  private static final String CROSS = "M14 14l14 14 M28 14L14 28";
  private static final double PATH_LENGTH = 40;

  private final Circle circle = new Circle(21, 21, 19.5);
  private final SVGPath mark = new SVGPath();
  private String tone = "";

  public RunMark() {
    getStyleClass().add("run-mark");
    circle.getStyleClass().add("mark-circle");
    mark.getStyleClass().add("mark-path");
    Pane canvas = new Pane(circle, mark);
    canvas.setMinSize(42, 42);
    canvas.setPrefSize(42, 42);
    canvas.setMaxSize(42, 42);
    getChildren().add(canvas);
    setMinSize(42, 42);
    setPrefSize(42, 42);
    setMaxSize(42, 42);
    setMouseTransparent(true);
  }

  /** Shows a success or a failure; {@code animate} draws it in. */
  public void show(boolean success, boolean animate) {
    String next = success ? "success" : "failed";
    getStyleClass().removeAll(Tones.ALL);
    getStyleClass().add(next);
    tone = next;
    mark.setContent(success ? TICK : CROSS);
    mark.getStrokeDashArray().setAll(PATH_LENGTH, PATH_LENGTH);
    if (!animate || Motion.reduced() || getScene() == null) {
      mark.setStrokeDashOffset(0);
      setScaleX(1);
      setScaleY(1);
      return;
    }
    mark.setStrokeDashOffset(PATH_LENGTH);
    Timeline draw =
        new Timeline(
            new KeyFrame(
                javafx.util.Duration.millis(320),
                new KeyValue(mark.strokeDashOffsetProperty(), 0, Interpolator.EASE_OUT)));
    ScaleTransition settle = new ScaleTransition(javafx.util.Duration.millis(220), this);
    settle.setFromX(0.7);
    settle.setFromY(0.7);
    settle.setToX(1);
    settle.setToY(1);
    settle.setInterpolator(Interpolator.EASE_OUT);
    new ParallelTransition(settle, draw).play();
  }

  public String tone() {
    return tone;
  }
}
