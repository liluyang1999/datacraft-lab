package com.example.datacraft.desktop.ui.component;

import com.example.datacraft.desktop.api.AirflowModels.Task;
import com.example.datacraft.desktop.api.AirflowModels.TaskInstance;
import com.example.datacraft.desktop.api.RunTone;
import com.example.datacraft.desktop.ui.Motion;
import com.example.datacraft.desktop.ui.component.GraphLayout.Placement;
import com.example.datacraft.desktop.util.Formats;
import com.example.datacraft.desktop.viewmodel.Labels;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.scene.Group;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.CubicCurve;
import javafx.scene.shape.Polygon;

/**
 * A DAG as it runs: one card per task, placed by {@link GraphLayout}, with curved edges to its
 * downstream tasks. A card's ring and border take the state of the task in the selected run; a
 * click opens that try's log. While a task runs, the edges into it flow. Cards are kept across
 * updates, so a poll only restyles what changed, and a card whose state changes pulses once.
 */
public final class TaskGraph extends Pane {

  static final double CARD_WIDTH = 176;
  static final double CARD_HEIGHT = 52;
  static final double COLUMN_GAP = 56;
  static final double ROW_GAP = 18;

  private final Group edges = new Group();
  private final Group cards = new Group();
  private final Map<String, Card> byId = new LinkedHashMap<>();
  private final List<Edge> edgeList = new ArrayList<>();
  private final DoubleProperty flowOffset = new SimpleDoubleProperty();
  private final Timeline flow;
  private final Consumer<TaskInstance> open;
  private String shapeKey = "";
  private boolean resumed;

  private record Edge(String from, String to, CubicCurve curve, Polygon head) {}

  public TaskGraph(Consumer<TaskInstance> open) {
    this.open = open;
    getStyleClass().add("task-graph");
    getChildren().addAll(edges, cards);
    flow =
        new Timeline(
            new KeyFrame(javafx.util.Duration.ZERO, new KeyValue(flowOffset, 0)),
            new KeyFrame(
                javafx.util.Duration.millis(700),
                new KeyValue(flowOffset, -14, Interpolator.LINEAR)));
    flow.setCycleCount(Animation.INDEFINITE);
  }

  /**
   * Shows a run: {@code structure} is the DAG's shape (may be empty while unknown), {@code
   * instances} its task instances. Without a shape the tasks stand in a row by start time.
   */
  public void set(List<Task> structure, List<TaskInstance> instances) {
    List<GraphLayout.Node> nodes = new ArrayList<>();
    Map<String, String> names = new HashMap<>();
    for (Task task : structure) {
      nodes.add(new GraphLayout.Node(task.taskId(), task.downstream()));
      names.put(task.taskId(), task.displayName());
    }
    Map<String, List<TaskInstance>> byTask = new LinkedHashMap<>();
    instances.stream()
        .sorted(Comparator.comparing(task -> task.startDate().orElse(Instant.MAX)))
        .forEach(task -> byTask.computeIfAbsent(task.taskId(), id -> new ArrayList<>()).add(task));
    for (Map.Entry<String, List<TaskInstance>> entry : byTask.entrySet()) {
      if (!names.containsKey(entry.getKey())) {
        // A task the shape does not know (no shape yet): it stands right of the others.
        nodes.add(new GraphLayout.Node(entry.getKey(), List.of()));
        names.put(entry.getKey(), entry.getValue().getFirst().displayName());
      }
    }
    List<Placement> placements = structure.isEmpty() ? row(nodes) : GraphLayout.place(nodes);
    String key = shapeKey(nodes, placements);
    if (!key.equals(shapeKey)) {
      rebuild(nodes, placements, names);
      shapeKey = key;
    }
    for (Card card : byId.values()) {
      card.show(byTask.getOrDefault(card.id, List.of()));
    }
    for (Edge edge : edgeList) {
      Card target = byId.get(edge.to());
      boolean flowing = target != null && target.tone == RunTone.RUNNING;
      edge.curve().getStyleClass().remove("flowing");
      if (flowing) {
        edge.curve().getStyleClass().add("flowing");
        edge.curve().strokeDashOffsetProperty().bind(flowOffset);
      } else {
        edge.curve().strokeDashOffsetProperty().unbind();
        edge.curve().setStrokeDashOffset(0);
      }
    }
    updateFlow();
  }

  /** The view is on screen: edges into running tasks may flow. */
  public void resume() {
    resumed = true;
    updateFlow();
  }

  /** The view is hidden: nothing moves. */
  public void pause() {
    resumed = false;
    updateFlow();
  }

  private void updateFlow() {
    boolean any =
        edgeList.stream().anyMatch(edge -> edge.curve().getStyleClass().contains("flowing"));
    if (any && resumed && !Motion.reduced()) {
      flow.play();
    } else {
      flow.stop();
      flowOffset.set(0);
    }
  }

  /** The task ids in the order they are drawn. */
  public List<String> taskIds() {
    return List.copyOf(byId.keySet());
  }

  private static List<Placement> row(List<GraphLayout.Node> nodes) {
    List<Placement> placements = new ArrayList<>();
    for (int index = 0; index < nodes.size(); index++) {
      placements.add(new Placement(nodes.get(index).id(), index, 0));
    }
    return placements;
  }

  private static String shapeKey(List<GraphLayout.Node> nodes, List<Placement> placements) {
    StringBuilder key = new StringBuilder();
    nodes.forEach(node -> key.append(node.id()).append(node.downstream()).append(';'));
    placements.forEach(place -> key.append(place).append(';'));
    return key.toString();
  }

  private void rebuild(
      List<GraphLayout.Node> nodes, List<Placement> placements, Map<String, String> names) {
    Map<String, Card> previous = new HashMap<>(byId);
    byId.clear();
    cards.getChildren().clear();
    edges.getChildren().clear();
    edgeList.clear();
    double width = 0;
    double height = 0;
    // Cards are kept in the order they are drawn, left to right and then top to bottom: Airflow
    // lists a DAG's tasks by id, not in the order they run.
    List<Placement> drawn =
        placements.stream()
            .sorted(Comparator.comparingInt(Placement::column).thenComparingInt(Placement::row))
            .toList();
    for (Placement place : drawn) {
      Card card = previous.getOrDefault(place.id(), new Card(place.id(), names.get(place.id())));
      double x = place.column() * (CARD_WIDTH + COLUMN_GAP);
      double y = place.row() * (CARD_HEIGHT + ROW_GAP);
      card.relocate(x, y);
      byId.put(place.id(), card);
      cards.getChildren().add(card);
      width = Math.max(width, x + CARD_WIDTH);
      height = Math.max(height, y + CARD_HEIGHT);
    }
    for (GraphLayout.Node node : nodes) {
      Card from = byId.get(node.id());
      for (String next : node.downstream()) {
        Card to = byId.get(next);
        if (from != null && to != null) {
          edgeList.add(edge(node.id(), next, from, to));
        }
      }
    }
    edgeList.forEach(edge -> edges.getChildren().addAll(edge.curve(), edge.head()));
    setMinSize(width, height);
    setPrefSize(width, height);
  }

  private static Edge edge(String fromId, String toId, Card from, Card to) {
    double startX = from.getLayoutX() + CARD_WIDTH;
    double startY = from.getLayoutY() + CARD_HEIGHT / 2;
    double endX = to.getLayoutX() - 6;
    double endY = to.getLayoutY() + CARD_HEIGHT / 2;
    double bend = Math.max(24, (endX - startX) / 2);
    CubicCurve curve =
        new CubicCurve(startX, startY, startX + bend, startY, endX - bend, endY, endX, endY);
    curve.getStyleClass().add("graph-edge");
    Polygon head = new Polygon(endX, endY - 4, endX + 6, endY, endX, endY + 4);
    head.getStyleClass().add("graph-arrow");
    return new Edge(fromId, toId, curve, head);
  }

  /** One task: a ring in its tone, its name, and how long it took or what it waits for. */
  private final class Card extends HBox {
    final String id;
    private final StatusRing ring = StatusRing.dot();
    private final Label name = new Label();
    private final Label meta = new Label();
    private final Tooltip tip = new Tooltip();
    private RunTone tone;
    private TaskInstance current;

    Card(String id, String displayName) {
      this.id = id;
      getStyleClass().addAll("task-card", "none");
      name.setText(displayName == null ? id : displayName);
      name.getStyleClass().addAll("task-name", "latin");
      meta.getStyleClass().add("caption");
      VBox text = new VBox(2, name, meta);
      getChildren().addAll(ring, text);
      setMinSize(CARD_WIDTH, CARD_HEIGHT);
      setPrefSize(CARD_WIDTH, CARD_HEIGHT);
      setMaxSize(CARD_WIDTH, CARD_HEIGHT);
      Tooltip.install(this, tip);
      Controls.activatable(
          this,
          () -> {
            if (current != null && current.tryNumber() >= 1) {
              open.accept(current);
            }
          });
    }

    void show(List<TaskInstance> tries) {
      TaskInstance shown = representative(tries);
      current = shown;
      setUserData(shown);
      RunTone next = shown == null ? RunTone.NONE : shown.tone();
      String toneClass = Tones.of(next);
      getStyleClass().removeAll(Tones.ALL);
      getStyleClass().add(toneClass);
      getStyleClass().remove("openable");
      if (shown != null && shown.tryNumber() >= 1) {
        getStyleClass().add("openable");
      }
      String state = shown == null ? "未开始" : Labels.state(shown.state().orElse(null));
      ring.set(toneClass, state);
      String detail =
          shown == null
              ? "未开始"
              : shown.duration().map(Formats::duration).orElse(state)
                  + (tries.size() > 1 ? "  ×" + tries.size() : "")
                  + (shown.tryNumber() > 1 ? "  第 " + shown.tryNumber() + " 次" : "");
      meta.setText(detail);
      tip.setText(
          name.getText()
              + "\n"
              + state
              + (shown == null
                  ? ""
                  : "  ·  尝试 " + Math.max(1, shown.tryNumber()) + " / " + (shown.maxTries() + 1))
              + (shown != null && shown.tryNumber() >= 1 ? "\n点按查看日志" : ""));
      setAccessibleText(name.getText() + " " + state);
      if (tone != null && tone != next && !Motion.reduced() && getScene() != null) {
        ScaleTransition pulse = new ScaleTransition(javafx.util.Duration.millis(140), this);
        pulse.setToX(1.06);
        pulse.setToY(1.06);
        pulse.setAutoReverse(true);
        pulse.setCycleCount(2);
        pulse.play();
      }
      tone = next;
    }

    /** Of several tries or mapped instances, the one that says most: failed, then running. */
    private static TaskInstance representative(List<TaskInstance> tries) {
      return tries.stream()
          .max(
              Comparator.comparingInt((TaskInstance task) -> weight(task.tone()))
                  .thenComparing(task -> task.startDate().orElse(Instant.MIN)))
          .orElse(null);
    }

    private static int weight(RunTone tone) {
      return switch (tone) {
        case FAILED -> 4;
        case RUNNING -> 3;
        case WAITING -> 2;
        case SUCCESS -> 1;
        case NONE -> 0;
      };
    }
  }
}
