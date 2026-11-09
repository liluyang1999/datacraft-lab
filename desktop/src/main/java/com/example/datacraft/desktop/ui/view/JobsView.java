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
import com.example.datacraft.desktop.util.Lists;
import com.example.datacraft.desktop.viewmodel.JobsModel;
import com.example.datacraft.desktop.viewmodel.Labels;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
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
import javafx.scene.control.TextField;
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
  private final ObservableList<JobInfo> visibleJobs = FXCollections.observableArrayList();
  private final TextField search = new TextField();
  private final ComboBox<KindFilter> kindFilter = new ComboBox<>();
  private final Label filteredSelection = Controls.label("当前作业不在筛选结果中", "caption");
  private boolean updatingList;
  private boolean visible;

  private enum KindFilter {
    ALL("全部", null),
    JVM("JVM", JobCatalog.Kind.JVM),
    SPARK("Spark", JobCatalog.Kind.SPARK),
    BUILT_IN("内置", JobCatalog.Kind.BUILT_IN),
    OTHER("其他", JobCatalog.Kind.OTHER);

    private final String label;
    private final JobCatalog.Kind kind;

    KindFilter(String label, JobCatalog.Kind kind) {
      this.label = label;
      this.kind = kind;
    }

    @Override
    public String toString() {
      return label;
    }
  }

  public JobsView(JobsModel model, Clock clock, Dialogs dialogs, Runnable openSettings) {
    this.model = model;
    this.clock = clock;
    this.dialogs = dialogs;

    list = new ListView<>(visibleJobs);
    list.setId("job-list");
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
                    !model.jobs().isEmpty()
                        ? "没有匹配的作业"
                        : !model.listProblem().get().isEmpty()
                            ? "作业目录读取失败"
                            : model.loaded().get() ? "引擎上没有作业" : "正在读取作业",
                model.listProblem(),
                model.jobs(),
                model.loaded()));
    list.setPlaceholder(placeholder);
    list.getSelectionModel()
        .selectedItemProperty()
        .addListener(
            (property, before, job) -> {
              if (!updatingList && job != null && !job.equals(model.selected().get())) {
                model.select(job);
              }
            });
    model
        .selected()
        .addListener(
            (property, before, job) -> {
              syncSelection();
            });

    StackPane disc = new StackPane();
    disc.getStyleClass().add("job-disc");
    Label name = Controls.compactLabel("", "view-title", "latin");
    Label summary = Controls.compactLabel("", "secondary");
    var jobDescription =
        Bindings.createStringBinding(
            () -> {
              JobInfo job = model.selected().get();
              return job == null ? "" : job.name() + "\n\n" + model.summary(job);
            },
            model.selected());
    Button descriptionDetails = Controls.details("作业说明", "作业说明", jobDescription, dialogs::details);
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
    VBox description = new VBox(4, name, summary);
    description.setMinWidth(0);
    HBox.setHgrow(description, Priority.ALWAYS);
    HBox header = Controls.row(16, disc, description, descriptionDetails);

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

    Button missing =
        Controls.details("检查参数", "参数检查", model.missing(), dialogs::details, "text-failed");
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

    Button presetError = notice("预设操作失败", "预设详情", model.presetProblem());
    Button error = notice("运行未完成", "运行详情", model.runError());

    VBox result = resultSection();

    RunBars bars = new RunBars(JobsModel.RIBBON, 9, 3, 40);
    model
        .recent()
        .addListener(
            (javafx.collections.ListChangeListener<RecentRun>)
                change -> bars.set(Segments.ofJobRuns(model.recent(), clock.instant())));
    Label noRecent = Controls.label("还没有运行", "secondary");
    noRecent
        .visibleProperty()
        .bind(
            Bindings.isEmpty(model.recent())
                .and(model.recentLoading().not())
                .and(model.recentProblem().isEmpty()));
    noRecent.managedProperty().bind(noRecent.visibleProperty());
    Label loadingRecent = Controls.label("正在读取运行记录", "secondary");
    loadingRecent.visibleProperty().bind(model.recentLoading());
    loadingRecent.managedProperty().bind(loadingRecent.visibleProperty());
    Button recentError = notice("运行记录读取失败", "运行记录", model.recentProblem());
    VBox recent =
        new VBox(
            12,
            OverviewView.heading(Glyph.ACTIVITY, "最近运行"),
            bars,
            loadingRecent,
            recentError,
            noRecent);

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
    Button listError = notice("作业目录读取失败", "作业目录", model.listProblem());
    VBox listColumn = new VBox(filters(), listError, list);
    VBox.setVgrow(list, Priority.ALWAYS);
    listColumn.setMinWidth(200);

    SplitPane split = new SplitPane(listColumn, scroll);
    split.setDividerPositions(0.24);
    SplitPane.setResizableWithParent(listColumn, false);

    EmptyState empty = new EmptyState().set(Glyph.LINK, "先连接你的引擎", "添加连接", openSettings);
    empty.visibleProperty().bind(model.connected().not());
    split.visibleProperty().bind(model.connected());
    root.getChildren().addAll(split, empty);
    model.jobs().addListener((ListChangeListener<JobInfo>) change -> refreshJobs());
    refreshJobs();
  }

  private VBox filters() {
    search.setPromptText("名称或说明");
    search.setId("job-search");
    Label searchLabel = Controls.label("查找作业", "caption");
    searchLabel.setLabelFor(search);
    search.textProperty().addListener((property, before, now) -> refreshJobs());
    kindFilter.setId("job-kind");
    kindFilter.getItems().setAll(KindFilter.values());
    kindFilter.setValue(KindFilter.ALL);
    kindFilter.setMinWidth(0);
    kindFilter.setMaxWidth(Double.MAX_VALUE);
    Label kindLabel = Controls.label("类型", "caption");
    kindLabel.setLabelFor(kindFilter);
    kindFilter.valueProperty().addListener((property, before, now) -> refreshJobs());
    HBox.setHgrow(kindFilter, Priority.ALWAYS);
    Button clear = Controls.iconButton(Icons.close(), "清除筛选", this::clearFilters);
    clear.setId("job-filter-clear");
    clear
        .disableProperty()
        .bind(
            search
                .textProperty()
                .isEmpty()
                .and(kindFilter.valueProperty().isEqualTo(KindFilter.ALL)));
    filteredSelection.setWrapText(true);
    filteredSelection.managedProperty().bind(filteredSelection.visibleProperty());
    VBox filters =
        new VBox(
            8,
            searchLabel,
            search,
            Controls.row(8, kindLabel, kindFilter, clear),
            filteredSelection);
    filters.getStyleClass().add("job-filters");
    return filters;
  }

  private void clearFilters() {
    search.clear();
    kindFilter.setValue(KindFilter.ALL);
  }

  private void refreshJobs() {
    String query = search.getText().strip().toLowerCase(Locale.ROOT);
    KindFilter filter = kindFilter.getValue();
    updatingList = true;
    try {
      Lists.reconcile(
          visibleJobs,
          model.jobs().stream()
              .filter(
                  job -> filter == null || filter.kind == null || model.kind(job) == filter.kind)
              .filter(
                  job ->
                      query.isEmpty()
                          || job.name().toLowerCase(Locale.ROOT).contains(query)
                          || model.summary(job).toLowerCase(Locale.ROOT).contains(query))
              .toList(),
          JobInfo::name);
      syncSelection();
    } finally {
      updatingList = false;
    }
  }

  /** Filtering changes discovery only; it never changes the draft or the running job. */
  private void syncSelection() {
    boolean previous = updatingList;
    updatingList = true;
    try {
      JobInfo selected = model.selected().get();
      JobInfo shown =
          selected == null
              ? null
              : visibleJobs.stream()
                  .filter(job -> job.name().equals(selected.name()))
                  .findFirst()
                  .orElse(null);
      if (shown != null) {
        list.getSelectionModel().select(shown);
      } else {
        list.getSelectionModel().clearSelection();
      }
      filteredSelection.setVisible(selected != null && shown == null);
    } finally {
      updatingList = previous;
    }
  }

  private Button notice(
      String shortText, String title, javafx.beans.value.ObservableStringValue text) {
    return Controls.details(shortText, title, text, dialogs::details, "text-failed");
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
    Label message = Controls.compactLabel("", "secondary");
    var resultMessage =
        Bindings.createStringBinding(
            () -> model.result().get() == null ? "" : model.result().get().message(),
            model.result());
    Button messageDetails = Controls.details("结果详情", "运行结果", resultMessage, dialogs::details);
    var metricText =
        Bindings.createStringBinding(
            () -> {
              JobResult result = model.result().get();
              if (result == null) {
                return "";
              }
              return result.metrics().entrySet().stream()
                  .map(metric -> metric.getKey() + " = " + metric.getValue())
                  .collect(java.util.stream.Collectors.joining("\n"));
            },
            model.result());
    Button metricDetails = Controls.details("指标详情", "结果指标", metricText, dialogs::details);
    FlowPane tiles = new FlowPane(12, 12);
    VBox longValues = new VBox(6);
    VBox section =
        new VBox(
            14,
            Controls.row(14, mark, new VBox(2, word, duration), messageDetails),
            message,
            tiles,
            longValues,
            metricDetails);
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
          message.setText(Formats.preview(result.message(), 100));
          message.setVisible(!result.message().isBlank());
          message.setManaged(!result.message().isBlank());
          tiles.getChildren().clear();
          longValues.getChildren().clear();
          for (Map.Entry<String, String> metric : result.metrics().entrySet()) {
            if (metric.getKey().equals("durationMillis")) {
              continue;
            }
            String value = Formats.compactMetric(metric.getKey(), metric.getValue());
            if (value.length() <= 16) {
              Label figure = Controls.compactLabel(value, "tile-figure", "latin");
              Label key = Controls.compactLabel(Labels.metric(metric.getKey()), "caption");
              key.setPrefWidth(140);
              key.setMaxWidth(140);
              figure.setMaxWidth(140);
              VBox tile = new VBox(2, figure, key);
              tile.setMinWidth(0);
              tile.setMaxWidth(172);
              tile.getStyleClass().add("metric-tile");
              tiles.getChildren().add(tile);
            } else {
              longValues.getChildren().add(longValue(metric.getKey(), metric.getValue()));
            }
          }
        };
    model.result().addListener((property, before, now) -> show.run());
    show.run();
    return section;
  }

  /** A value too long for a tile (a checksum, a path): one line, its key, and a copy button. */
  private static HBox longValue(String key, String value) {
    String label = Labels.metric(key);
    Label name = Controls.compactLabel(label, "caption");
    name.setPrefWidth(96);
    name.setMaxWidth(96);
    Label text = Controls.compactLabel(Formats.preview(value, 96), "mono");
    text.setTextOverrun(OverrunStyle.CENTER_ELLIPSIS);
    HBox.setHgrow(text, Priority.ALWAYS);
    Button copy =
        Controls.iconButton(
            Icons.of(Glyph.COPY, 16),
            "复制指标原值",
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
    private final Label name = Controls.compactLabel("", "name", "latin");
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
        .ifPresent(
            job -> {
              clearFilters();
              model.select(job);
              list.scrollTo(job);
            });
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
