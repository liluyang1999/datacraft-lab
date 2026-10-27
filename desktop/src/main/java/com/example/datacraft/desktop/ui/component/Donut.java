package com.example.datacraft.desktop.ui.component;

import com.example.datacraft.desktop.ui.Motion;
import java.util.List;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.scene.Node;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Circle;

/**
 * A ring split by tone, clockwise from the top in the order given: how the runs on screen went. The
 * first data draws in over 520 ms; later changes move the slices at once. A node sits in the middle
 * (the success share).
 */
public final class Donut extends StackPane {

  private final Arc[] arcs;
  private final List<String> tones;
  private final DoubleProperty drawn = new SimpleDoubleProperty(1);
  private double[] shares;
  private boolean empty = true;

  public Donut(double diameter, double stroke, List<String> tones, Node centre) {
    getStyleClass().add("donut");
    this.tones = List.copyOf(tones);
    double radius = (diameter - stroke) / 2;
    double middle = diameter / 2;
    Circle track = new Circle(middle, middle, radius);
    track.getStyleClass().add("ring-track");
    track.setStrokeWidth(stroke);
    Pane canvas = new Pane(track);
    arcs = new Arc[tones.size()];
    shares = new double[tones.size()];
    for (int index = 0; index < arcs.length; index++) {
      Arc arc = new Arc(middle, middle, radius, radius, 90, 0);
      arc.setType(ArcType.OPEN);
      arc.getStyleClass().addAll("ring-arc", tones.get(index));
      arc.setStrokeWidth(stroke);
      arcs[index] = arc;
      canvas.getChildren().add(arc);
    }
    canvas.setMinSize(diameter, diameter);
    canvas.setPrefSize(diameter, diameter);
    canvas.setMaxSize(diameter, diameter);
    canvas.setMouseTransparent(true);
    getChildren().addAll(canvas, centre);
    setMinSize(diameter, diameter);
    setPrefSize(diameter, diameter);
    setMaxSize(diameter, diameter);
    drawn.addListener((property, before, now) -> draw());
  }

  /** Sets the counts, one per tone given to the constructor. */
  public void set(List<Integer> counts) {
    int total = counts.stream().mapToInt(Integer::intValue).sum();
    double[] next = new double[arcs.length];
    for (int index = 0; index < arcs.length; index++) {
      next[index] = total == 0 ? 0 : counts.get(index) / (double) total;
    }
    shares = next;
    boolean wasEmpty = empty;
    empty = total == 0;
    if (wasEmpty && !empty && !Motion.reduced() && getScene() != null) {
      drawn.set(0);
      new Timeline(
              new KeyFrame(
                  javafx.util.Duration.millis(520), new KeyValue(drawn, 1, Interpolator.EASE_OUT)))
          .play();
    } else {
      draw();
    }
  }

  private void draw() {
    double start = 90;
    double scale = drawn.get();
    for (int index = 0; index < arcs.length; index++) {
      double sweep = -360 * shares[index] * scale;
      arcs[index].setStartAngle(start);
      arcs[index].setLength(sweep);
      start += sweep;
    }
  }

  /** The tones in drawing order. */
  public List<String> tones() {
    return tones;
  }
}
