package com.example.datacraft.desktop.ui.view;

import com.example.datacraft.desktop.api.AirflowModels;
import com.example.datacraft.desktop.api.AirflowModels.ActiveTask;
import com.example.datacraft.desktop.api.AirflowModels.ImportError;
import com.example.datacraft.desktop.api.AirflowModels.Pool;
import com.example.datacraft.desktop.api.EngineModels.Status;
import com.example.datacraft.desktop.service.ConnectionMonitor;
import com.example.datacraft.desktop.service.ConnectionMonitor.Sample;
import com.example.datacraft.desktop.ui.component.Controls;
import com.example.datacraft.desktop.ui.component.EmptyState;
import com.example.datacraft.desktop.ui.component.Icons;
import com.example.datacraft.desktop.ui.component.Icons.Glyph;
import com.example.datacraft.desktop.ui.component.Meter;
import com.example.datacraft.desktop.ui.component.Rows;
import com.example.datacraft.desktop.ui.component.Sparkline;
import com.example.datacraft.desktop.ui.component.StatusRing;
import com.example.datacraft.desktop.ui.component.Tones;
import com.example.datacraft.desktop.ui.shell.Dialogs;
import com.example.datacraft.desktop.util.Formats;
import com.example.datacraft.desktop.viewmodel.Alerts.Alert;
import com.example.datacraft.desktop.viewmodel.Alerts.Level;
import com.example.datacraft.desktop.viewmodel.Labels;
import com.example.datacraft.desktop.viewmodel.LatencyStats;
import com.example.datacraft.desktop.viewmodel.MonitorModel;
import com.example.datacraft.desktop.viewmodel.StatusModel;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import javafx.beans.binding.Bindings;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * 监控, the live picture: what needs attention first, then the engine's load and counters and
 * Airflow's components, both backends' latency over the last checks, the tasks running or waiting
 * now, the pools' slots and the DAG files that fail to parse. Every figure updates in place; rows
 * that do not change are not rebuilt.
 */
public final class MonitorView implements View {

  /** The sections' titles, also their accessible names. */
  static final String ATTENTION = "需要关注";

  static final String TASKS = "运行中的任务";
  static final String POOLS = "资源池";
  static final String IMPORT_ERRORS = "DAG 导入错误";

  private final MonitorModel model;
  private final Clock clock;
  private final Dialogs dialogs;
  private final Consumer<String> openPipeline;
  private final Runnable openSettings;
  private final StackPane root = new StackPane();

  public MonitorView(
      MonitorModel model,
      StatusModel status,
      Clock clock,
      Consumer<String> openPipeline,
      Runnable openSettings,
      Dialogs dialogs) {
    this.model = model;
    this.clock = clock;
    this.dialogs = dialogs;
    this.openPipeline = openPipeline;
    this.openSettings = openSettings;

    GridPane tiles = new GridPane();
    tiles.getStyleClass().add("tile-grid");
    tiles.setHgap(16);
    tiles.setVgap(16);
    for (int column = 0; column < 2; column++) {
      ColumnConstraints share = new ColumnConstraints();
      share.setPercentWidth(50);
      share.setHgrow(Priority.ALWAYS);
      tiles.getColumnConstraints().add(share);
    }
    tiles.add(engineTile(), 0, 0);
    tiles.add(airflowTile(status), 1, 0);

    VBox page = new VBox(28, attention(), tiles, latency(status), tasks(), pools(), importErrors());
    page.getStyleClass().add("view-content");
    ScrollPane scroll = new ScrollPane(page);
    scroll.setFitToWidth(true);

    EmptyState empty = new EmptyState().set(Glyph.LINK, "先连接你的引擎与 Airflow", "添加连接", openSettings);
    root.getChildren().addAll(scroll, empty);
    empty.visibleProperty().bind(model.connected().not());
    scroll.visibleProperty().bind(model.connected());
  }

  // ---------- 需要关注 ----------

  private Node attention() {
    VBox rows = new VBox(6);
    Rows.bind(rows, model.alerts(), this::alertRow, this::updateAlert);
    Label calm = Controls.label("一切正常", "calm-title");
    Label checked = Controls.label("", "caption");
    HBox allClear = Controls.row(10, mark(Glyph.CHECK, "success"), new VBox(0, calm, checked));
    allClear.getStyleClass().add("all-clear");
    allClear.visibleProperty().bind(Bindings.isEmpty(model.alerts()).and(model.loaded()));
    allClear.managedProperty().bind(allClear.visibleProperty());
    checked
        .textProperty()
        .bind(
            Bindings.createStringBinding(
                () ->
                    model.updatedAt().get() == null
                        ? ""
                        : "更新于 " + Formats.clock(model.updatedAt().get(), ZoneId.systemDefault()),
                model.updatedAt()));
    Label count = Controls.label("", "count-badge");
    count.textProperty().bind(model.alertCount().asString());
    count.visibleProperty().bind(model.alertCount().greaterThan(0));
    count.managedProperty().bind(count.visibleProperty());
    HBox heading = OverviewView.heading(Glyph.BELL, ATTENTION);
    heading.getChildren().add(count);
    VBox section = new VBox(10, heading, allClear, rows);
    section.setAccessibleText(ATTENTION);
    return section;
  }

  private HBox alertRow() {
    StackPane icon = new StackPane();
    icon.getStyleClass().add("alert-mark");
    Label title = Controls.compactLabel("", "alert-title");
    Label detail = Controls.compactLabel("", "caption");
    VBox text = new VBox(1, title, detail);
    text.setMinWidth(0);
    HBox.setHgrow(text, Priority.ALWAYS);
    Button details = Controls.button("详情", () -> {}, "quiet");
    Button action = Controls.button("", () -> {}, "quiet");
    HBox row = Controls.row(12, icon, text, details, action);
    row.getStyleClass().add("alert-row");
    return row;
  }

  private void updateAlert(HBox row, Alert alert) {
    StackPane icon = (StackPane) row.getChildren().get(0);
    VBox text = (VBox) row.getChildren().get(1);
    Button details = (Button) row.getChildren().get(2);
    Button action = (Button) row.getChildren().get(3);
    boolean failed = alert.level() == Level.FAILED;
    row.getStyleClass().removeAll("failed", "warning");
    row.getStyleClass().add(failed ? "failed" : "warning");
    icon.getChildren().setAll(Icons.of(failed ? Glyph.CROSS : Glyph.WARNING, 16));
    ((Label) text.getChildren().get(0)).setText(alert.title());
    String firstLine = alert.detail().lines().findFirst().orElse("");
    ((Label) text.getChildren().get(1)).setText(Formats.preview(firstLine, 96));
    row.setAccessibleText(alert.title());
    details.setOnAction(event -> dialogs.details(alert.title(), alert.detail()));
    details.setVisible(!alert.detail().isBlank());
    details.setManaged(details.isVisible());
    switch (alert.target()) {
      case SETTINGS -> {
        action.setText("检查连接");
        action.setOnAction(event -> openSettings.run());
        action.setVisible(true);
      }
      case PIPELINE -> {
        action.setText("打开流水线");
        action.setOnAction(event -> alert.dagId().ifPresent(openPipeline));
        action.setVisible(alert.dagId().isPresent());
      }
      case MONITOR -> action.setVisible(false);
    }
    action.setManaged(action.isVisible());
  }

  // ---------- engine and Airflow tiles ----------

  private Node engineTile() {
    Label version = Controls.label("", "chip", "latin");
    HBox head = tileHead(Glyph.ENGINE, ConnectionMonitor.ENGINE, version);
    Label load = Controls.label("—", "figure", "latin");
    Label limit = Controls.label("", "caption");
    Meter meter = new Meter(10);
    HBox.setHgrow(meter, Priority.ALWAYS);
    HBox loadRow = Controls.row(10, load, limit);
    loadRow.setAlignment(Pos.BASELINE_LEFT);
    Label started = metric();
    Label succeeded = metric();
    Label failed = metric();
    Label rejected = metric();
    HBox counters =
        Controls.row(
            20,
            metricBox(started, "开始"),
            metricBox(succeeded, "成功"),
            metricBox(failed, "失败"),
            metricBox(rejected, "拒绝"));
    Label uptime = Controls.label("", "caption");
    Label problem = Controls.compactLabel("", "caption", "text-warning");
    problem.textProperty().bind(model.engineProblem());
    problem.visibleProperty().bind(model.engineProblem().isNotEmpty());
    problem.managedProperty().bind(problem.visibleProperty());
    Label unsupported = Controls.label("这个引擎版本不提供运行状态", "caption");
    unsupported.visibleProperty().bind(model.engineUnsupported());
    unsupported.managedProperty().bind(unsupported.visibleProperty());
    VBox body = new VBox(10, loadRow, meter, counters, uptime);
    body.visibleProperty().bind(model.engine().isNotNull());
    body.managedProperty().bind(body.visibleProperty());
    VBox tile = new VBox(14, head, body, unsupported, problem);
    tile.getStyleClass().addAll("tile", "monitor-tile");
    tile.setAccessibleText(ConnectionMonitor.ENGINE);
    Runnable show =
        () -> {
          Status status = model.engine().get();
          if (status == null) {
            version.setVisible(false);
            return;
          }
          version.setVisible(!status.version().isBlank());
          version.setText("v" + status.version());
          load.setText(status.active().size() + " / " + status.maxConcurrentRuns());
          limit.setText("同时运行");
          meter.set(
              status.maxConcurrentRuns(),
              List.of(new Meter.Part(status.active().size(), "running")));
          Tooltip.install(meter, new Tooltip(activeRuns(status)));
          started.setText(Formats.thousands(status.started()));
          succeeded.setText(Formats.thousands(status.succeeded()));
          failed.setText(Formats.thousands(status.failed()));
          rejected.setText(Formats.thousands(status.rejected()));
          uptime.setText("已运行 " + Formats.duration(status.uptime()));
        };
    model.engine().addListener((property, before, now) -> show.run());
    show.run();
    return tile;
  }

  private static String activeRuns(Status status) {
    if (status.active().isEmpty()) {
      return "没有正在运行的作业";
    }
    StringBuilder text = new StringBuilder();
    status
        .active()
        .forEach(
            run ->
                text.append(run.job())
                    .append("  ")
                    .append(Formats.duration(run.elapsed()))
                    .append('\n'));
    return text.toString().strip();
  }

  private Node airflowTile(StatusModel status) {
    HBox head = tileHead(Glyph.AIRFLOW, ConnectionMonitor.AIRFLOW, new Label());
    VBox components = new VBox(8);
    Label missing = Controls.label("等待 Airflow 的健康检查", "caption");
    VBox tile = new VBox(14, head, components, missing);
    tile.getStyleClass().addAll("tile", "monitor-tile");
    tile.setAccessibleText(ConnectionMonitor.AIRFLOW);
    Runnable show =
        () -> {
          var snapshot = status.snapshot().get();
          Optional<AirflowModels.Health> health =
              snapshot == null ? Optional.empty() : snapshot.airflowHealth();
          missing.setVisible(health.isEmpty());
          missing.setManaged(health.isEmpty());
          if (health.isEmpty()) {
            components.getChildren().clear();
            return;
          }
          AirflowModels.Health value = health.get();
          List<Node> rows =
              new java.util.ArrayList<>(
                  List.of(
                      componentRow("元数据库", value.metadatabase()),
                      componentRow("调度器", value.scheduler()),
                      componentRow("触发器", value.triggerer())));
          value.dagProcessor().ifPresent(processor -> rows.add(componentRow("DAG 处理器", processor)));
          components.getChildren().setAll(rows);
        };
    status.snapshot().addListener((property, before, now) -> show.run());
    show.run();
    return tile;
  }

  private Node componentRow(String name, AirflowModels.Component component) {
    StatusRing ring = StatusRing.dot();
    boolean healthy = component.healthy();
    ring.set(healthy ? "success" : "failed", name + (healthy ? " 正常" : " 异常"));
    Label title = Controls.label(name, "");
    Label beat =
        Controls.label(
            component
                .heartbeat()
                .map(at -> "心跳 " + Formats.ago(at, clock.instant()))
                .orElse(healthy ? "正常" : "异常"),
            "caption");
    HBox row = Controls.row(10, ring, title, Controls.grow(), beat);
    row.getStyleClass().add("component-row");
    row.setAccessibleText(name + " " + (healthy ? "正常" : "异常"));
    return row;
  }

  private static HBox tileHead(Glyph glyph, String name, Label trailing) {
    HBox head =
        Controls.row(
            10, Icons.of(glyph, 20), Controls.label(name, "tile-title"), Controls.grow(), trailing);
    head.getStyleClass().add("tile-head");
    return head;
  }

  private static Label metric() {
    return Controls.label("—", "metric-figure", "latin");
  }

  private static VBox metricBox(Label figure, String caption) {
    VBox box = new VBox(0, figure, Controls.label(caption, "caption"));
    box.getStyleClass().add("metric-box");
    return box;
  }

  // ---------- latency and availability ----------

  private Node latency(StatusModel status) {
    VBox section =
        new VBox(
            10,
            OverviewView.heading(Glyph.PULSE, "延迟与可用性"),
            latencyRow(
                ConnectionMonitor.ENGINE,
                Glyph.ENGINE,
                model.engineSamples(),
                model.engineLatency()),
            latencyRow(
                ConnectionMonitor.AIRFLOW,
                Glyph.AIRFLOW,
                model.airflowSamples(),
                model.airflowLatency()));
    section.setAccessibleText("延迟与可用性");
    return section;
  }

  private Node latencyRow(
      String name,
      Glyph glyph,
      ObservableValue<List<Sample>> samples,
      ObservableValue<LatencyStats> stats) {
    Label title = Controls.label(name, "");
    title.setMinWidth(88);
    Sparkline line = new Sparkline(ConnectionMonitor.HISTORY, 40);
    HBox.setHgrow(line, Priority.ALWAYS);
    Label latest = Controls.label("—", "metric-figure", "latin");
    Label spread = Controls.label("", "caption");
    VBox figures = new VBox(0, latest, spread);
    figures.setMinWidth(112);
    figures.setAlignment(Pos.CENTER_RIGHT);
    Label availability = Controls.label("—", "metric-figure", "latin");
    VBox share = new VBox(0, availability, Controls.label("可用", "caption"));
    share.setMinWidth(64);
    share.setAlignment(Pos.CENTER_RIGHT);
    HBox row = Controls.row(16, Icons.of(glyph, 18), title, line, figures, share);
    row.getStyleClass().add("latency-row");
    Runnable show =
        () -> {
          line.set(samples.getValue());
          LatencyStats value = stats.getValue();
          latest.setText(value.latest().map(Formats::latency).orElse("—"));
          spread.setText(
              value.median().isPresent()
                  ? "中位 "
                      + Formats.latency(value.median().get())
                      + "，最慢 "
                      + Formats.latency(value.worst().orElseThrow())
                  : "");
          double ratio = value.availability();
          availability.setText(ratio < 0 ? "—" : Math.round(ratio * 1000) / 10.0 + "%");
          availability.getStyleClass().removeAll("text-success", "text-warning", "text-failed");
          if (ratio >= 0) {
            availability
                .getStyleClass()
                .add(
                    ratio >= 0.99 ? "text-success" : ratio >= 0.9 ? "text-warning" : "text-failed");
          }
          row.setAccessibleText(name + " 延迟 " + latest.getText() + "，可用 " + availability.getText());
        };
    stats.addListener((property, before, now) -> show.run());
    show.run();
    return row;
  }

  // ---------- running tasks ----------

  private Node tasks() {
    VBox rows = new VBox(2);
    Rows<ActiveTask, HBox> bound = Rows.bind(rows, model.tasks(), this::taskRow, this::updateTask);
    model.applied().addListener((property, before, now) -> bound.refresh());
    Label count = Controls.label("", "count-badge", "quiet-badge");
    count.textProperty().bind(Bindings.size(model.tasks()).asString());
    count.visibleProperty().bind(Bindings.isNotEmpty(model.tasks()));
    count.managedProperty().bind(count.visibleProperty());
    HBox heading = OverviewView.heading(Glyph.PLAY, TASKS);
    heading.getChildren().add(count);
    VBox section =
        new VBox(
            10,
            heading,
            problem(model.tasksProblem(), "任务状态未更新"),
            rows,
            idle("没有正在运行或等待的任务", Bindings.isEmpty(model.tasks())));
    section.setAccessibleText(TASKS);
    return section;
  }

  private HBox taskRow() {
    StatusRing ring = StatusRing.dot();
    Label dag = Controls.compactLabel("", "latin");
    Label task = Controls.compactLabel("", "latin", "secondary");
    HBox names = Controls.row(6, dag, Controls.label("›", "secondary"), task);
    names.setMinWidth(0);
    HBox.setHgrow(names, Priority.ALWAYS);
    Label state = Controls.label("", "chip");
    Label time = Controls.label("", "caption", "latin");
    time.setMinWidth(72);
    time.setAlignment(Pos.CENTER_RIGHT);
    HBox row = Controls.row(12, ring, names, state, time);
    row.getStyleClass().addAll("monitor-row", "clickable");
    // Once per row: the row's current task is its user data.
    Controls.activatable(
        row,
        () -> {
          if (row.getUserData() instanceof ActiveTask current) {
            openPipeline.accept(current.dagId());
          }
        });
    return row;
  }

  private void updateTask(HBox row, ActiveTask task) {
    StatusRing ring = (StatusRing) row.getChildren().get(0);
    HBox names = (HBox) row.getChildren().get(1);
    Label state = (Label) row.getChildren().get(2);
    Label time = (Label) row.getChildren().get(3);
    String label = Labels.state(task.state());
    ring.set(Tones.of(task.tone()), label);
    ((Label) names.getChildren().get(0)).setText(task.dagId());
    String taskName =
        task.mapIndex() >= 0 ? task.taskId() + "[" + task.mapIndex() + "]" : task.taskId();
    ((Label) names.getChildren().get(2)).setText(taskName);
    String tries = task.tryNumber() > 1 ? "，第 " + task.tryNumber() + " 次" : "";
    state.setText(label + tries);
    Instant now = clock.instant();
    time.setText(
        task.startDate()
            .map(at -> Formats.duration(elapsed(at, now)))
            .orElseGet(() -> task.queuedAt().map(at -> Formats.ago(at, now)).orElse("")));
    row.setAccessibleText(task.dagId() + " " + taskName + " " + label);
  }

  private static Duration elapsed(Instant from, Instant to) {
    Duration elapsed = Duration.between(from, to);
    return elapsed.isNegative() ? Duration.ZERO : elapsed;
  }

  // ---------- pools ----------

  private Node pools() {
    VBox rows = new VBox(2);
    Rows.bind(rows, model.pools(), this::poolRow, this::updatePool);
    VBox section =
        new VBox(
            10,
            OverviewView.heading(Glyph.ALL, POOLS),
            problem(model.poolsProblem(), "资源池未更新"),
            rows,
            idle("没有资源池", Bindings.isEmpty(model.pools())));
    section.setAccessibleText(POOLS);
    return section;
  }

  private HBox poolRow() {
    Label name = Controls.compactLabel("", "latin");
    name.setMinWidth(120);
    name.setPrefWidth(160);
    name.setMaxWidth(200);
    Meter meter = new Meter(10);
    HBox.setHgrow(meter, Priority.ALWAYS);
    Label figures = Controls.label("", "caption");
    figures.setMinWidth(168);
    figures.setAlignment(Pos.CENTER_RIGHT);
    HBox row = Controls.row(16, name, meter, figures);
    row.getStyleClass().add("monitor-row");
    return row;
  }

  private void updatePool(HBox row, Pool pool) {
    Label name = (Label) row.getChildren().get(0);
    Meter meter = (Meter) row.getChildren().get(1);
    Label figures = (Label) row.getChildren().get(2);
    name.setText(pool.name());
    meter.set(
        pool.slots(),
        List.of(
            new Meter.Part(pool.running(), "running"),
            new Meter.Part(pool.queued() + pool.scheduled(), "waiting"),
            new Meter.Part(pool.deferred(), "deferred")));
    String capacity = pool.unlimited() ? "不限" : pool.open() + " / " + pool.slots() + " 空闲";
    figures.setText(
        "运行 " + pool.running() + "  排队 " + (pool.queued() + pool.scheduled()) + "  " + capacity);
    row.getStyleClass().remove("exhausted");
    if (pool.exhausted()) {
      row.getStyleClass().add("exhausted");
    }
    row.setAccessibleText(pool.name() + " " + figures.getText());
  }

  // ---------- import errors ----------

  private Node importErrors() {
    VBox rows = new VBox(2);
    Rows<ImportError, HBox> bound =
        Rows.bind(rows, model.importErrors(), this::errorRow, this::updateError);
    model.applied().addListener((property, before, now) -> bound.refresh());
    Label count = Controls.label("", "count-badge");
    count.textProperty().bind(Bindings.size(model.importErrors()).asString());
    count.visibleProperty().bind(Bindings.isNotEmpty(model.importErrors()));
    count.managedProperty().bind(count.visibleProperty());
    HBox heading = OverviewView.heading(Glyph.LOG, IMPORT_ERRORS);
    heading.getChildren().add(count);
    HBox clean =
        Controls.row(8, mark(Glyph.CHECK, "success"), Controls.label("所有 DAG 文件都能解析", "secondary"));
    clean
        .visibleProperty()
        .bind(
            Bindings.isEmpty(model.importErrors())
                .and(model.loaded())
                .and(model.importProblem().isEmpty()));
    clean.managedProperty().bind(clean.visibleProperty());
    VBox section = new VBox(10, heading, problem(model.importProblem(), "导入错误未更新"), rows, clean);
    section.setAccessibleText(IMPORT_ERRORS);
    return section;
  }

  private HBox errorRow() {
    Label file = Controls.label("", "latin");
    file.setMinWidth(0);
    file.setMaxWidth(Double.MAX_VALUE);
    file.setTooltip(new Tooltip());
    HBox.setHgrow(file, Priority.ALWAYS);
    Label time = Controls.label("", "caption");
    Button details = Controls.button("查看错误", () -> {}, "quiet");
    HBox row = Controls.row(12, mark(Glyph.CROSS, "failed"), file, time, details);
    row.getStyleClass().add("monitor-row");
    return row;
  }

  private void updateError(HBox row, ImportError error) {
    Label file = (Label) row.getChildren().get(1);
    Label time = (Label) row.getChildren().get(2);
    Button details = (Button) row.getChildren().get(3);
    file.setText(error.shortName());
    file.getTooltip().setText(error.filename());
    time.setText(error.timestamp().map(at -> Formats.ago(at, clock.instant())).orElse(""));
    details.setOnAction(event -> dialogs.details(error.filename(), error.stackTrace()));
    row.setAccessibleText("导入错误 " + error.filename());
  }

  // ---------- shared ----------

  private Button problem(javafx.beans.value.ObservableStringValue problem, String text) {
    return Controls.details(text, "监控", problem, dialogs::details, "text-warning");
  }

  private Label idle(String text, javafx.beans.binding.BooleanBinding empty) {
    Label label = Controls.label(text, "secondary");
    label.visibleProperty().bind(empty.and(model.loaded()));
    label.managedProperty().bind(label.visibleProperty());
    return label;
  }

  private static StackPane mark(Glyph glyph, String tone) {
    StackPane mark = new StackPane(Icons.of(glyph, 14));
    mark.getStyleClass().addAll("status-mark", tone);
    return mark;
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
