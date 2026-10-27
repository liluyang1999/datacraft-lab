package com.example.datacraft.desktop.ui.component;

import com.example.datacraft.desktop.ui.Motion;
import javafx.animation.FillTransition;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Paint;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

/** An 8 px status dot with a word next to it; the dot's colour fades in 200 ms when it changes. */
public final class StatusBadge extends HBox {

  private final Circle dot = new Circle(4);
  private final Label text = new Label();
  private String tone = "";

  public StatusBadge() {
    getStyleClass().add("status-badge");
    dot.getStyleClass().addAll("status-dot", "none");
    getChildren().addAll(dot, text);
  }

  /** Sets the tone (a {@link Tones} class) and the word. */
  public void set(String newTone, String word) {
    text.setText(word);
    if (newTone.equals(tone)) {
      return;
    }
    Paint before = dot.getFill();
    dot.getStyleClass().removeAll(Tones.ALL);
    dot.getStyleClass().add(newTone);
    boolean animate = !tone.isEmpty() && !Motion.reduced() && getScene() != null;
    tone = newTone;
    if (animate) {
      // Read the new colour from CSS, then fade to it.
      dot.applyCss();
      Paint after = dot.getFill();
      if (before instanceof javafx.scene.paint.Color from
          && after instanceof javafx.scene.paint.Color to
          && !from.equals(to)) {
        FillTransition fade = new FillTransition(Duration.millis(200), dot, from, to);
        fade.setOnFinished(event -> dot.setFill(to));
        fade.play();
      }
    }
  }

  public Label text() {
    return text;
  }

  public String tone() {
    return tone;
  }
}
