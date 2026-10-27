package com.example.datacraft.desktop.ui.component;

import com.example.datacraft.desktop.ui.Motion;
import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.RotateTransition;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;

/**
 * A three-quarter ring that turns while something is under way: the one motion that loops, and only
 * between {@link #spin(boolean) spin(true)} and {@code spin(false)}. With reduced motion it stands
 * still.
 */
public final class BusyRing extends StackPane {

  private final RotateTransition turn;

  public BusyRing(double diameter) {
    getStyleClass().add("busy-ring");
    double radius = diameter / 2 - 1.5;
    Arc arc = new Arc(diameter / 2, diameter / 2, radius, radius, 90, -270);
    arc.setType(ArcType.OPEN);
    arc.getStyleClass().add("busy-arc");
    Pane canvas = new Pane(arc);
    canvas.setMinSize(diameter, diameter);
    canvas.setPrefSize(diameter, diameter);
    canvas.setMaxSize(diameter, diameter);
    getChildren().add(canvas);
    setMinSize(diameter, diameter);
    setPrefSize(diameter, diameter);
    setMaxSize(diameter, diameter);
    setMouseTransparent(true);
    turn = new RotateTransition(javafx.util.Duration.millis(900), canvas);
    turn.setByAngle(360);
    turn.setInterpolator(Interpolator.LINEAR);
    turn.setCycleCount(Animation.INDEFINITE);
  }

  public void spin(boolean on) {
    if (on && !Motion.reduced()) {
      turn.play();
    } else {
      turn.stop();
    }
  }

  public boolean spinning() {
    return turn.getStatus() == Animation.Status.RUNNING;
  }
}
