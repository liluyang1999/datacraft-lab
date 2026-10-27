package com.example.datacraft.desktop.ui.view;

import com.example.datacraft.desktop.store.ActivityRepository.Entry;
import com.example.datacraft.desktop.store.ActivityRepository.Level;
import com.example.datacraft.desktop.store.ActivityRepository.Source;
import com.example.datacraft.desktop.ui.component.Controls;
import com.example.datacraft.desktop.ui.component.Icons;
import com.example.datacraft.desktop.ui.component.Icons.Glyph;
import com.example.datacraft.desktop.ui.component.Tones;
import com.example.datacraft.desktop.ui.shell.Dialogs;
import com.example.datacraft.desktop.util.Formats;
import com.example.datacraft.desktop.viewmodel.ActivityModel;
import java.time.Clock;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/** 活动: what happened, newest first, one line each; the selected entry shows its details. */
public final class ActivityView implements View {

  /** The filter choices; ALL stands for "every source". */
  private enum Filter {
    ALL(null, "全部"),
    ENGINE(Source.ENGINE, "引擎"),
    AIRFLOW(Source.AIRFLOW, "Airflow"),
    APP(Source.APP, "应用");

    final Source source;
    final String label;

    Filter(Source source, String label) {
      this.source = source;
      this.label = label;
    }
  }

  private final ActivityModel model;
  private final VBox root = new VBox(16);

  public ActivityView(ActivityModel model, Clock clock, Dialogs dialogs) {
    this.model = model;
    root.getStyleClass().add("view-content");

    ObjectProperty<Filter> filter = new SimpleObjectProperty<>(Filter.ALL);
    filter.addListener((property, before, now) -> model.filter().set(now.source));
    HBox filters =
        Controls.segmented(
            List.of(Filter.values()),
            Arrays.stream(Filter.values()).map(choice -> choice.label).toList(),
            List.of(Glyph.ALL, Glyph.ENGINE, Glyph.AIRFLOW, Glyph.APP),
            filter);

    var clear =
        Controls.button(
            "清除",
            () -> {
              if (dialogs.confirm("清除活动记录", "删除本机保存的全部活动记录？连接配置与设置不受影响。", "清除")) {
                model.clear();
              }
            },
            "danger");
    clear.setGraphic(Icons.of(Glyph.TRASH, 16));
    HBox header = Controls.row(12, filters, Controls.grow(), clear);

    ListView<Entry> list = new ListView<>(model.visibleEntries());
    list.getStyleClass().add("activity-list");
    list.setCellFactory(view -> new EntryCell(clock));
    VBox placeholder =
        new VBox(10, Icons.of(Glyph.ACTIVITY, 36), Controls.label("还没有活动", "secondary"));
    placeholder.setAlignment(javafx.geometry.Pos.CENTER);
    placeholder.getStyleClass().add("placeholder-art");
    list.setPlaceholder(placeholder);
    VBox.setVgrow(list, Priority.ALWAYS);

    Label detail = Controls.label("", "secondary");
    detail.setWrapText(true);
    detail.setMaxWidth(Double.MAX_VALUE);
    VBox detailBox = new VBox(6, detail);
    detailBox.getStyleClass().add("panel");
    detailBox.setStyle("-fx-padding: 12 16 12 16;");
    list.getSelectionModel()
        .selectedItemProperty()
        .addListener(
            (property, before, entry) -> {
              boolean show = entry != null && !entry.detail().isBlank();
              detail.setText(show ? entry.detail() : "");
              detailBox.setVisible(show);
              detailBox.setManaged(show);
            });
    detailBox.setVisible(false);
    detailBox.setManaged(false);

    root.getChildren().addAll(header, list, detailBox);
  }

  static String tone(Level level) {
    return switch (level) {
      case INFO -> "running";
      case SUCCESS -> "success";
      case WARNING -> "warning";
      case ERROR -> "failed";
    };
  }

  static Glyph glyph(Source source) {
    return switch (source) {
      case ENGINE -> Glyph.ENGINE;
      case AIRFLOW -> Glyph.AIRFLOW;
      case APP -> Glyph.APP;
    };
  }

  static String source(Source source) {
    return switch (source) {
      case ENGINE -> "引擎";
      case AIRFLOW -> "Airflow";
      case APP -> "应用";
    };
  }

  /** The source's glyph in the entry's tone, the sentence, and the time on the right. */
  private static final class EntryCell extends ListCell<Entry> {
    private final Clock clock;
    private final StackPane glyph = new StackPane();
    private final Label text = Controls.label("", "");
    private final Label time = Controls.label("", "secondary");
    private final HBox row = Controls.row(12, glyph, text, Controls.grow(), time);
    private final Tooltip tip = new Tooltip();

    EntryCell(Clock clock) {
      this.clock = clock;
      glyph.getStyleClass().add("activity-glyph");
      time.setMinWidth(96);
      time.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
      text.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
      text.setMinWidth(0);
      HBox.setHgrow(text, Priority.SOMETIMES);
    }

    @Override
    protected void updateItem(Entry entry, boolean empty) {
      super.updateItem(entry, empty);
      if (empty || entry == null) {
        setGraphic(null);
        setTooltip(null);
        return;
      }
      time.setText(Formats.when(entry.at(), clock.instant(), ZoneId.systemDefault()));
      glyph.getChildren().setAll(Icons.of(glyph(entry.source()), 18));
      glyph.getStyleClass().removeAll(Tones.ALL);
      glyph.getStyleClass().add(tone(entry.level()));
      tip.setText(source(entry.source()) + (entry.detail().isBlank() ? "" : "\n" + entry.detail()));
      setTooltip(tip);
      text.setText(entry.text());
      text.getStyleClass().removeAll("text-failed", "text-warning");
      if (entry.level() == Level.ERROR) {
        text.getStyleClass().add("text-failed");
      } else if (entry.level() == Level.WARNING) {
        text.getStyleClass().add("text-warning");
      }
      setGraphic(row);
    }
  }

  @Override
  public Node node() {
    return root;
  }

  @Override
  public void shown() {
    model.shown();
  }
}
