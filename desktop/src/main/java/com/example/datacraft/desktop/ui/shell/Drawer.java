package com.example.datacraft.desktop.ui.shell;

import com.example.datacraft.desktop.ui.Motion;
import com.example.datacraft.desktop.ui.component.Controls;
import com.example.datacraft.desktop.ui.component.Icons;
import javafx.animation.Animation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * The side panel for triggering a run and reading a log: slides in from the right in 160 ms, closes
 * with Esc, the close button or a click beside it, and releases its content when closed.
 */
public final class Drawer extends StackPane {

  static final double WIDTH = 520;

  private final Region scrim = new Region();
  private final VBox panel = new VBox();
  private final Label title = Controls.label("", "section-title");
  private Runnable onClose = () -> {};
  private Node shownContent;
  private Animation motion;

  public Drawer() {
    scrim.getStyleClass().add("drawer-scrim");
    scrim.setOnMouseClicked(event -> close());
    // Blur only the stable surface, not its scrolling log or changing form on every frame.
    Region shadow = new Region();
    shadow.getStyleClass().add("drawer-shadow");
    shadow.setMouseTransparent(true);
    shadow.setMaxWidth(WIDTH);
    shadow.setPrefWidth(WIDTH);
    shadow.translateXProperty().bind(panel.translateXProperty());
    StackPane.setAlignment(shadow, Pos.CENTER_RIGHT);
    panel.getStyleClass().add("drawer");
    panel.setMaxWidth(WIDTH);
    panel.setPrefWidth(WIDTH);
    HBox header =
        Controls.row(
            8, title, Controls.grow(), Controls.iconButton(Icons.close(), "关闭", this::close));
    header.setAlignment(Pos.CENTER_LEFT);
    panel.getChildren().add(header);
    StackPane.setAlignment(panel, Pos.CENTER_RIGHT);
    getChildren().addAll(scrim, shadow, panel);
    setVisible(false);
    setOnKeyPressed(
        event -> {
          if (event.getCode() == KeyCode.ESCAPE) {
            close();
            event.consume();
          }
        });
  }

  /** Opens with this title and content; {@code closed} runs after it closes. */
  public void open(String heading, Node content, Runnable closed) {
    stopMotion();
    release();
    title.setText(heading);
    if (panel.getChildren().size() > 1) {
      panel.getChildren().remove(1, panel.getChildren().size());
    }
    VBox.setVgrow(content, Priority.ALWAYS);
    panel.getChildren().add(content);
    onClose = closed;
    shownContent = content;
    setVisible(true);
    panel.setTranslateX(0);
    motion = Motion.slideIn(panel, WIDTH);
    requestFocus();
  }

  public boolean isOpen() {
    return isVisible();
  }

  /** Whether this content still owns the panel, including while it slides in but not out. */
  public boolean isShowing(Node content) {
    return content != null && shownContent == content && isVisible();
  }

  private void stopMotion() {
    if (motion != null) {
      motion.stop();
      motion = null;
    }
  }

  /**
   * Runs the close callback of the content shown now, if any, without closing the panel: call it
   * before the panel is filled again, so that the content it replaces lets go of what it held.
   */
  public void release() {
    shownContent = null;
    Runnable closed = onClose;
    onClose = () -> {};
    closed.run();
  }

  public void close() {
    if (!isVisible()) {
      return;
    }
    stopMotion();
    shownContent = null;
    motion =
        Motion.slideOut(
            panel,
            WIDTH,
            () -> {
              setVisible(false);
              if (panel.getChildren().size() > 1) {
                panel.getChildren().remove(1, panel.getChildren().size());
              }
              release();
            });
  }
}
