package com.example.datacraft.desktop.ui.shell;

import com.example.datacraft.desktop.ui.theme.ThemeManager;
import java.util.Optional;
import javafx.event.ActionEvent;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextInputDialog;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.stage.Window;

/** Confirmations and short text prompts, styled like the rest of the app. */
public final class Dialogs {

  private final ThemeManager theme;
  private Window owner;

  public Dialogs(ThemeManager theme) {
    this.theme = theme;
  }

  public void owner(Window window) {
    this.owner = window;
  }

  /** Shows complete diagnostic content on demand, with scrolling, selection and exact copying. */
  public void details(String title, String text) {
    String content = text == null ? "" : text;
    Dialog<Void> dialog = new Dialog<>();
    dialog.setTitle(title);
    dialog.setHeaderText(null);
    dialog.setResizable(true);
    TextArea area = new TextArea(content);
    area.setEditable(false);
    area.setWrapText(true);
    area.setAccessibleHelp(title);
    area.setPrefWidth(owner == null ? 560 : Math.max(280, Math.min(640, owner.getWidth() - 80)));
    area.setPrefHeight(owner == null ? 320 : Math.max(160, Math.min(420, owner.getHeight() - 180)));
    dialog.getDialogPane().setContent(area);
    ButtonType copy = new ButtonType("复制全文", ButtonBar.ButtonData.OTHER);
    dialog
        .getDialogPane()
        .getButtonTypes()
        .setAll(copy, new ButtonType("关闭", ButtonBar.ButtonData.CANCEL_CLOSE));
    dialog
        .getDialogPane()
        .lookupButton(copy)
        .addEventFilter(
            ActionEvent.ACTION,
            event -> {
              ClipboardContent clipboard = new ClipboardContent();
              clipboard.putString(content);
              Clipboard.getSystemClipboard().setContent(clipboard);
              event.consume();
            });
    style(dialog);
    dialog.show();
  }

  /** Asks before an action that cannot be undone; the action button names the action. */
  public boolean confirm(String title, String text, String action) {
    Alert alert = new Alert(Alert.AlertType.NONE);
    ButtonType confirm = new ButtonType(action, ButtonBar.ButtonData.OK_DONE);
    ButtonType cancel = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
    alert.getButtonTypes().setAll(cancel, confirm);
    alert.setTitle(title);
    alert.setHeaderText(null);
    alert.setContentText(text);
    style(alert);
    return alert.showAndWait().filter(confirm::equals).isPresent();
  }

  /** Asks for one line of text, e.g. a preset name. */
  public Optional<String> text(String title, String label, String initial) {
    TextInputDialog dialog = new TextInputDialog(initial);
    dialog.setTitle(title);
    dialog.setHeaderText(null);
    dialog.setGraphic(null);
    dialog.setContentText(label);
    dialog.getDialogPane().lookupButton(ButtonType.OK).setAccessibleText("保存");
    ((javafx.scene.control.Button) dialog.getDialogPane().lookupButton(ButtonType.OK))
        .setText("保存");
    ((javafx.scene.control.Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL))
        .setText("取消");
    style(dialog);
    return dialog.showAndWait().map(String::strip).filter(value -> !value.isEmpty());
  }

  private void style(Dialog<?> dialog) {
    if (owner != null) {
      dialog.initOwner(owner);
    }
    dialog.getDialogPane().getStylesheets().setAll(theme.stylesheets());
    theme.style(dialog.getDialogPane());
    dialog
        .getDialogPane()
        .lookupAll(".button")
        .forEach(
            button -> {
              if (button instanceof javafx.scene.control.Button b && b.isDefaultButton()) {
                b.getStyleClass().add("primary");
              }
            });
  }
}
