package com.example.datacraft.desktop.ui.view;

import com.example.datacraft.desktop.api.AirflowModels.DagRun;
import com.example.datacraft.desktop.api.AirflowModels.TaskInstance;
import com.example.datacraft.desktop.service.JobCatalog;
import com.example.datacraft.desktop.service.PipelineService;
import com.example.datacraft.desktop.ui.component.Controls;
import com.example.datacraft.desktop.ui.component.EmptyState;
import com.example.datacraft.desktop.ui.component.Icons;
import com.example.datacraft.desktop.ui.component.ParamEditor;
import com.example.datacraft.desktop.ui.component.RunRibbon;
import com.example.datacraft.desktop.ui.component.StatusBadge;
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
import java.util.function.Function;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * 流水线: the DAG table with ribbons and pause switches; below it the runs of the selected DAG and the
 * tasks of the selected run. Triggering and logs open in the side panel.
 */
public final class PipelinesView implements View {

  private final PipelinesModel model;
  private final Clock clock;
  private final Drawer drawer;
  private final Toasts toasts;
  private final ObjectMapper json;
  private final StackPane root = new StackPane();
  private final Label detailTitle = Controls.label("", "section-title", "latin");

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

    TableView<DagRow> dagTable = dagTable();
    dagTable.setPrefHeight(36 * 5 + 34);
    dagTable.setMinHeight(36 * 2 + 34);

    Label dagProblem = notice(model.dagProblem().isNotEmpty());
    dagProblem.textProperty().bind(model.dagProblem());

    Button trigger = Controls.button("触发运行", this::openTrigger, "primary");
    trigger.setGraphic(Icons.play());
    trigger.disableProperty().bind(model.selectedDag().isNull());
    detailTitle
        .textProperty()
        .bind(
            Bindings.createStringBinding(
                () -> model.selectedDag().get() == null ? "" : model.selectedDag().get(),
                model.selectedDag()));
    HBox detailHeader = Controls.row(12, detailTitle, Controls.grow(), trigger);
    Label detailProblem = notice(model.detailProblem().isNotEmpty());
    detailProblem.textProperty().bind(model.detailProblem());

    TableView<DagRun> runTable = runTable();
    TableView<TaskInstance> taskTable = taskTable();
    VBox runsBox = new VBox(8, Controls.label("运行", "caption"), runTable);
    VBox tasksBox = new VBox(8, Controls.label("任务", "caption"), taskTable);
    VBox.setVgrow(runTable, Priority.ALWAYS);
    VBox.setVgrow(taskTable, Priority.ALWAYS);
    HBox.setHgrow(runsBox, Priority.ALWAYS);
    HBox.setHgrow(tasksBox, Priority.SOMETIMES);
    runsBox.setPrefWidth(600);
    tasksBox.setPrefWidth(420);
    HBox details = new HBox(24, runsBox, tasksBox);
    VBox.setVgrow(details, Priority.ALWAYS);

    VBox page =
        new VBox(16, dagProblem, dagTable, Controls.rule(), detailHeader, detailProblem, details);
    page.getStyleClass().add("view-content");

    EmptyState empty =
        new EmptyState().set("还没有连接配置。添加连接后即可查看并触发 Airflow 的流水线。", "添加连接", openSettings);
    empty.visibleProperty().bind(model.connected().not());
    page.visibleProperty().bind(model.connected());
    root.getChildren().addAll(page, empty);
  }

  private static Label notice(javafx.beans.value.ObservableBooleanValue visible) {
    Label notice = Controls.label("", "notice", "failed");
    notice.setWrapText(true);
    notice.setMaxWidth(Double.MAX_VALUE);
    notice.visibleProperty().bind(visible);
    notice.managedProperty().bind(visible);
    return notice;
  }

  // ---------- tables ----------

  private TableView<DagRow> dagTable() {
    TableView<DagRow> table = new TableView<>(model.dags());
    table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    table.setPlaceholder(placeholder(model.loaded(), "Airflow 里还没有 DAG。", "正在读取 DAG"));
    table.getColumns().add(textColumn("DAG", 220, row -> row.dag().displayName(), "latin"));
    table.getColumns().add(textColumn("调度", 96, row -> Labels.schedule(row.dag().schedule())));
    TableColumn<DagRow, DagRow> ribbon = column("最近运行", 184);
    ribbon.setCellFactory(column -> new RibbonCell());
    table.getColumns().add(ribbon);
    TableColumn<DagRow, DagRow> last = column("上次", 180);
    last.setCellFactory(
        column ->
            new BadgeCell<>(
                row ->
                    row.latest()
                        .map(
                            run ->
                                new Badge(
                                    Tones.of(run.tone()),
                                    Labels.state(run.state())
                                        + "  "
                                        + Formats.ago(
                                            run.startDate().orElse(run.runAfter()),
                                            clock.instant())))
                        .orElse(new Badge("none", "尚未运行"))));
    table.getColumns().add(last);
    TableColumn<DagRow, DagRow> paused = column("启用", 72);
    paused.setCellFactory(column -> new SwitchCell());
    table.getColumns().add(paused);
    table
        .getSelectionModel()
        .selectedItemProperty()
        .addListener(
            (property, before, row) -> {
              if (row != null) {
                model.selectDag(row.dagId());
              }
            });
    // Keep the selection on the same DAG when a refresh replaces its row.
    model
        .dags()
        .addListener(
            (javafx.collections.ListChangeListener<DagRow>)
                change -> reselect(table, model.selectedDag().get(), DagRow::dagId));
    model
        .selectedDag()
        .addListener((property, before, dagId) -> reselect(table, dagId, DagRow::dagId));
    return table;
  }

  private TableView<DagRun> runTable() {
    TableView<DagRun> table = new TableView<>(model.runs());
    table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    table.setPlaceholder(Controls.label("还没有运行。", "secondary"));
    TableColumn<DagRun, String> runIdColumn = textColumn("运行", 260, DagRun::runId, "latin");
    runIdColumn.setCellFactory(column -> new RunIdCell());
    table.getColumns().add(runIdColumn);
    TableColumn<DagRun, DagRun> state = column("状态", 96);
    state.setCellFactory(
        column ->
            new BadgeCell<>(run -> new Badge(Tones.of(run.tone()), Labels.state(run.state()))));
    table.getColumns().add(state);
    table
        .getColumns()
        .add(
            textColumn(
                "开始",
                104,
                run ->
                    run.startDate()
                        .map(start -> Formats.when(start, clock.instant(), ZoneId.systemDefault()))
                        .orElse("")));
    TableColumn<DagRun, String> duration =
        textColumn("耗时", 64, run -> run.duration().map(Formats::duration).orElse(""));
    duration.getStyleClass().add("numeric");
    table.getColumns().add(duration);
    table.getColumns().add(textColumn("触发", 64, run -> Labels.runType(run.runType())));
    table
        .getSelectionModel()
        .selectedItemProperty()
        .addListener(
            (property, before, run) -> {
              if (run != null) {
                model.selectRun(run.runId());
              }
            });
    model
        .runs()
        .addListener(
            (javafx.collections.ListChangeListener<DagRun>)
                change -> reselect(table, model.selectedRun().get(), DagRun::runId));
    model
        .selectedRun()
        .addListener((property, before, runId) -> reselect(table, runId, DagRun::runId));
    return table;
  }

  private TableView<TaskInstance> taskTable() {
    TableView<TaskInstance> table = new TableView<>(model.tasks());
    table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    table.setPlaceholder(Controls.label("选择一次运行查看它的任务。", "secondary"));
    table
        .getColumns()
        .add(
            textColumn(
                "任务",
                160,
                task ->
                    task.mapIndex() >= 0
                        ? task.displayName() + " [" + task.mapIndex() + "]"
                        : task.displayName(),
                "latin"));
    TableColumn<TaskInstance, TaskInstance> state = column("状态", 100);
    state.setCellFactory(
        column ->
            new BadgeCell<>(
                task -> new Badge(Tones.of(task.tone()), Labels.state(task.state().orElse(null)))));
    table.getColumns().add(state);
    TableColumn<TaskInstance, String> tries =
        textColumn("尝试", 56, task -> task.tryNumber() + " / " + (task.maxTries() + 1));
    tries.getStyleClass().add("numeric");
    table.getColumns().add(tries);
    TableColumn<TaskInstance, String> duration =
        textColumn("耗时", 72, task -> task.duration().map(Formats::duration).orElse(""));
    duration.getStyleClass().add("numeric");
    table.getColumns().add(duration);
    TableColumn<TaskInstance, TaskInstance> log = column("", 72);
    log.setCellFactory(column -> new LogCell());
    table.getColumns().add(log);
    return table;
  }

  private static <T> void reselect(TableView<T> table, String key, Function<T, String> keyOf) {
    if (key == null) {
      table.getSelectionModel().clearSelection();
      return;
    }
    T selected = table.getSelectionModel().getSelectedItem();
    if (selected != null && key.equals(keyOf.apply(selected))) {
      return;
    }
    for (int index = 0; index < table.getItems().size(); index++) {
      if (key.equals(keyOf.apply(table.getItems().get(index)))) {
        table.getSelectionModel().select(index);
        return;
      }
    }
  }

  private static Label placeholder(
      javafx.beans.value.ObservableBooleanValue loaded, String empty, String loading) {
    Label label = Controls.label("", "secondary");
    label.textProperty().bind(Bindings.when(loaded).then(empty).otherwise(loading));
    return label;
  }

  private static <S> TableColumn<S, S> column(String title, double width) {
    TableColumn<S, S> column = new TableColumn<>(title);
    column.setPrefWidth(width);
    column.setSortable(false);
    column.setReorderable(false);
    column.setCellValueFactory(
        cell -> new javafx.beans.property.ReadOnlyObjectWrapper<>(cell.getValue()));
    return column;
  }

  private static <S> TableColumn<S, String> textColumn(
      String title, double width, Function<S, String> text, String... styleClasses) {
    TableColumn<S, String> column = new TableColumn<>(title);
    column.setPrefWidth(width);
    column.setSortable(false);
    column.setReorderable(false);
    column.setCellValueFactory(
        cell -> new javafx.beans.property.ReadOnlyStringWrapper(text.apply(cell.getValue())));
    if (styleClasses.length > 0) {
      column.setCellFactory(
          ignored -> {
            TableCell<S, String> cell =
                new TableCell<>() {
                  @Override
                  protected void updateItem(String value, boolean empty) {
                    super.updateItem(value, empty);
                    setText(empty ? null : value);
                  }
                };
            cell.getStyleClass().addAll(styleClasses);
            return cell;
          });
    }
    return column;
  }

  /** A run ID, which is long: cut with an ellipsis, shown whole in a tooltip. */
  private static final class RunIdCell extends TableCell<DagRun, String> {
    private final javafx.scene.control.Tooltip tip = new javafx.scene.control.Tooltip();

    RunIdCell() {
      getStyleClass().add("latin");
      setTextOverrun(javafx.scene.control.OverrunStyle.CENTER_ELLIPSIS);
    }

    @Override
    protected void updateItem(String runId, boolean empty) {
      super.updateItem(runId, empty);
      setText(empty ? null : runId);
      if (empty || runId == null) {
        setTooltip(null);
      } else {
        tip.setText(runId);
        setTooltip(tip);
      }
    }
  }

  /** What a badge cell shows: a tone class and a word. */
  private record Badge(String tone, String word) {}

  /** A status badge cell. */
  private static final class BadgeCell<S> extends TableCell<S, S> {
    private final StatusBadge badge = new StatusBadge();
    private final Function<S, Badge> describe;

    BadgeCell(Function<S, Badge> describe) {
      this.describe = describe;
    }

    @Override
    protected void updateItem(S item, boolean empty) {
      super.updateItem(item, empty);
      if (empty || item == null) {
        setGraphic(null);
        return;
      }
      Badge shown = describe.apply(item);
      badge.set(shown.tone(), shown.word());
      setGraphic(badge);
    }
  }

  private final class RibbonCell extends TableCell<DagRow, DagRow> {
    private final RunRibbon ribbon = new RunRibbon(PipelinesModel.RIBBON);

    @Override
    protected void updateItem(DagRow row, boolean empty) {
      super.updateItem(row, empty);
      if (empty || row == null) {
        setGraphic(null);
        return;
      }
      ribbon.set(Segments.ofDagRuns(row.runs(), clock.instant()));
      setGraphic(ribbon);
    }
  }

  private final class SwitchCell extends TableCell<DagRow, DagRow> {
    private final javafx.scene.control.Tooltip tip = new javafx.scene.control.Tooltip();
    private final ToggleButton toggle =
        Controls.toggleSwitch(
            "调度开关",
            on -> {
              DagRow row = getItem();
              if (row != null) {
                model
                    .setPaused(row.dagId(), !on)
                    .whenComplete(
                        (ignored, failure) ->
                            javafx.application.Platform.runLater(
                                () -> {
                                  if (failure == null) {
                                    toasts.success((on ? "已恢复调度 " : "已暂停调度 ") + row.dagId());
                                  } else {
                                    toasts.failure(PipelineService.describe(failure));
                                  }
                                }));
              }
            });

    @Override
    protected void updateItem(DagRow row, boolean empty) {
      super.updateItem(row, empty);
      if (empty || row == null) {
        setGraphic(null);
        return;
      }
      toggle.setSelected(!row.dag().paused());
      tip.setText(row.dag().paused() ? "调度已暂停：点按恢复" : "调度中：点按暂停");
      toggle.setTooltip(tip);
      setGraphic(toggle);
    }
  }

  private final class LogCell extends TableCell<TaskInstance, TaskInstance> {
    private final Button open = Controls.button("日志", () -> {}, "quiet");

    LogCell() {
      open.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
      open.setOnAction(
          event -> {
            if (getItem() != null) {
              openLog(getItem());
            }
          });
    }

    @Override
    protected void updateItem(TaskInstance task, boolean empty) {
      super.updateItem(task, empty);
      setGraphic(empty || task == null || task.tryNumber() < 1 ? null : open);
    }
  }

  // ---------- trigger ----------

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
    ToggleButton asJson = new ToggleButton("用 JSON 编辑");
    TextField note = new TextField();
    note.setPromptText("备注，可不填");
    Label error = Controls.label("", "notice", "failed");
    error.setWrapText(true);
    error.setMaxWidth(Double.MAX_VALUE);
    error.setVisible(false);
    error.setManaged(false);
    Button submit = Controls.button("触发运行", () -> {}, "primary");
    VBox body = new VBox(16);
    Label intro =
        Controls.label(
            known
                .map(JobCatalog.Entry::summary)
                .filter(text -> !text.isEmpty())
                .orElse("这条流水线的参数不在已知目录里，用 JSON 填写运行配置。"),
            "secondary");
    intro.setWrapText(true);
    Runnable layout =
        () -> {
          body.getChildren().clear();
          body.getChildren().add(intro);
          if (asJson.isSelected()) {
            body.getChildren().add(jsonText);
          } else if (!rows.isEmpty() || known.isPresent()) {
            body.getChildren()
                .add(rows.isEmpty() ? Controls.label("这条流水线没有参数。", "secondary") : form);
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
    javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(body);
    scroll.setFitToWidth(true);
    drawer.open("触发运行 " + dagId, scroll, () -> {});
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
    javafx.collections.ListChangeListener<String> follow =
        change -> {
          if (!lines.getItems().isEmpty()) {
            lines.scrollTo(lines.getItems().size() - 1);
          }
        };
    model.logLines().addListener(follow);
    VBox body = new VBox(12, problem, lines, Controls.row(12, status, Controls.grow(), more));
    VBox.setVgrow(lines, Priority.ALWAYS);
    body.setAlignment(Pos.TOP_LEFT);
    drawer.open(
        "任务 " + task.taskId() + " 的日志（第 " + Math.max(1, task.tryNumber()) + " 次尝试）",
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
  }

  @Override
  public void hidden() {
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
