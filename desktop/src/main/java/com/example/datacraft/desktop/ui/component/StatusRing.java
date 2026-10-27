package com.example.datacraft.desktop.ui.component;

import com.example.datacraft.desktop.ui.Motion;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.scene.Node;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Circle;

/**
 * A backend's state as a ring: the track is quiet, the arc carries the tone. Healthy is a full
 * ring, connecting a quarter, trouble a full ring in its colour, not configured the track alone.
 * When the state changes the arc sweeps to its new length in 420 ms; an optional glyph sits inside.
 */
public final class StatusRing extends StackPane {

  private final Circle track;
  private final Arc arc;
  private final Tooltip tooltip = new Tooltip();
  private Timeline sweep;
  private String tone = "";

  public StatusRing(double diameter, double stroke, Node glyph) {
    getStyleClass().add("status-ring");
    double radius = (diameter - stroke) / 2;
    double centre = diameter / 2;
    track = new Circle(centre, centre, radius);
    track.getStyleClass().add("ring-track");
    track.setStrokeWidth(stroke);
    arc = new Arc(centre, centre, radius, radius, 90, 0);
    arc.setType(ArcType.OPEN);
    arc.getStyleClass().addAll("ring-arc", "none");
    arc.setStrokeWidth(stroke);
    // A pane without layout keeps the arc on the track whatever its length.
    Pane canvas = new Pane(track, arc);
    canvas.setMinSize(diameter, diameter);
    canvas.setPrefSize(diameter, diameter);
    canvas.setMaxSize(diameter, diameter);
    canvas.setMouseTransparent(true);
    getChildren().add(canvas);
    if (glyph != null) {
      getChildren().add(glyph);
    }
    setMinSize(diameter, diameter);
    setPrefSize(diameter, diameter);
    setMaxSize(diameter, diameter);
    Tooltip.install(this, tooltip);
  }

  /** A small ring without a glyph, the size of a status dot. */
  public static StatusRing dot() {
    return new StatusRing(12, 2.4, null);
  }

  /** Sets the tone (a {@link Tones} class) and what the tooltip says about it. */
  public void set(String newTone, String description) {
    tooltip.setText(description);
    setAccessibleText(description);
    if (newTone.equals(tone)) {
      return;
    }
    boolean first = tone.isEmpty();
    arc.getStyleClass().removeAll(Tones.ALL);
    arc.getStyleClass().add(newTone);
    tone = newTone;
    double length = lengthOf(newTone);
    if (sweep != null) {
      sweep.stop();
    }
    if (first || Motion.reduced() || getScene() == null) {
      arc.setLength(length);
      return;
    }
    arc.setLength(0); // the new state draws itself from the top
    sweep =
        new Timeline(
            new KeyFrame(
                javafx.util.Duration.millis(420),
                new KeyValue(arc.lengthProperty(), length, Interpolator.EASE_OUT)));
    sweep.play();
  }

  /** The arc of a tone, in degrees; negative runs clockwise from the top. */
  static double lengthOf(String tone) {
    return switch (tone) {
      case "none" -> 0;
      case "waiting" -> -90;
      default -> -360;
    };
  }

  public String tone() {
    return tone;
  }
}
