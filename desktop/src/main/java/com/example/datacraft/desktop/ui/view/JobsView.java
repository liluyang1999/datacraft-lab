package com.example.datacraft.desktop.ui.view;

import com.example.datacraft.desktop.api.EngineModels.JobInfo;
import com.example.datacraft.desktop.api.EngineModels.JobResult;
import com.example.datacraft.desktop.api.EngineModels.JobStatus;
import com.example.datacraft.desktop.service.JobCatalog;
import com.example.datacraft.desktop.store.PresetRepository.Preset;
import com.example.datacraft.desktop.store.RecentRunRepository.RecentRun;
import com.example.datacraft.desktop.ui.component.BusyRing;
import com.example.datacraft.desktop.ui.component.Controls;
import com.example.datacraft.desktop.ui.component.EmptyState;
import com.example.datacraft.desktop.ui.component.Icons;
import com.example.datacraft.desktop.ui.component.Icons.Glyph;
import com.example.datacraft.desktop.ui.component.ParamEditor;
import com.example.datacraft.desktop.ui.component.RunBars;
import com.example.datacraft.desktop.ui.component.RunMark;
import com.example.datacraft.desktop.ui.shell.Dialogs;
import com.example.datacraft.desktop.util.Formats;
import com.example.datacraft.desktop.viewmodel.JobsModel;
import com.example.datacraft.desktop.viewmodel.Labels;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.binding.Bindings;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * 作业: the engine's jobs, each with the glyph of where it runs; on the right the selected job's
 * form, the run button (a turning ring while a run is on), the outcome as a mark that draws itself
 * with the metrics as figures, and the recent runs as bars.
 */
public final class JobsView implements View {

  private final JobsModel model;
  private final Clock clock;
  private final Dialogs dialogs;
  private final StackPane root = new StackPane();
  private final Timeline elapsedTicker;
  private final BusyRing busy = new BusyRing(16);
  private final ListView<JobInfo> list;
  private boolean visible;

  public JobsView(JobsModel model, Clock clock, Dialogs dialogs, Runnable openSettings) {
    this.model = model;
    this.clock = clock;
    this.dialogs = dialogs;

    list = new ListView<>(model.jobs());
    list.getStyleClass().add("job-list");
    list.setCellFactory(view -> new JobCell(model));
    list.setMinWidth(200);
    Label placeholder = Controls.label("正在读取作业", "secondary");
    placeholder.setWrapText(true);
    placeholder
        .textProperty()
        .bind(
            Bindings.createStringBinding(
                () ->
                    !model.listProblem().get().isEmpty()
                        ? model.listProblem().get()
                        : model.loaded().get() ? "引擎上没有作业" : "正在读取作业",
                model.listProblem(),
                model.loaded()));
    list.setPlaceholder(placeholder);
    list.getSelectionModel()
        .selectedItemProperty()
        .addListener(
            (property, before, job) -> {
              if (job != null && !job.equals(model.selected().get())) {
                model.select(job);
              }
            });
    model
        .selected()
        .addListener(
            (property, before, job) -> {
              if (job != null && !job.equals(list.getSelectionModel().getSelectedItem())) {
                list.getSelectionModel().select(job);
              }
            });

    StackPane disc = new StackPane();
    disc.getStyleClass().add("job-disc");
    Label name = Controls.label("", "view-title", "latin");
    Label summary = Controls.label("", "secondary");
    model
        .selected()
        .addListener(
            (property, before, job) -> {
              name.setText(job == null ? "" : job.name());
              summary.setText(job == null ? "" : model.summary(job));
              if (job != null) {
                JobCatalog.Kind kind = model.kind(job);
                disc.getChildren().setAll(Icons.of(glyph(kind), 26));
                disc.getStyleClass().removeAll("jvm", "spark", "built-in", "other");
                disc.getStyleClass().add(kindClass(kind));
              }
            });
    HBox header = Controls.row(16, disc, new VBox(4, name, summary));

    ParamEditor editor = new ParamEditor(model.params(), model::addParam, model::removeParam);

    Button run = new Button("运行作业");
    run.getStyleClass().addAll("primary", "run-button");
    run.setGraphic(Icons.of(Glyph.PLAY));
    run.textProperty().bind(Bindings.when(model.running()).then("停止等待").otherwise("运行作业"));
    run.disableProperty().bind(model.running().not().and(model.canRun().not()));
    run.setOnAction(
        event -> {
          if (model.running().get()) {
            model.stopWaiting();
          } else {
            model.run();
          }
        });

    MenuButton presets = new MenuButton("预设");
    presets.setGraphic(Icons.of(Glyph.DATABASE, 16));
    presets.setOnShowing(event -> fillPresets(presets));
    presets.disableProperty().bind(model.selected().isNull());

    Label missing = Controls.label("", "caption", "text-failed");
    missing.textProperty().bind(model.missing());
    Label elapsed = Controls.label("", "figure-small", "latin");
    HBox actions = Controls.row(12, run, elapsed, presets, missing);

    elapsedTicker =
        new Timeline(
            new KeyFrame(javafx.util.Duration.seconds(1), event -> elapsed.setText(elapsedText())));
    elapsedTicker.setCycleCount(Animation.INDEFINITE);
    model
        .running()
        .addListener(
            (property, before, running) -> {
              if (running) {
                run.setGraphic(busy);
                busy.spin(visible);
                elapsed.setText(elapsedText());
                elapsedTicker.play();
              } else {
                busy.spin(false);
                run.setGraphic(Icons.of(Glyph.PLAY));
                elapsedTicker.stop();
                elapsed.setText("");
              }
            });

    Label presetError = notice(model.presetProblem());
    Label error = notice(model.runError());

    VBox result = resultSection();

    RunBars bars = new RunBars(JobsModel.RIBBON, 9, 3, 40);
    model
        .recent()
        .addListener(
            (javafx.collections.ListChangeListener<RecentRun>)
                change -> bars.set(Segments.ofJobRuns(model.recent(), clock.instant())));
    Label noRecent = Controls.label("还没有运行", "secondary");
    noRecent.visibleProperty().bind(Bindings.isEmpty(model.recent()));
    noRecent.managedProperty().bind(noRecent.visibleProperty());
    VBox recent = new VBox(12, OverviewView.heading(Glyph.ACTIVITY, "最近运行"), bars, noRecent);

    VBox detail =
        new VBox(
            24,
            header,
            new VBox(12, OverviewView.heading(Glyph.SETTINGS, "参数"), editor),
            new VBox(10, actions, presetError, error),
            result,
            recent);
    detail.getStyleClass().add("view-content");
    ScrollPane scroll = new ScrollPane(detail);
    scroll.setFitToWidth(true);
    detail.visibleProperty().bind(model.selected().isNotNull());

    // A refresh that fails while an earlier list is shown: say so above the (stale) list.
    Label listError = notice(model.listProblem());
    listError
        .visibleProperty()
        .bind(model.listProblem().isNotEmpty().and(Bindings.isNotEmpty(model.jobs())));
    VBox listColumn = new VBox(listError, list);
    VBox.setVgrow(list, Priority.ALWAYS);
    listColumn.setMinWidth(200);

    SplitPane split = new SplitPane(listColumn, scroll);
    split.setDividerPositions(0.24);
    SplitPane.setResizableWithParent(listColumn, false);

    EmptyState empty = new EmptyState().set(Glyph.LINK, "先连接你的引擎", "添加连接", openSettings);
    empty.visibleProperty().bind(model.connected().not());
    split.visibleProperty().bind(model.connected());
    root.getChildren().addAll(split, empty);
  }

  private static Label notice(javafx.beans.value.ObservableStringValue text) {
    Label notice = Controls.label("", "notice", "failed");
    notice.setWrapText(true);
    notice.setMaxWidth(Double.MAX_VALUE);
    notice.textProperty().bind(text);
    notice.visibleProperty().bind(Bindings.isNotEmpty(text));
    notice.managedProperty().bind(notice.visibleProperty());
    return notice;
  }

  static Glyph glyph(JobCatalog.Kind kind) {
    return switch (kind) {
      case JVM -> Glyph.JVM;
      case SPARK -> Glyph.SPARK;
      case BUILT_IN -> Glyph.BUILT_IN;
      case OTHER -> Glyph.JOBS;
    };
  }

  static String kindClass(JobCatalog.Kind kind) {
    return switch (kind) {
      case JVM -> "jvm";
      case SPARK -> "spark";
      case BUILT_IN -> "built-in";
      case OTHER -> "other";
    };
  }

  private static String kindWord(JobCatalog.Kind kind) {
    return switch (kind) {
      case JVM -> "在 JVM 中运行";
      case SPARK -> "由 Spark 运行";
      case BUILT_IN -> "引擎内置";
      case OTHER -> "引擎作业";
    };
  }

  private String elapsedText() {
    var since = model.runningSince().get();
    return since == null ? "" : Formats.duration(Duration.between(since, clock.instant()));
  }

  private VBox resultSection() {
    RunMark mark = new RunMark();
    Label word = Controls.label("", "section-title");
    Label duration = Controls.label("", "figure-small", "latin");
    Label message = Controls.label("", "secondary");
    message.setTextOverrun(OverrunStyle.ELLIPSIS);
    message.setMaxWidth(640);
    Tooltip messageTip = new Tooltip();
    messageTip.setWrapText(true);
    messageTip.setMaxWidth(480);
    message.setTooltip(messageTip);
    FlowPane tiles = new FlowPane(12, 12);
    VBox longValues = new VBox(6);
    VBox section =
        new VBox(
            14, Controls.row(14, mark, new VBox(2, word, duration)), message, tiles, longValues);
    section.getStyleClass().add("result");
    Runnable show =
        () -> {
          JobResult result = model.result().get();
          section.setVisible(result != null);
          section.setManaged(result != null);
          if (result == null) {
            return;
          }
          boolean ok = result.status() == JobStatus.SUCCEEDED;
          mark.show(ok, true);
          word.setText(ok ? "成功" : Labels.state(result.status().name()));
          word.getStyleClass().removeAll("text-success", "text-failed");
          word.getStyleClass().add(ok ? "text-success" : "text-failed");
          duration.setText(result.duration().isZero() ? "" : Formats.duration(result.duration()));
          message.setText(result.message());
          messageTip.setText(result.message());
          message.setVisible(!result.message().isBlank());
          message.setManaged(!result.message().isBlank());
          tiles.getChildren().clear();
          longValues.getChildren().clear();
          for (Map.Entry<String, String> metric : result.metrics().entrySet()) {
            if (metric.getKey().equals("durationMillis")) {
              continue;
            }
            String value = Formats.metric(metric.getKey(), metric.getValue());
            if (value.length() <= 16) {
              Label figure = Controls.label(value, "tile-figure", "latin");
              Label key = Controls.label(metric.getKey(), "caption", "latin");
              VBox tile = new VBox(2, figure, key);
              tile.getStyleClass().add("metric-tile");
              tiles.getChildren().add(tile);
            } else {
              longValues.getChildren().add(longValue(metric.getKey(), value));
            }
          }
        };
    model.result().addListener((property, before, now) -> show.run());
    show.run();
    return section;
  }

  /** A value too long for a tile (a checksum, a path): one line, its key, and a copy button. */
  private static HBox longValue(String key, String value) {
    Label name = Controls.label(key, "caption", "latin");
    name.setMinWidth(72);
    Label text = Controls.label(value, "mono");
    text.setTextOverrun(OverrunStyle.CENTER_ELLIPSIS);
    text.setTooltip(new Tooltip(value));
    HBox.setHgrow(text, Priority.ALWAYS);
    text.setMaxWidth(520);
    Button copy =
        Controls.iconButton(
            Icons.of(Glyph.COPY, 16),
            "复制 " + key,
            () -> {
              ClipboardContent content = new ClipboardContent();
              content.putString(value);
              Clipboard.getSystemClipboard().setContent(content);
            });
    return Controls.row(10, name, text, copy);
  }

  private void fillPresets(MenuButton menu) {
    menu.getItems().clear();
    for (Preset preset : model.presets()) {
      MenuItem apply = new MenuItem(preset.name());
      apply.setOnAction(event -> model.applyPreset(preset));
      menu.getItems().add(apply);
    }
    if (!model.presets().isEmpty()) {
      menu.getItems().add(new SeparatorMenuItem());
    }
    MenuItem save = new MenuItem("保存为预设");
    save.setOnAction(event -> dialogs.text("保存预设", "预设名称", "").ifPresent(model::savePreset));
    menu.getItems().add(save);
    if (!model.presets().isEmpty()) {
      Menu delete = new Menu("删除预设");
      for (Preset preset : model.presets()) {
        MenuItem item = new MenuItem(preset.name());
        item.setOnAction(
            event -> {
              if (dialogs.confirm("删除预设", "删除预设“" + preset.name() + "”？", "删除")) {
                model.deletePreset(preset);
              }
            });
        delete.getItems().add(item);
      }
      menu.getItems().add(delete);
    }
  }

  /** A job: the glyph of where it runs and its name; the summary is the tooltip. */
  private static final class JobCell extends ListCell<JobInfo> {
    private final JobsModel model;
    private final StackPane glyph = new StackPane();
    private final Label name = Controls.label("", "name", "latin");
    private final HBox box = Controls.row(12, glyph, name);
    private final Tooltip tip = new Tooltip();

    JobCell(JobsModel model) {
      this.model = model;
      box.getStyleClass().add("job-cell");
      glyph.getStyleClass().add("job-glyph");
      tip.setWrapText(true);
      tip.setMaxWidth(320);
      setPrefWidth(0);
    }

    @Override
    protected void updateItem(JobInfo job, boolean empty) {
      super.updateItem(job, empty);
      if (empty || job == null) {
        setGraphic(null);
        setTooltip(null);
        return;
      }
      JobCatalog.Kind kind = model.kind(job);
      glyph.getChildren().setAll(Icons.of(glyph(kind), 18));
      glyph.getStyleClass().removeAll("jvm", "spark", "built-in", "other");
      glyph.getStyleClass().add(kindClass(kind));
      name.setText(job.name());
      tip.setText(model.summary(job) + "\n" + kindWord(kind));
      setTooltip(tip);
      setGraphic(box);
    }
  }

  /** Selects a job by name, e.g. when the overview opens this view for one. */
  public void select(String jobName) {
    model.jobs().stream()
        .filter(job -> job.name().equals(jobName))
        .findFirst()
        .ifPresent(model::select);
  }

  @Override
  public Node node() {
    return root;
  }

  @Override
  public void shown() {
    visible = true;
    model.shown();
    busy.spin(model.running().get());
  }

  @Override
  public void hidden() {
    visible = false;
    busy.spin(false);
    model.hidden();
  }

  @Override
  public void refresh() {
    model.refresh();
  }
}
