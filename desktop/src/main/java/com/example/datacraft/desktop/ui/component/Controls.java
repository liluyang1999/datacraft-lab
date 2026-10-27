package com.example.datacraft.desktop.ui.component;

import java.util.List;
import java.util.function.Consumer;
import javafx.beans.property.ObjectProperty;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;

/** Small building blocks shared by the views. */
public final class Controls {

  private Controls() {}

  public static Label label(String text, String... styleClasses) {
    Label label = new Label(text);
    label.getStyleClass().addAll(styleClasses);
    return label;
  }

  public static Button button(String text, Runnable action, String... styleClasses) {
    Button button = new Button(text);
    button.getStyleClass().addAll(styleClasses);
    button.setOnAction(event -> action.run());
    return button;
  }

  /** A quiet icon button with a tooltip naming its action. */
  public static Button iconButton(Node icon, String action, Runnable run) {
    Button button = new Button();
    button.setGraphic(icon);
    button.getStyleClass().add("quiet");
    button.setTooltip(new Tooltip(action));
    button.setAccessibleText(action);
    button.setOnAction(event -> run.run());
    return button;
  }

  public static Region grow() {
    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    VBox.setVgrow(spacer, Priority.ALWAYS);
    return spacer;
  }

  public static Region rule() {
    Region rule = new Region();
    rule.getStyleClass().add("rule");
    return rule;
  }

  public static HBox row(double spacing, Node... children) {
    HBox row = new HBox(spacing, children);
    row.setAlignment(Pos.CENTER_LEFT);
    return row;
  }

  /**
   * A small "i" that explains on hover: the place for what used to be a sentence under a control.
   */
  public static Label info(String explanation) {
    Label info = new Label();
    info.setGraphic(Icons.of(Icons.Glyph.INFO, 16));
    info.getStyleClass().add("info");
    Tooltip tip = new Tooltip(explanation);
    tip.setWrapText(true);
    tip.setMaxWidth(320);
    tip.setShowDelay(javafx.util.Duration.millis(150));
    info.setTooltip(tip);
    info.setAccessibleText(explanation);
    return info;
  }

  /**
   * Makes a graphic act like a button: a primary click or, once it has the focus (Tab), Enter or
   * Space runs {@code action}.
   */
  public static void activatable(Node node, Runnable action) {
    node.setFocusTraversable(true);
    node.addEventHandler(
        javafx.scene.input.MouseEvent.MOUSE_CLICKED,
        event -> {
          if (event.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
            node.requestFocus();
            action.run();
          }
        });
    node.addEventHandler(
        javafx.scene.input.KeyEvent.KEY_PRESSED,
        event -> {
          if (event.getCode() == javafx.scene.input.KeyCode.ENTER
              || event.getCode() == javafx.scene.input.KeyCode.SPACE) {
            action.run();
            event.consume();
          }
        });
  }

  /** A glyph with a word or two: how the views label a fact without a sentence. */
  public static Label chip(Icons.Glyph glyph, String text) {
    Label chip = new Label(text);
    chip.setGraphic(Icons.of(glyph, 16));
    chip.getStyleClass().add("chip");
    return chip;
  }

  /** A segmented choice with a glyph on each option. */
  public static <T> HBox segmented(
      List<T> options, List<String> labels, List<Icons.Glyph> glyphs, ObjectProperty<T> value) {
    HBox box = segmented(options, labels, value);
    for (int index = 0; index < glyphs.size(); index++) {
      ((ToggleButton) box.getChildren().get(index)).setGraphic(Icons.of(glyphs.get(index), 16));
    }
    return box;
  }

  /** A segmented choice bound to a property; one button per option. */
  public static <T> HBox segmented(List<T> options, List<String> labels, ObjectProperty<T> value) {
    HBox box = new HBox();
    box.getStyleClass().add("segmented");
    ToggleGroup group = new ToggleGroup();
    for (int index = 0; index < options.size(); index++) {
      T option = options.get(index);
      ToggleButton button = new ToggleButton(labels.get(index));
      button.setToggleGroup(group);
      button.setUserData(option);
      if (index == 0) {
        button.getStyleClass().add("first");
      }
      if (index == options.size() - 1) {
        button.getStyleClass().add("last");
      }
      button.setSelected(option.equals(value.get()));
      box.getChildren().add(button);
    }
    group
        .selectedToggleProperty()
        .addListener(
            (property, before, selected) -> {
              if (selected == null) {
                before.setSelected(true); // one option is always chosen
              } else {
                @SuppressWarnings("unchecked")
                T option = (T) selected.getUserData();
                value.set(option);
              }
            });
    value.addListener(
        (property, before, now) ->
            group
                .getToggles()
                .forEach(toggle -> toggle.setSelected(now.equals(toggle.getUserData()))));
    return box;
  }

  /** A small on/off switch; {@code changed} receives the new state from the user. */
  public static ToggleButton toggleSwitch(String accessibleText, Consumer<Boolean> changed) {
    ToggleButton toggle = new ToggleButton();
    toggle.getStyleClass().setAll("switch");
    toggle.setAccessibleText(accessibleText);
    Circle thumb = new Circle(6);
    thumb.getStyleClass().add("thumb");
    StackPane track = new StackPane(thumb);
    track.setMouseTransparent(true);
    track.setPrefSize(34, 18);
    StackPane.setAlignment(thumb, Pos.CENTER_LEFT);
    thumb.translateXProperty().bind(toggle.selectedProperty().map(on -> on ? 19.0 : 3.0));
    toggle.setGraphic(track);
    toggle.setOnAction(event -> changed.accept(toggle.isSelected()));
    return toggle;
  }
}
