package com.example.datacraft.desktop.ui.view;

import com.example.datacraft.desktop.service.BackendStatus;
import com.example.datacraft.desktop.ui.component.Controls;
import com.example.datacraft.desktop.ui.component.Donut;
import com.example.datacraft.desktop.ui.component.EmptyState;
import com.example.datacraft.desktop.ui.component.Icons;
import com.example.datacraft.desktop.ui.component.Icons.Glyph;
import com.example.datacraft.desktop.ui.component.Rows;
import com.example.datacraft.desktop.ui.component.RunBars;
import com.example.datacraft.desktop.ui.component.StatusRing;
import com.example.datacraft.desktop.ui.component.TimeStrip;
import com.example.datacraft.desktop.ui.component.Tones;
import com.example.datacraft.desktop.util.Formats;
import com.example.datacraft.desktop.viewmodel.DagRow;
import com.example.datacraft.desktop.viewmodel.Labels;
import com.example.datacraft.desktop.viewmodel.OverviewModel;
import com.example.datacraft.desktop.viewmodel.OverviewModel.Mix;
import com.example.datacraft.desktop.viewmodel.OverviewModel.RecentItem;
import com.example.datacraft.desktop.viewmodel.StatusModel;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Consumer;
import javafx.beans.binding.Bindings;
import javafx.beans.value.ObservableValue;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * 总览, read at a glance: a ring per backend and a donut of how the recent runs went; one lane per
 * pipeline with its duration bars and a play button; and the newest runs as dots on a time line.
 * Words are kept to names and figures; the rest is in tooltips.
 */
public final class OverviewView implements View {

  /** How many of the recent runs are also listed by name under the time strip. */
  static final int NAMED = 4;

  private final OverviewModel model;
  private final Clock clock;
  private final StackPane root = new StackPane();

  public OverviewView(
      OverviewModel model,
      StatusModel status,
      Clock clock,
      Consumer<String> openPipeline,
      Consumer<String> triggerPipeline,
      Consumer<String> openJob,
      Runnable openSettings) {
    this.model = model;
    this.clock = clock;

    HBox tiles =
        new HBox(
            16,
            backendTile("引擎 API", Glyph.ENGINE, status.engine()),
            backendTile("Airflow", Glyph.AIRFLOW, status.airflow()),
            mixTile());

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

    VBox lanes = new VBox(4);
    var laneRows =
        Rows.bind(lanes, model.dags(), () -> lane(openPipeline, triggerPipeline), this::updateLane);
    Label noDags = Controls.label("还没有流水线", "secondary");
    noDags
        .visibleProperty()
        .bind(Bindings.isEmpty(model.dags()).and(model.loaded()).and(model.problem().isEmpty()));
    noDags.managedProperty().bind(noDags.visibleProperty());
    VBox pipelines = new VBox(10, heading(Glyph.PIPELINES, "流水线"), problem, lanes, noDags);

    TimeStrip strip =
        new TimeStrip(Icons.of(Glyph.PIPELINES, 16), Icons.of(Glyph.JOBS, 16))
            .onPick(
                mark -> {
                  if (mark.lane() == 0) {
                    openPipeline.accept(mark.key());
                  } else {
                    openJob.accept(mark.key());
                  }
                });
    // The strip shows every recent run; by name, only the newest few.
    VBox recentRows = new VBox(2);
    javafx.collections.ObservableList<RecentItem> newest =
        javafx.collections.FXCollections.observableArrayList();
    var rows = Rows.bind(recentRows, newest, this::recentRow, this::updateRecentRow);
    Runnable showStrip =
        () -> {
          strip.set(marks(), clock.instant(), oldest());
          newest.setAll(model.recent().subList(0, Math.min(NAMED, model.recent().size())));
        };
    model
        .recent()
        .addListener((javafx.collections.ListChangeListener<RecentItem>) c -> showStrip.run());
    model
        .applied()
        .addListener(
            (property, before, now) -> {
              laneRows.refresh();
              rows.refresh();
              showStrip.run();
            });
    Label noRuns = Controls.label("还没有运行", "secondary");
    noRuns.visibleProperty().bind(Bindings.isEmpty(model.recent()).and(model.loaded()));
    noRuns.managedProperty().bind(noRuns.visibleProperty());
    strip.visibleProperty().bind(Bindings.isNotEmpty(model.recent()));
    strip.managedProperty().bind(strip.visibleProperty());
    VBox recent = new VBox(10, heading(Glyph.ACTIVITY, "最近运行"), strip, recentRows, noRuns);

    VBox page = new VBox(28, tiles, pipelines, recent);
    page.getStyleClass().add("view-content");
    ScrollPane scroll = new ScrollPane(page);
    scroll.setFitToWidth(true);

    EmptyState empty = new EmptyState().set(Glyph.LINK, "先连接你的引擎与 Airflow", "添加连接", openSettings);
    root.getChildren().addAll(scroll, empty);
    ObservableValue<Boolean> connected = model.connected();
    empty.visibleProperty().bind(model.connected().not());
    scroll.visibleProperty().bind(connected);
  }

  static HBox heading(Glyph glyph, String title) {
    HBox heading = Controls.row(8, Icons.of(glyph, 18), Controls.label(title, "section-title"));
    heading.getStyleClass().add("heading");
    return heading;
  }

  private static Node backendTile(String name, Glyph glyph, ObservableValue<BackendStatus> value) {
    StatusRing ring = new StatusRing(60, 4, Icons.of(glyph, 26));
    Label title = Controls.label(name, "tile-title");
    Label latency = Controls.label("", "figure");
    Label state = Controls.label("", "caption");
    VBox text = new VBox(2, title, latency, state);
    HBox tile = Controls.row(16, ring, text);
    tile.getStyleClass().add("tile");
    HBox.setHgrow(tile, Priority.ALWAYS);
    Tooltip tooltip = new Tooltip();
    Tooltip.install(tile, tooltip);
    Runnable show =
        () -> {
          BackendStatus status = value.getValue();
          boolean up = status.state() == BackendStatus.State.UP;
          ring.set(Tones.of(status), name + " " + Labels.status(status));
          latency.setText(up ? status.latency().map(Formats::latency).orElse("—") : "—");
          state.setText(up ? status.summary() : Labels.status(status));
          tooltip.setText(status.summary());
        };
    value.addListener((property, before, now) -> show.run());
    show.run();
    return tile;
  }

  private Node mixTile() {
    Label share = Controls.label("—", "donut-figure");
    Donut donut = new Donut(60, 7, List.of("success", "failed", "running", "waiting"), share);
    Label title = Controls.label("近期成功率", "tile-title");
    HBox legend = new HBox(12);
    Label[] counts = new Label[4];
    String[] tones = {"success", "failed", "running", "waiting"};
    String[] words = {"成功", "失败", "运行中", "等待"};
    for (int index = 0; index < 4; index++) {
      StatusRing dot = StatusRing.dot();
      dot.set(tones[index], words[index]);
      counts[index] = Controls.label("0", "latin");
      HBox entry = Controls.row(6, dot, counts[index]);
      Tooltip.install(entry, new Tooltip(words[index]));
      legend.getChildren().add(entry);
    }
    VBox text = new VBox(6, title, legend);
    HBox tile = Controls.row(16, donut, text);
    tile.getStyleClass().add("tile");
    HBox.setHgrow(tile, Priority.ALWAYS);
    Runnable show =
        () -> {
          Mix mix = model.mix().get();
          List<Integer> values = List.of(mix.success(), mix.failed(), mix.running(), mix.waiting());
          donut.set(values);
          for (int index = 0; index < 4; index++) {
            counts[index].setText(String.valueOf(values.get(index)));
            // A tone with no run says nothing: leave it out of the legend.
            Node entry = counts[index].getParent();
            entry.setVisible(values.get(index) > 0);
            entry.setManaged(values.get(index) > 0);
          }
          double rate = mix.successShare();
          share.setText(rate < 0 ? "—" : Math.round(rate * 100) + "%");
        };
    model.mix().addListener((property, before, now) -> show.run());
    show.run();
    return tile;
  }

  private HBox lane(Consumer<String> openPipeline, Consumer<String> triggerPipeline) {
    StackPane schedule = new StackPane();
    schedule.getStyleClass().add("lane-glyph");
    Tooltip scheduleTip = new Tooltip();
    Tooltip.install(schedule, scheduleTip);
    schedule.setUserData(scheduleTip);
    Label name = Controls.label("", "name", "latin");
    name.setMinWidth(120);
    name.setPrefWidth(220);
    Tooltip nameTip = new Tooltip();
    nameTip.textProperty().bind(name.textProperty());
    name.setTooltip(nameTip);
    RunBars bars = RunBars.compact(OverviewModel.RIBBON);
    StatusRing last = StatusRing.dot();
    Label ago = Controls.label("", "secondary");
    ago.setMinWidth(88);
    Label paused = Controls.chip(Glyph.PAUSE, "已暂停");
    Button play = Controls.iconButton(Icons.of(Glyph.PLAY), "触发运行", () -> {});
    play.addEventHandler(MouseEvent.MOUSE_CLICKED, MouseEvent::consume);
    HBox row = Controls.row(14, schedule, name, bars, last, ago, Controls.grow(), paused, play);
    row.getStyleClass().add("lane");
    play.setOnAction(
        event -> {
          if (row.getUserData() instanceof DagRow dag) {
            triggerPipeline.accept(dag.dagId());
          }
        });
    Controls.activatable(
        row,
        () -> {
          if (row.getUserData() instanceof DagRow dag) {
            openPipeline.accept(dag.dagId());
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
    Node paused = children.get(6);
    paused.setVisible(dag.dag().paused());
    paused.setManaged(dag.dag().paused());
    row.getStyleClass().remove("paused");
    if (dag.dag().paused()) {
      row.getStyleClass().add("paused");
    }
  }

  private List<TimeStrip.Mark> marks() {
    return model.recent().stream()
        .map(
            item ->
                new TimeStrip.Mark(
                    item.name(),
                    Tones.of(item.tone()),
                    item.startedAt(),
                    item.name()
                        + "\n"
                        + item.state()
                        + "  "
                        + Formats.when(item.startedAt(), clock.instant(), ZoneId.systemDefault())
                        + item.duration().map(d -> "  " + Formats.duration(d)).orElse(""),
                    item.pipeline() ? 0 : 1))
        .toList();
  }

  private String oldest() {
    return model.recent().stream()
        .map(RecentItem::startedAt)
        .min(java.time.Instant::compareTo)
        .map(at -> Formats.when(at, clock.instant(), ZoneId.systemDefault()))
        .orElse("");
  }

  private HBox recentRow() {
    StackPane kind = new StackPane();
    kind.getStyleClass().add("lane-glyph");
    Label name = Controls.label("", "name", "latin");
    name.setMinWidth(220);
    name.setPrefWidth(220);
    StatusRing state = StatusRing.dot();
    Label time = Controls.label("", "secondary");
    time.setMinWidth(96);
    Label duration = Controls.label("", "secondary", "latin");
    duration.setMinWidth(64);
    HBox row = Controls.row(14, kind, name, state, time, duration);
    row.getStyleClass().add("recent-row");
    return row;
  }

  private void updateRecentRow(HBox row, RecentItem item) {
    var children = row.getChildren();
    ((StackPane) children.get(0))
        .getChildren()
        .setAll(Icons.of(item.pipeline() ? Glyph.PIPELINES : Glyph.JOBS, 16));
    ((Label) children.get(1)).setText(item.name());
    ((StatusRing) children.get(2)).set(Tones.of(item.tone()), item.state());
    ((Label) children.get(3))
        .setText(Formats.when(item.startedAt(), clock.instant(), ZoneId.systemDefault()));
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
