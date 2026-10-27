package com.example.datacraft.desktop.ui.component;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/** An empty screen as an invitation: one sentence of why, and the button that fixes it. */
public final class EmptyState extends VBox {

  private final Label text = new Label();
  private final Button action = new Button();

  public EmptyState() {
    getStyleClass().add("empty-state");
    text.setWrapText(true);
    action.getStyleClass().add("primary");
    getChildren().addAll(text, action);
  }

  /** Sets the sentence; a null action hides the button. */
  public EmptyState set(String sentence, String actionText, Runnable run) {
    text.setText(sentence);
    boolean hasAction = actionText != null && run != null;
    action.setVisible(hasAction);
    action.setManaged(hasAction);
    if (hasAction) {
      action.setText(actionText);
      action.setOnAction(event -> run.run());
    }
    return this;
  }
}
