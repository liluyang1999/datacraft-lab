package com.example.datacraft.desktop.ui.view;

import com.example.datacraft.desktop.service.BackendStatus;
import com.example.datacraft.desktop.ui.component.Controls;
import com.example.datacraft.desktop.ui.component.EmptyState;
import com.example.datacraft.desktop.ui.component.Rows;
import com.example.datacraft.desktop.ui.component.RunRibbon;
import com.example.datacraft.desktop.ui.component.StatusBadge;
import com.example.datacraft.desktop.ui.component.Tones;
import com.example.datacraft.desktop.util.Formats;
import com.example.datacraft.desktop.viewmodel.DagRow;
import com.example.datacraft.desktop.viewmodel.Labels;
import com.example.datacraft.desktop.viewmodel.OverviewModel;
import com.example.datacraft.desktop.viewmodel.OverviewModel.RecentItem;
import com.example.datacraft.desktop.viewmodel.StatusModel;
import java.time.Clock;
import java.time.ZoneId;
import java.util.function.Consumer;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * 总览: both backends on one line each, every DAG with its run ribbon, and the newest runs. The
 * ribbons are the one bold element; everything else is text and thin rules.
 */
public final class OverviewView implements View {

  private final OverviewModel model;
  private final Clock clock;
  private final StackPane root = new StackPane();

  public OverviewView(
      OverviewModel model,
      StatusModel status,
      Clock clock,
      Consumer<String> openPipeline,
      Runnable openSettings) {
    this.model = model;
    this.clock = clock;

    VBox page = new VBox(24);
    page.getStyleClass().add("view-content");
    page.getChildren().addAll(backends(status), Controls.rule());

    Label problem = Controls.label("", "notice");
    problem.setWrapText(true);
    problem.setMaxWidth(Double.MAX_VALUE);
    Runnable showProblem =
        () -> {
          String text = model.problem().get();
          boolean stale = model.staleSince().get() != null && !model.dags().isEmpty();
          problem.setText(
              stale
                  ? "数据停在 "
                      + Formats.clock(model.staleSince().get(), ZoneId.systemDefault())
                      + "。"
                      + text
                  : text);
          boolean visible = !text.isEmpty() && model.connected().get();
          problem.setVisible(visible);
          problem.setManaged(visible);
        };
    model.problem().addListener((property, before, now) -> showProblem.run());
    model.staleSince().addListener((property, before, now) -> showProblem.run());
    showProblem.run();

    VBox dags = new VBox();
    var dagRows = Rows.bind(dags, model.dags(), () -> dagRow(openPipeline), this::updateDagRow);
    Label noDags = Controls.label("Airflow 里还没有 DAG。", "secondary");
    noDags
        .visibleProperty()
        .bind(
            javafx.beans.binding.Bindings.isEmpty(model.dags())
                .and(model.loaded())
                .and(model.problem().isEmpty()));
    noDags.managedProperty().bind(noDags.visibleProperty());
    VBox pipelines = new VBox(12, Controls.label("流水线", "section-title"), problem, dags, noDags);

    VBox recent = new VBox();
    var recentRows = Rows.bind(recent, model.recent(), this::recentRow, this::updateRecentRow);
    model
        .applied()
        .addListener(
            (property, before, now) -> {
              dagRows.refresh();
              recentRows.refresh();
            });
    Label noRuns = Controls.label("还没有运行记录。运行一个作业或触发一条流水线后，它会出现在这里。", "secondary");
    noRuns.setWrapText(true);
    noRuns
        .visibleProperty()
        .bind(javafx.beans.binding.Bindings.isEmpty(model.recent()).and(model.loaded()));
    noRuns.managedProperty().bind(noRuns.visibleProperty());
    VBox runs = new VBox(12, Controls.label("最近运行", "section-title"), recent, noRuns);

    page.getChildren().addAll(pipelines, Controls.rule(), runs);
    ScrollPane scroll = new ScrollPane(page);
    scroll.setFitToWidth(true);

    EmptyState empty =
        new EmptyState().set("还没有连接配置。添加一个连接后，这里会显示两个后台与各条流水线的状态。", "添加连接", openSettings);
    root.getChildren().addAll(scroll, empty);
    ObservableValue<Boolean> connected = model.connected();
    empty.visibleProperty().bind(model.connected().not());
    scroll.visibleProperty().bind(connected);
  }

  private static Node backends(StatusModel status) {
    HBox line =
        new HBox(48, backend("引擎 API", status.engine()), backend("Airflow", status.airflow()));
    line.setAlignment(Pos.CENTER_LEFT);
    return line;
  }

  private static Node backend(String name, ObservableValue<BackendStatus> value) {
    Label title = Controls.label(name, "section-title");
    StatusBadge badge = new StatusBadge();
    Label latency = Controls.label("", "latin");
    Label summary = Controls.label("", "secondary");
    HBox line = Controls.row(12, title, badge, latency, summary);
    javafx.scene.control.Tooltip tooltip = new javafx.scene.control.Tooltip();
    javafx.scene.control.Tooltip.install(line, tooltip);
    Runnable show =
        () -> {
          BackendStatus status = value.getValue();
          badge.set(Tones.of(status), Labels.status(status));
          latency.setText(
              status.state() == BackendStatus.State.UP
                  ? status.latency().map(Formats::latency).orElse("")
                  : "");
          summary.setText(status.state() == BackendStatus.State.UP ? status.summary() : "");
          tooltip.setText(status.summary());
        };
    value.addListener((property, before, now) -> show.run());
    show.run();
    return line;
  }

  private HBox dagRow(Consumer<String> openPipeline) {
    Label name = Controls.label("", "name");
    name.setMinWidth(240);
    name.setPrefWidth(240);
    Label schedule = Controls.label("", "secondary");
    schedule.setMinWidth(96);
    schedule.setPrefWidth(96);
    RunRibbon ribbon = new RunRibbon(OverviewModel.RIBBON);
    StatusBadge last = new StatusBadge();
    Label ago = Controls.label("", "secondary");
    Label paused = Controls.label("", "caption");
    HBox row = Controls.row(16, name, schedule, ribbon, last, ago, Controls.grow(), paused);
    row.setMinHeight(44);
    row.getStyleClass().add("overview-row");
    row.setStyle("-fx-cursor: hand;");
    row.setOnMouseClicked(
        event -> {
          if (event.getButton() == MouseButton.PRIMARY && row.getUserData() instanceof DagRow dag) {
            openPipeline.accept(dag.dagId());
          }
        });
    return row;
  }

  private void updateDagRow(HBox row, DagRow dag) {
    var children = row.getChildren();
    ((Label) children.get(0)).setText(dag.dag().displayName());
    ((Label) children.get(1)).setText(Labels.schedule(dag.dag().schedule()));
    ((RunRibbon) children.get(2)).set(Segments.ofDagRuns(dag.runs(), clock.instant()));
    StatusBadge last = (StatusBadge) children.get(3);
    Label ago = (Label) children.get(4);
    dag.latest()
        .ifPresentOrElse(
            run -> {
              last.set(Tones.of(run.tone()), "上次" + Labels.state(run.state()));
              ago.setText(Formats.ago(run.startDate().orElse(run.runAfter()), clock.instant()));
            },
            () -> {
              last.set("none", "尚未运行");
              ago.setText("");
            });
    ((Label) children.get(6)).setText(dag.dag().paused() ? "调度已暂停" : "");
  }

  private HBox recentRow() {
    Label name = Controls.label("", "name");
    name.setMinWidth(240);
    name.setPrefWidth(240);
    Label kind = Controls.label("", "caption");
    kind.setMinWidth(56);
    Label time = Controls.label("", "secondary");
    time.setMinWidth(96);
    StatusBadge state = new StatusBadge();
    state.setMinWidth(96);
    Label duration = Controls.label("", "secondary");
    duration.setMinWidth(80);
    duration.setAlignment(Pos.CENTER_RIGHT);
    HBox row = Controls.row(16, name, kind, time, state, duration);
    row.setMinHeight(36);
    return row;
  }

  private void updateRecentRow(HBox row, RecentItem item) {
    var children = row.getChildren();
    ((Label) children.get(0)).setText(item.name());
    ((Label) children.get(1)).setText(item.pipeline() ? "流水线" : "作业");
    ((Label) children.get(2))
        .setText(Formats.when(item.startedAt(), clock.instant(), ZoneId.systemDefault()));
    ((StatusBadge) children.get(3)).set(Tones.of(item.tone()), item.state());
    ((Label) children.get(4)).setText(item.duration().map(Formats::duration).orElse(""));
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
