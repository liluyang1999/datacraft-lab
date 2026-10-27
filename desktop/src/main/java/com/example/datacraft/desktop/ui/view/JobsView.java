package com.example.datacraft.desktop.ui.view;

import com.example.datacraft.desktop.api.EngineModels.JobInfo;
import com.example.datacraft.desktop.api.EngineModels.JobResult;
import com.example.datacraft.desktop.api.EngineModels.JobStatus;
import com.example.datacraft.desktop.api.RunTone;
import com.example.datacraft.desktop.store.PresetRepository.Preset;
import com.example.datacraft.desktop.store.RecentRunRepository.RecentRun;
import com.example.datacraft.desktop.ui.Motion;
import com.example.datacraft.desktop.ui.component.Controls;
import com.example.datacraft.desktop.ui.component.EmptyState;
import com.example.datacraft.desktop.ui.component.Icons;
import com.example.datacraft.desktop.ui.component.ParamEditor;
import com.example.datacraft.desktop.ui.component.Rows;
import com.example.datacraft.desktop.ui.component.RunRibbon;
import com.example.datacraft.desktop.ui.component.StatusBadge;
import com.example.datacraft.desktop.ui.component.Tones;
import com.example.datacraft.desktop.ui.shell.Dialogs;
import com.example.datacraft.desktop.util.Formats;
import com.example.datacraft.desktop.viewmodel.JobsModel;
import com.example.datacraft.desktop.viewmodel.Labels;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Map;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.binding.Bindings;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * 作业: the engine's jobs on the left; on the right the selected job's parameters, presets, the run
 * button (which turns into "停止等待" while a run is on), the result and the recent runs.
 */
public final class JobsView implements View {

  private final JobsModel model;
  private final Clock clock;
  private final Dialogs dialogs;
  private final StackPane root = new StackPane();
  private final Timeline elapsedTicker;

  public JobsView(JobsModel model, Clock clock, Dialogs dialogs, Runnable openSettings) {
    this.model = model;
    this.clock = clock;
    this.dialogs = dialogs;

    ListView<JobInfo> list = new ListView<>(model.jobs());
    list.getStyleClass().add("job-list");
    list.setCellFactory(view -> new JobCell(model::summary));
    list.setMinWidth(220);
    Label placeholder = Controls.label("正在读取作业目录", "secondary");
    placeholder.setWrapText(true);
    placeholder
        .textProperty()
        .bind(
            Bindings.createStringBinding(
                () ->
                    !model.listProblem().get().isEmpty()
                        ? model.listProblem().get()
                        : model.loaded().get() ? "引擎上没有作业。" : "正在读取作业目录",
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

    Label name = Controls.label("", "view-title", "latin");
    Label summary = Controls.label("", "secondary");
    summary.setWrapText(true);
    model
        .selected()
        .addListener(
            (property, before, job) -> {
              name.setText(job == null ? "" : job.name());
              summary.setText(job == null ? "" : model.summary(job));
            });

    ParamEditor editor = new ParamEditor(model.params(), model::addParam, model::removeParam);

    Button run = new Button("运行作业");
    run.getStyleClass().add("primary");
    run.setGraphic(Icons.play());
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
    run.setDefaultButton(false);

    MenuButton presets = new MenuButton("预设");
    presets.setOnShowing(event -> fillPresets(presets));
    presets.disableProperty().bind(model.selected().isNull());

    Label missing = Controls.label("", "caption", "text-failed");
    missing.textProperty().bind(model.missing());
    Label elapsed = Controls.label("", "secondary");
    HBox actions = Controls.row(12, run, presets, missing, elapsed);

    ProgressBar progress = new ProgressBar(ProgressBar.INDETERMINATE_PROGRESS);
    progress.getStyleClass().add("progress-line");
    progress.setMaxWidth(Double.MAX_VALUE);
    progress.visibleProperty().bind(model.running());
    progress.managedProperty().bind(model.running());

    elapsedTicker =
        new Timeline(
            new KeyFrame(javafx.util.Duration.seconds(1), event -> elapsed.setText(elapsedText())));
    elapsedTicker.setCycleCount(Animation.INDEFINITE);
    model
        .running()
        .addListener(
            (property, before, running) -> {
              if (running) {
                elapsed.setText(elapsedText());
                elapsedTicker.play();
                // Reduced motion: a still line instead of the moving one.
                progress.setProgress(Motion.reduced() ? 1 : ProgressBar.INDETERMINATE_PROGRESS);
              } else {
                elapsedTicker.stop();
                elapsed.setText("");
              }
            });

    Label presetError = Controls.label("", "notice", "failed");
    presetError.setWrapText(true);
    presetError.setMaxWidth(Double.MAX_VALUE);
    presetError.textProperty().bind(model.presetProblem());
    presetError.visibleProperty().bind(model.presetProblem().isNotEmpty());
    presetError.managedProperty().bind(presetError.visibleProperty());

    Label error = Controls.label("", "notice", "failed");
    error.setWrapText(true);
    error.setMaxWidth(Double.MAX_VALUE);
    error.textProperty().bind(model.runError());
    error.visibleProperty().bind(model.runError().isNotEmpty());
    error.managedProperty().bind(error.visibleProperty());

    VBox result = resultSection();

    RunRibbon ribbon = new RunRibbon(JobsModel.RIBBON);
    VBox recentRows = new VBox();
    Rows.bind(recentRows, model.recent(), this::recentRow, this::updateRecentRow);
    model
        .recent()
        .addListener(
            (javafx.collections.ListChangeListener<RecentRun>)
                change -> ribbon.set(Segments.ofJobRuns(model.recent(), clock.instant())));
    Label noRecent = Controls.label("这个作业还没有从本机运行过。", "secondary");
    noRecent.visibleProperty().bind(Bindings.isEmpty(model.recent()));
    noRecent.managedProperty().bind(noRecent.visibleProperty());
    VBox recent =
        new VBox(
            12,
            Controls.row(16, Controls.label("最近运行", "section-title"), ribbon),
            noRecent,
            recentRows);

    VBox detail =
        new VBox(
            24,
            new VBox(6, name, summary),
            new VBox(
                12,
                Controls.label("参数", "section-title"),
                editor,
                actions,
                progress,
                presetError,
                error),
            result,
            Controls.rule(),
            recent);
    detail.getStyleClass().add("view-content");
    ScrollPane scroll = new ScrollPane(detail);
    scroll.setFitToWidth(true);
    detail.visibleProperty().bind(model.selected().isNotNull());

    // A refresh that fails while an earlier list is shown: say so above the (stale) list.
    Label listError = Controls.label("", "notice", "failed");
    listError.setWrapText(true);
    listError.setMaxWidth(Double.MAX_VALUE);
    listError.textProperty().bind(model.listProblem());
    listError
        .visibleProperty()
        .bind(model.listProblem().isNotEmpty().and(Bindings.isNotEmpty(model.jobs())));
    listError.managedProperty().bind(listError.visibleProperty());
    VBox listColumn = new VBox(listError, list);
    VBox.setVgrow(list, javafx.scene.layout.Priority.ALWAYS);
    listColumn.setMinWidth(220);

    SplitPane split = new SplitPane(listColumn, scroll);
    split.setDividerPositions(0.26);
    SplitPane.setResizableWithParent(listColumn, false);

    EmptyState empty = new EmptyState().set("还没有连接配置。添加连接后即可浏览并运行引擎的作业。", "添加连接", openSettings);
    empty.visibleProperty().bind(model.connected().not());
    split.visibleProperty().bind(model.connected());
    root.getChildren().addAll(split, empty);
  }

  private String elapsedText() {
    var since = model.runningSince().get();
    return since == null ? "" : "已用 " + Formats.duration(Duration.between(since, clock.instant()));
  }

  private VBox resultSection() {
    StatusBadge status = new StatusBadge();
    Label duration = Controls.label("", "secondary");
    Label message = Controls.label("", "");
    message.setWrapText(true);
    GridPane metrics = new GridPane();
    metrics.setHgap(24);
    metrics.setVgap(6);
    VBox section =
        new VBox(
            12,
            Controls.label("结果", "section-title"),
            Controls.row(12, status, duration),
            message,
            metrics);
    Runnable show =
        () -> {
          JobResult result = model.result().get();
          section.setVisible(result != null);
          section.setManaged(result != null);
          if (result == null) {
            return;
          }
          boolean ok = result.status() == JobStatus.SUCCEEDED;
          status.set(ok ? "success" : "failed", ok ? "成功" : Labels.state(result.status().name()));
          duration.setText(result.duration().isZero() ? "" : Formats.duration(result.duration()));
          message.setText(result.message());
          metrics.getChildren().clear();
          int line = 0;
          for (Map.Entry<String, String> metric : result.metrics().entrySet()) {
            if (metric.getKey().equals("durationMillis")) {
              continue;
            }
            Label key = Controls.label(metric.getKey(), "secondary", "latin");
            Label value = Controls.label(Formats.metric(metric.getKey(), metric.getValue()), "");
            value.setWrapText(true);
            metrics.addRow(line++, key, value);
          }
        };
    model.result().addListener((property, before, now) -> show.run());
    show.run();
    return section;
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
    MenuItem save = new MenuItem("保存当前参数为预设");
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

  private HBox recentRow() {
    Label time = Controls.label("", "secondary");
    time.setMinWidth(96);
    StatusBadge state = new StatusBadge();
    state.setMinWidth(80);
    Label duration = Controls.label("", "secondary");
    duration.setMinWidth(72);
    duration.setAlignment(Pos.CENTER_RIGHT);
    Label message = Controls.label("", "secondary");
    message.setMaxWidth(560);
    HBox row = Controls.row(16, time, state, duration, message);
    row.setMinHeight(32);
    return row;
  }

  private void updateRecentRow(HBox row, RecentRun run) {
    var children = row.getChildren();
    ((Label) children.get(0))
        .setText(Formats.when(run.startedAt(), clock.instant(), ZoneId.systemDefault()));
    ((StatusBadge) children.get(1))
        .set(Tones.of(RunTone.of(run.status())), Labels.state(run.status()));
    ((Label) children.get(2)).setText(Formats.duration(run.duration()));
    ((Label) children.get(3)).setText(run.message());
  }

  /** Two lines: the job name and its description. */
  private static final class JobCell extends ListCell<JobInfo> {
    private final Label name = Controls.label("", "name", "latin");
    private final Label description = Controls.label("", "caption");
    private final VBox box = new VBox(name, description);
    private final java.util.function.Function<JobInfo, String> describe;

    JobCell(java.util.function.Function<JobInfo, String> describe) {
      this.describe = describe;
      box.getStyleClass().add("job-cell");
      setPrefWidth(0);
    }

    @Override
    protected void updateItem(JobInfo job, boolean empty) {
      super.updateItem(job, empty);
      if (empty || job == null) {
        setGraphic(null);
        return;
      }
      name.setText(job.name());
      description.setText(describe.apply(job));
      setGraphic(box);
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

  @Override
  public void hidden() {
    model.hidden();
  }

  @Override
  public void refresh() {
    model.refresh();
  }
}
