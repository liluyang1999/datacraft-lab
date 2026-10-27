package com.example.datacraft.desktop.ui.shell;

import com.example.datacraft.desktop.ui.Motion;
import com.example.datacraft.desktop.ui.component.Controls;
import com.example.datacraft.desktop.ui.component.Icons;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * Notifications in the bottom right corner: at most three at a time, gone after 4 s, except
 * failures, which stay until closed. The layer lets clicks through where there is no toast.
 */
public final class Toasts extends VBox {

  static final int MAX = 3;
  static final Duration LIFETIME = Duration.seconds(4);

  public Toasts() {
    setSpacing(8);
    setAlignment(Pos.BOTTOM_RIGHT);
    setPadding(new Insets(0, 24, 24, 0));
    setPickOnBounds(false);
    setMaxSize(USE_PREF_SIZE, USE_PREF_SIZE);
  }

  public void success(String text) {
    show("success", text, false);
  }

  public void info(String text) {
    show("running", text, false);
  }

  public void warning(String text) {
    show("warning", text, false);
  }

  public void failure(String text) {
    show("failed", text, true);
  }

  private void show(String tone, String text, boolean sticky) {
    javafx.scene.layout.StackPane glyph =
        new javafx.scene.layout.StackPane(
            Icons.of(
                switch (tone) {
                  case "success" -> Icons.Glyph.CHECK;
                  case "failed" -> Icons.Glyph.CROSS;
                  default -> Icons.Glyph.INFO;
                },
                18));
    glyph.getStyleClass().addAll("toast-glyph", tone);
    Label message = Controls.label(text, "text");
    message.setWrapText(true);
    message.setMaxWidth(300);
    HBox.setHgrow(message, Priority.ALWAYS);
    HBox toast = new HBox(glyph, message);
    toast.getStyleClass().add("toast");
    toast.setAccessibleText(text);
    Button close = Controls.iconButton(Icons.close(), "关闭通知", () -> dismiss(toast));
    toast.getChildren().add(close);
    while (getChildren().size() >= MAX) {
      getChildren().removeFirst();
    }
    getChildren().add(toast);
    Motion.rise(toast);
    if (!sticky) {
      PauseTransition life = new PauseTransition(LIFETIME);
      life.setOnFinished(event -> dismiss(toast));
      // Keep a toast while the pointer is on it.
      toast
          .hoverProperty()
          .addListener(
              (property, before, hover) -> {
                if (hover) {
                  life.stop();
                } else {
                  life.playFromStart();
                }
              });
      life.play();
    }
  }

  private void dismiss(HBox toast) {
    if (getChildren().contains(toast)) {
      Motion.fadeOut(toast, () -> getChildren().remove(toast));
    }
  }
}
