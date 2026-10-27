package com.example.datacraft.desktop.ui.component;

import com.example.datacraft.desktop.ui.component.Icons.Glyph;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * An empty screen as an invitation: a large glyph in a soft disc, a few words of why, and the
 * button that fixes it.
 */
public final class EmptyState extends VBox {

  private final StackPane art = new StackPane();
  private final Label text = new Label();
  private final Button action = new Button();

  public EmptyState() {
    getStyleClass().add("empty-state");
    art.getStyleClass().add("empty-art");
    text.setWrapText(true);
    action.getStyleClass().add("primary");
    getChildren().addAll(art, text, action);
  }

  /** Sets the glyph and the words; a null action hides the button. */
  public EmptyState set(Glyph glyph, String words, String actionText, Runnable run) {
    art.getChildren().setAll(Icons.of(glyph, 40));
    text.setText(words);
    boolean hasAction = actionText != null && run != null;
    action.setVisible(hasAction);
    action.setManaged(hasAction);
    if (hasAction) {
      action.setText(actionText);
      action.setGraphic(Icons.of(Glyph.PLUS));
      action.setOnAction(event -> run.run());
    }
    return this;
  }
}
