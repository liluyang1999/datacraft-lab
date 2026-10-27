package com.example.datacraft.desktop.ui.component;

import com.example.datacraft.desktop.service.JobCatalog;
import com.example.datacraft.desktop.viewmodel.JobsModel.ParamRow;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

/**
 * The parameter form: one row per key. Known keys show their hint and default and cannot be
 * renamed; added keys are free. Rows are rebuilt only when rows are added or removed, never when a
 * value changes, so typing does not touch the layout.
 */
public final class ParamEditor extends VBox {

  static final double KEY_WIDTH = 168;

  private final GridPane grid = new GridPane();
  private final ObservableList<ParamRow> rows;
  private final Consumer<ParamRow> remove;

  public ParamEditor(ObservableList<ParamRow> rows, Runnable add, Consumer<ParamRow> remove) {
    this.rows = rows;
    this.remove = remove;
    setSpacing(8);
    grid.setHgap(12);
    grid.setVgap(8);
    Button addButton = Controls.button("添加参数", add, "quiet");
    addButton.setGraphic(Icons.plus());
    getChildren().addAll(grid, addButton);
    rows.addListener(
        (ListChangeListener<ParamRow>)
            change -> {
              while (change.next()) {
                if (change.wasAdded() || change.wasRemoved() || change.wasPermutated()) {
                  rebuild();
                  return;
                }
              }
            });
    rebuild();
  }

  private void rebuild() {
    grid.getChildren().clear();
    int line = 0;
    for (ParamRow row : rows) {
      Node key = row.known() ? knownKey(row) : customKey(row);
      Node value = valueField(row);
      GridPane.setHgrow(value, Priority.ALWAYS);
      grid.add(key, 0, line);
      grid.add(value, 1, line);
      if (!row.known()) {
        grid.add(Controls.iconButton(Icons.close(), "移除参数", () -> remove.accept(row)), 2, line);
      }
      line++;
    }
  }

  private Node knownKey(ParamRow row) {
    JobCatalog.Param spec = row.spec().orElseThrow();
    Label key = Controls.label(spec.key(), "latin");
    HBox box = new HBox(2, key);
    box.setAlignment(Pos.CENTER_LEFT);
    if (spec.required()) {
      Label mark = Controls.label("*", "required-mark");
      mark.setAccessibleText("必填");
      box.getChildren().add(mark);
    }
    box.setMinWidth(KEY_WIDTH);
    box.setPrefWidth(KEY_WIDTH);
    if (!spec.hint().isEmpty()) {
      Tooltip.install(box, new Tooltip(tooltip(spec)));
    }
    return box;
  }

  private Node customKey(ParamRow row) {
    TextField key = new TextField();
    key.setPromptText("参数名");
    key.textProperty().bindBidirectional(row.key());
    key.setMinWidth(KEY_WIDTH);
    key.setPrefWidth(KEY_WIDTH);
    key.getStyleClass().add("latin");
    return key;
  }

  private Node valueField(ParamRow row) {
    if (row.spec().map(spec -> !spec.choices().isEmpty()).orElse(false)) {
      return choiceField(row, row.spec().orElseThrow());
    }
    TextField value = new TextField();
    value.textProperty().bindBidirectional(row.value());
    row.spec()
        .ifPresentOrElse(
            spec -> {
              value.setPromptText(
                  spec.defaultValue().isEmpty() ? spec.hint() : "默认 " + spec.defaultValue());
              if (!spec.hint().isEmpty()) {
                value.setTooltip(new Tooltip(tooltip(spec)));
              }
              value.setAccessibleText(spec.key());
            },
            () -> value.setPromptText("值"));
    return value;
  }

  private Node choiceField(ParamRow row, JobCatalog.Param spec) {
    List<String> items = new ArrayList<>();
    items.add("");
    items.addAll(spec.choices());
    ComboBox<String> choice = new ComboBox<>();
    choice.getItems().setAll(items);
    choice.setConverter(
        new StringConverter<>() {
          @Override
          public String toString(String value) {
            if (value == null || value.isEmpty()) {
              return spec.defaultValue().isEmpty() ? "默认" : "默认（" + spec.defaultValue() + "）";
            }
            return value;
          }

          @Override
          public String fromString(String text) {
            return text;
          }
        });
    if (row.value().get() == null) {
      row.value().set("");
    }
    // Bidirectional bindings hold both sides weakly: a rebuilt form leaves no listener behind.
    choice.valueProperty().bindBidirectional(row.value());
    choice.setMaxWidth(Double.MAX_VALUE);
    choice.setAccessibleText(spec.key());
    if (!spec.hint().isEmpty()) {
      choice.setTooltip(new Tooltip(tooltip(spec)));
    }
    return choice;
  }

  private static String tooltip(JobCatalog.Param spec) {
    String text = spec.hint();
    if (spec.required()) {
      text += "（必填）";
    } else if (!spec.defaultValue().isEmpty()) {
      text += "（默认 " + spec.defaultValue() + "）";
    }
    return text;
  }
}
