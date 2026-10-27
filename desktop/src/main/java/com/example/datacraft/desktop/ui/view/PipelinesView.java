package com.example.datacraft.desktop.ui.view;

import com.example.datacraft.desktop.api.AirflowModels.DagRun;
import com.example.datacraft.desktop.api.AirflowModels.TaskInstance;
import com.example.datacraft.desktop.service.JobCatalog;
import com.example.datacraft.desktop.service.PipelineService;
import com.example.datacraft.desktop.ui.component.Controls;
import com.example.datacraft.desktop.ui.component.EmptyState;
import com.example.datacraft.desktop.ui.component.Icons;
import com.example.datacraft.desktop.ui.component.Icons.Glyph;
import com.example.datacraft.desktop.ui.component.ParamEditor;
import com.example.datacraft.desktop.ui.component.Rows;
import com.example.datacraft.desktop.ui.component.RunBars;
import com.example.datacraft.desktop.ui.component.StatusRing;
import com.example.datacraft.desktop.ui.component.TaskGraph;
import com.example.datacraft.desktop.ui.component.Tones;
import com.example.datacraft.desktop.ui.shell.Drawer;
import com.example.datacraft.desktop.ui.shell.Toasts;
import com.example.datacraft.desktop.util.Formats;
import com.example.datacraft.desktop.viewmodel.DagRow;
import com.example.datacraft.desktop.viewmodel.JobsModel.ParamRow;
import com.example.datacraft.desktop.viewmodel.Labels;
import com.example.datacraft.desktop.viewmodel.PipelinesModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * 流水线: one lane per DAG (its runs as bars, a switch for its schedule, a play button); for the
 * selected DAG its runs as bars to pick from, and the picked run drawn as the DAG it is, task by
 * task. Triggering and logs open in the side panel.
 */
public final class PipelinesView implements View {

  private final PipelinesModel model;
  private final Clock clock;
  private final Drawer drawer;
  private final Toasts toasts;
  private final ObjectMapper json;
  private final StackPane root = new StackPane();
  private final TaskGraph graph;
  private final VBox lanes = new VBox(4);

  public PipelinesView(
      PipelinesModel model,
      Clock clock,
      Drawer drawer,
      Toasts toasts,
      ObjectMapper json,
      Runnable openSettings) {
    this.model = model;
    this.clock = clock;
    this.drawer = drawer;
    this.toasts = toasts;
    this.json = json;

    Label dagProblem = notice(model.dagProblem().isNotEmpty());
    dagProblem.textProperty().bind(model.dagProblem());
    Rows.bind(lanes, model.dags(), this::lane, this::updateLane);
    model.selectedDag().addListener((property, before, now) -> markSelectedLane());
    Label noDags = Controls.label("", "secondary");
    noDags.textProperty().bind(Bindings.when(model.loaded()).then("还没有流水线").otherwise("正在读取"));
    noDags.visibleProperty().bind(Bindings.isEmpty(model.dags()));
    noDags.managedProperty().bind(noDags.visibleProperty());

    Label title = Controls.label("", "section-title", "latin");
    title
        .textProperty()
        .bind(
            Bindings.createStringBinding(
                () -> model.selectedDag().get() == null ? "" : model.selectedDag().get(),
                model.selectedDag()));
    Button trigger = Controls.button("触发运行", this::openTrigger, "primary");
    trigger.setGraphic(Icons.of(Glyph.PLAY));
    trigger.disableProperty().bind(model.selectedDag().isNull());
    HBox header = Controls.row(12, Icons.of(Glyph.PIPELINES, 18), title, Controls.grow(), trigger);
    Label detailProblem = notice(model.detailProblem().isNotEmpty());
    detailProblem.textProperty().bind(model.detailProblem());

    RunBars runBars =
        new RunBars(PipelinesModel.RUNS, 12, 4, 48)
            .onPick(segment -> model.selectRun(segment.key()));
    Runnable showRuns =
        () -> {
          runBars.set(Segments.ofDagRuns(model.runs(), clock.instant()));
          runBars.select(model.selectedRun().get());
        };
    model.runs().addListener((ListChangeListener<DagRun>) change -> showRuns.run());
    model.selectedRun().addListener((property, before, now) -> showRuns.run());
    HBox runFacts = new HBox(10);
    runFacts.setAlignment(Pos.CENTER_LEFT);
    Runnable showFacts = () -> runFacts.getChildren().setAll(facts(model.selected()));
    model.runs().addListener((ListChangeListener<DagRun>) change -> showFacts.run());
    model.selectedRun().addListener((property, before, now) -> showFacts.run());
    Label noRuns = Controls.label("", "secondary");
    noRuns.textProperty().bind(Bindings.when(model.runsLoaded()).then("还没有运行").otherwise("正在读取"));
    noRuns.visibleProperty().bind(Bindings.isEmpty(model.runs()));
    noRuns.managedProperty().bind(noRuns.visibleProperty());
    VBox runs = new VBox(10, OverviewView.heading(Glyph.ACTIVITY, "运行"), runBars, runFacts, noRuns);

    graph = new TaskGraph(this::openLog);
    Runnable showGraph = () -> graph.set(model.structure(), model.tasks());
    model.tasks().addListener((ListChangeListener<TaskInstance>) change -> showGraph.run());
    model
        .structure()
        .addListener(
            (ListChangeListener<com.example.datacraft.desktop.api.AirflowModels.Task>)
                change -> showGraph.run());
    ScrollPane graphScroll = new ScrollPane(graph);
    graphScroll.getStyleClass().add("graph-scroll");
    graphScroll.setFitToHeight(false);
    graphScroll.setPrefViewportHeight(200);
    VBox tasks =
        new VBox(
            10,
            Controls.row(
                8, OverviewView.heading(Glyph.PIPELINES, "任务"), Controls.info("点按任务查看它的日志。")),
            graphScroll);

    VBox detail = new VBox(20, header, detailProblem, runs, tasks);
    detail.getStyleClass().add("pipeline-detail");
    detail.visibleProperty().bind(model.selectedDag().isNotNull());

    VBox page = new VBox(20, dagProblem, lanes, noDags, Controls.rule(), detail);
    page.getStyleClass().add("view-content");
    ScrollPane scroll = new ScrollPane(page);
    scroll.setFitToWidth(true);

    EmptyState empty = new EmptyState().set(Glyph.LINK, "先连接你的 Airflow", "添加连接", openSettings);
    empty.visibleProperty().bind(model.connected().not());
    scroll.visibleProperty().bind(model.connected());
    root.getChildren().addAll(scroll, empty);
  }

  private static Label notice(javafx.beans.value.ObservableBooleanValue visible) {
    Label notice = Controls.label("", "notice", "failed");
    notice.setWrapText(true);
    notice.setMaxWidth(Double.MAX_VALUE);
    notice.visibleProperty().bind(visible);
    notice.managedProperty().bind(visible);
    return notice;
  }

  /** The picked run in a few chips: how it started, when, how long, how it stands. */
  private Node[] facts(Optional<DagRun> picked) {
    if (picked.isEmpty()) {
      return new Node[0];
    }
    DagRun run = picked.get();
    StatusRing ring = StatusRing.dot();
    ring.set(Tones.of(run.tone()), Labels.state(run.state()));
    Label state = Controls.label(Labels.state(run.state()), "chip");
    state.setGraphic(ring);
    Label type =
        Controls.chip(
            "manual".equals(run.runType()) ? Glyph.MANUAL : Glyph.CLOCK,
            Labels.runType(run.runType()));
    Label start =
        Controls.chip(
            Glyph.CLOCK,
            Formats.when(
                run.startDate().orElse(run.runAfter()), clock.instant(), ZoneId.systemDefault()));
    Label took = Controls.chip(Glyph.TIMER, run.duration().map(Formats::duration).orElse("进行中"));
    Tooltip.install(state, new Tooltip(run.runId()));
    return new Node[] {state, type, start, took};
  }

  // ---------- lanes ----------

  private HBox lane() {
    StackPane schedule = new StackPane();
    schedule.getStyleClass().add("lane-glyph");
    Tooltip scheduleTip = new Tooltip();
    Tooltip.install(schedule, scheduleTip);
    schedule.setUserData(scheduleTip);
    Label name = Controls.label("", "name", "latin");
    name.setMinWidth(220);
    name.setPrefWidth(220);
    RunBars bars = RunBars.compact(PipelinesModel.RIBBON);
    StatusRing last = StatusRing.dot();
    Label ago = Controls.label("", "secondary");
    ago.setMinWidth(88);
    Tooltip switchTip = new Tooltip();
    ToggleButton toggle = Controls.toggleSwitch("调度开关", on -> {});
    toggle.setTooltip(switchTip);
    Button play = Controls.iconButton(Icons.of(Glyph.PLAY), "触发运行", () -> {});
    HBox row = Controls.row(14, schedule, name, bars, last, ago, Controls.grow(), toggle, play);
    row.getStyleClass().add("lane");
    toggle.addEventHandler(MouseEvent.MOUSE_CLICKED, MouseEvent::consume);
    play.addEventHandler(MouseEvent.MOUSE_CLICKED, MouseEvent::consume);
    toggle.setOnAction(
        event -> {
          if (row.getUserData() instanceof DagRow dag) {
            boolean on = toggle.isSelected();
            model
                .setPaused(dag.dagId(), !on)
                .whenComplete(
                    (ignored, failure) ->
                        javafx.application.Platform.runLater(
                            () -> {
                              if (failure == null) {
                                toasts.success((on ? "已恢复调度 " : "已暂停调度 ") + dag.dagId());
                              } else {
                                toasts.failure(PipelineService.describe(failure));
                              }
                            }));
          }
        });
    play.setOnAction(
        event -> {
          if (row.getUserData() instanceof DagRow dag) {
            trigger(dag.dagId());
          }
        });
    Controls.activatable(
        row,
        () -> {
          if (row.getUserData() instanceof DagRow dag) {
            model.selectDag(dag.dagId());
          }
        });
    return row;
  }

  private void updateLane(HBox row, DagRow dag) {
    var children = row.getChildren();
    StackPane schedule = (StackPane) children.get(0);
    boolean manual = dag.dag().schedule() == null || dag.dag().schedule().isBlank();
    schedule.getChildren().setAll(Icons.of(manual ? Glyph.MANUAL : Glyph.CLOCK, 18));
    ((Tooltip) schedule.getUserData()).setText(Labels.schedule(dag.dag().schedule()));
    ((Label) children.get(1)).setText(dag.dag().displayName());
    ((RunBars) children.get(2)).set(Segments.ofDagRuns(dag.runs(), clock.instant()));
    StatusRing last = (StatusRing) children.get(3);
    Label ago = (Label) children.get(4);
    dag.latest()
        .ifPresentOrElse(
            run -> {
              last.set(Tones.of(run.tone()), "上次" + Labels.state(run.state()));
              ago.setText(Formats.ago(run.startDate().orElse(run.runAfter()), clock.instant()));
            },
            () -> {
              last.set("none", "尚未运行");
              ago.setText("尚未运行");
            });
    ToggleButton toggle = (ToggleButton) children.get(6);
    toggle.setSelected(!dag.dag().paused());
    toggle.getTooltip().setText(dag.dag().paused() ? "调度已暂停：点按恢复" : "调度中：点按暂停");
    row.getStyleClass().remove("paused");
    if (dag.dag().paused()) {
      row.getStyleClass().add("paused");
    }
    markSelectedLane();
  }

  private void markSelectedLane() {
    String selected = model.selectedDag().get();
    for (Node lane : lanes.getChildren()) {
      boolean on = lane.getUserData() instanceof DagRow dag && dag.dagId().equals(selected);
      lane.getStyleClass().remove("selected");
      if (on) {
        lane.getStyleClass().add("selected");
      }
    }
  }

  // ---------- trigger ----------

  /** Selects a DAG and opens its trigger panel, e.g. from the play button of the overview. */
  public void trigger(String dagId) {
    model.selectDag(dagId);
    openTrigger();
  }

  private void openTrigger() {
    drawer.release();
    String dagId = model.selectedDag().get();
    if (dagId == null) {
      return;
    }
    Optional<JobCatalog.Entry> known = model.catalogEntry(dagId);
    ObservableList<ParamRow> rows = FXCollections.observableArrayList();
    known.ifPresent(entry -> entry.params().forEach(param -> rows.add(ParamRow.known(param))));
    ParamEditor form = new ParamEditor(rows, () -> rows.add(ParamRow.custom()), rows::remove);
    TextArea jsonText = new TextArea("{\n}");
    jsonText.getStyleClass().add("mono");
    jsonText.setPrefRowCount(12);
    ToggleButton asJson = new ToggleButton("JSON");
    asJson.setGraphic(Icons.of(Glyph.LOG, 16));
    asJson.setTooltip(new Tooltip("用 JSON 编辑运行配置"));
    TextField note = new TextField();
    note.setPromptText("备注（可不填）");
    Label error = Controls.label("", "notice", "failed");
    error.setWrapText(true);
    error.setMaxWidth(Double.MAX_VALUE);
    error.setVisible(false);
    error.setManaged(false);
    Button submit = Controls.button("触发运行", () -> {}, "primary");
    submit.setGraphic(Icons.of(Glyph.PLAY));
    VBox body = new VBox(16);
    Label intro =
        Controls.label(
            known
                .map(JobCatalog.Entry::summary)
                .filter(text -> !text.isEmpty())
                .orElse("用 JSON 填写运行配置"),
            "secondary");
    intro.setWrapText(true);
    Runnable layout =
        () -> {
          body.getChildren().clear();
          body.getChildren().add(intro);
          if (asJson.isSelected()) {
            body.getChildren().add(jsonText);
          } else if (!rows.isEmpty() || known.isPresent()) {
            body.getChildren().add(rows.isEmpty() ? Controls.label("没有参数", "secondary") : form);
          }
          body.getChildren().addAll(note, error, Controls.row(12, submit, Controls.grow(), asJson));
        };
    asJson.setOnAction(
        event -> {
          if (asJson.isSelected()) {
            try {
              jsonText.setText(
                  json.writerWithDefaultPrettyPrinter()
                      .writeValueAsString(PipelinesModel.conf(values(rows))));
            } catch (com.fasterxml.jackson.core.JsonProcessingException impossible) {
              jsonText.setText("{\n}");
            }
          }
          layout.run();
        });
    if (known.isEmpty()) {
      asJson.setSelected(true);
      asJson.setDisable(true);
    }
    layout.run();
    submit.setOnAction(
        event -> {
          Map<String, Object> conf;
          if (asJson.isSelected()) {
            var parsed = model.parseConf(jsonText.getText());
            if (parsed.value().isEmpty()) {
              showError(error, parsed.error());
              return;
            }
            conf = parsed.value().get();
          } else {
            conf = PipelinesModel.conf(values(rows));
          }
          submit.setDisable(true);
          model
              .trigger(dagId, conf, note.getText())
              .whenComplete(
                  (run, failure) ->
                      javafx.application.Platform.runLater(
                          () -> {
                            submit.setDisable(false);
                            if (failure == null) {
                              drawer.close();
                              toasts.success("已触发 " + dagId);
                            } else {
                              showError(error, PipelineService.describe(failure));
                            }
                          }));
        });
    ScrollPane scroll = new ScrollPane(body);
    scroll.setFitToWidth(true);
    drawer.open("触发 " + dagId, scroll, () -> {});
  }

  private static Map<String, String> values(ObservableList<ParamRow> rows) {
    Map<String, String> values = new LinkedHashMap<>();
    rows.forEach(row -> values.put(row.key().get(), row.value().get()));
    return values;
  }

  private static void showError(Label error, String text) {
    error.setText(text);
    error.setVisible(true);
    error.setManaged(true);
  }

  // ---------- logs ----------

  private void openLog(TaskInstance task) {
    drawer.release();
    model.openLog(task);
    ListView<String> lines = new ListView<>(model.logLines());
    lines.getStyleClass().add("log-view");
    lines.setCellFactory(
        view ->
            new ListCell<>() {
              @Override
              protected void updateItem(String line, boolean empty) {
                super.updateItem(line, empty);
                setText(empty ? null : line);
              }
            });
    Label status = Controls.label("", "caption");
    status
        .textProperty()
        .bind(
            Bindings.when(model.logLoading())
                .then("正在读取")
                .otherwise(
                    Bindings.createStringBinding(
                        () -> model.logLines().size() + " 行", model.logLines())));
    Button more = Controls.button("继续读取", model::loadMoreLog, "");
    more.visibleProperty().bind(model.logNext().isNotEmpty());
    more.managedProperty().bind(more.visibleProperty());
    more.disableProperty().bind(model.logLoading());
    Label problem = notice(model.logProblem().isNotEmpty());
    problem.textProperty().bind(model.logProblem());
    // Follow the end while new lines arrive, as a terminal does. The model's list outlives this
    // drawer, so the listener goes when the drawer closes.
    ListChangeListener<String> follow =
        change -> {
          if (!lines.getItems().isEmpty()) {
            lines.scrollTo(lines.getItems().size() - 1);
          }
        };
    model.logLines().addListener(follow);
    VBox body = new VBox(12, problem, lines, Controls.row(12, status, Controls.grow(), more));
    VBox.setVgrow(lines, Priority.ALWAYS);
    body.setAlignment(Pos.TOP_LEFT);
    int attempt = Math.max(1, task.tryNumber());
    drawer.open(
        task.taskId() + " 的日志" + (attempt > 1 ? " · 第 " + attempt + " 次尝试" : ""),
        body,
        () -> {
          model.logLines().removeListener(follow);
          model.closeLog();
        });
  }

  @Override
  public Node node() {
    return root;
  }

  @Override
  public void shown() {
    model.shown();
    graph.resume();
  }

  @Override
  public void hidden() {
    graph.pause();
    model.hidden();
  }

  @Override
  public void refresh() {
    model.refresh();
  }

  @Override
  public boolean escape() {
    if (drawer.isOpen()) {
      drawer.close();
      return true;
    }
    return false;
  }

  /** Selects a DAG, e.g. when the overview opens this view for one. */
  public void select(String dagId) {
    model.selectDag(dagId);
  }
}
