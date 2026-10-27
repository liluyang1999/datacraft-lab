package com.example.datacraft.desktop.ui.component;

import com.example.datacraft.desktop.api.RunTone;
import com.example.datacraft.desktop.ui.Motion;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Region;
import javafx.scene.shape.Rectangle;

/**
 * The newest runs as bars, newest on the right: the colour is the outcome, the height the duration
 * (relative to the longest run shown, on a square-root scale so short runs stay visible). A run
 * still going stands at full height. One tooltip follows the pointer; a click picks a run when the
 * bars are selectable. A bar whose height changes grows to it in 240 ms.
 */
public final class RunBars extends Region {

  /** One run: its tone, its duration if finished, the tooltip text, and a key to select it by. */
  public record Segment(RunTone tone, Optional<Duration> duration, String description, String key) {
    public Segment(RunTone tone, String description) {
      this(tone, Optional.empty(), description, description);
    }
  }

  static final double MIN_HEIGHT = 4;

  private final int size;
  private final double barWidth;
  private final double gap;
  private final double maxHeight;
  private final List<Rectangle> bars = new ArrayList<>();
  private final Timeline[] growing;
  private final List<Segment> shown = new ArrayList<>();
  private final Tooltip tooltip = new Tooltip();
  private Consumer<Segment> onPick;
  private String selectedKey;

  public RunBars(int size, double barWidth, double gap, double maxHeight) {
    this.size = size;
    this.barWidth = barWidth;
    this.gap = gap;
    this.maxHeight = maxHeight;
    this.growing = new Timeline[size];
    getStyleClass().add("run-bars");
    for (int index = 0; index < size; index++) {
      Rectangle bar = new Rectangle(barWidth, MIN_HEIGHT);
      bar.getStyleClass().addAll("run-bar", "none");
      bar.setManaged(false);
      bar.setX(index * (barWidth + gap));
      bar.setY(maxHeight - MIN_HEIGHT);
      bars.add(bar);
    }
    getChildren().addAll(bars);
    double width = size * barWidth + (size - 1) * gap;
    setMinSize(width, maxHeight);
    setPrefSize(width, maxHeight);
    setMaxSize(width, maxHeight);
    tooltip.setShowDelay(javafx.util.Duration.millis(200));
    tooltip.setText("尚无运行");
    Tooltip.install(this, tooltip);
    setOnMouseMoved(
        event -> {
          Segment segment = segmentAt(slotAt(event.getX()));
          tooltip.setText(segment == null ? "尚无运行" : segment.description());
        });
    setOnMouseClicked(
        event -> {
          Segment segment = segmentAt(slotAt(event.getX()));
          if (onPick != null && segment != null && event.getButton() == MouseButton.PRIMARY) {
            onPick.accept(segment);
          }
        });
  }

  /** The compact bars of a list row. */
  public static RunBars compact(int size) {
    return new RunBars(size, 6, 2, 22);
  }

  /**
   * Makes the bars selectable: a click on a run calls {@code pick} with it; with the focus, the
   * left and right arrow keys pick the older or newer neighbour of the marked run.
   */
  public RunBars onPick(Consumer<Segment> pick) {
    onPick = pick;
    getStyleClass().add("selectable");
    setFocusTraversable(true);
    setOnMousePressed(event -> requestFocus());
    addEventHandler(
        javafx.scene.input.KeyEvent.KEY_PRESSED,
        event -> {
          int step =
              switch (event.getCode()) {
                case LEFT -> 1; // older runs stand further left
                case RIGHT -> -1;
                default -> 0;
              };
          if (step == 0 || shown.isEmpty()) {
            return;
          }
          int at = -1;
          for (int index = 0; index < shown.size(); index++) {
            if (shown.get(index).key().equals(selectedKey)) {
              at = index;
            }
          }
          int next = Math.max(0, Math.min(shown.size() - 1, at < 0 ? 0 : at + step));
          onPick.accept(shown.get(next));
          event.consume();
        });
    return this;
  }

  /** Marks the run with this key, or none for null. */
  public void select(String key) {
    selectedKey = key;
    for (int slot = 0; slot < size; slot++) {
      Segment segment = segmentAt(slot);
      boolean selected = segment != null && segment.key().equals(key);
      bars.get(slot).getStyleClass().remove("selected");
      if (selected) {
        bars.get(slot).getStyleClass().add("selected");
      }
    }
  }

  /** Shows the runs, newest first. Only bars whose tone or height changed are touched. */
  public void set(List<Segment> newestFirst) {
    shown.clear();
    shown.addAll(newestFirst.subList(0, Math.min(size, newestFirst.size())));
    double longest =
        shown.stream()
            .map(segment -> segment.duration().map(Duration::toMillis).orElse(0L))
            .max(Long::compare)
            .orElse(0L);
    boolean animate = !Motion.reduced() && getScene() != null;
    for (int slot = 0; slot < size; slot++) {
      Segment segment = segmentAt(slot);
      Rectangle bar = bars.get(slot);
      String tone = segment == null ? "none" : Tones.of(segment.tone());
      if (!bar.getStyleClass().contains(tone)) {
        bar.getStyleClass().removeAll(Tones.ALL);
        bar.getStyleClass().add(tone);
      }
      double height = heightOf(segment, longest, maxHeight);
      if (growing[slot] != null) {
        growing[slot].stop(); // a newer answer replaces the target of a running growth
        growing[slot] = null;
      }
      if (Math.abs(bar.getHeight() - height) > 0.1) {
        if (animate) {
          growing[slot] = grow(bar, height);
        } else {
          bar.setHeight(height);
          bar.setY(maxHeight - height);
        }
      }
    }
    select(selectedKey);
  }

  /** How many runs are shown. */
  public int count() {
    return shown.size();
  }

  /** The runs shown, newest first. */
  public List<Segment> segments() {
    return List.copyOf(shown);
  }

  /** The height of a bar: empty slots and runs without a time are short, running ones full. */
  static double heightOf(Segment segment, double longestMillis, double maxHeight) {
    if (segment == null) {
      return MIN_HEIGHT;
    }
    if (segment.duration().isEmpty()) {
      return segment.tone().active() ? maxHeight : MIN_HEIGHT * 2;
    }
    if (longestMillis <= 0) {
      return maxHeight;
    }
    double share = Math.sqrt(segment.duration().get().toMillis() / longestMillis);
    return MIN_HEIGHT * 2 + (maxHeight - MIN_HEIGHT * 2) * Math.min(1, share);
  }

  private Timeline grow(Rectangle bar, double height) {
    Timeline timeline =
        new Timeline(
            new KeyFrame(
                javafx.util.Duration.millis(240),
                new KeyValue(bar.heightProperty(), height, Interpolator.EASE_OUT),
                new KeyValue(bar.yProperty(), maxHeight - height, Interpolator.EASE_OUT)));
    timeline.play();
    return timeline;
  }

  private int slotAt(double x) {
    return (int) Math.floor(x / (barWidth + gap));
  }

  /** The run drawn in a slot (0 is the leftmost), or null for an empty slot. */
  private Segment segmentAt(int slot) {
    int fromNewest = size - 1 - slot;
    return fromNewest >= 0 && fromNewest < shown.size() ? shown.get(fromNewest) : null;
  }
}
