package com.example.datacraft.desktop.ui.component;

import com.example.datacraft.desktop.api.RunTone;
import java.util.ArrayList;
import java.util.List;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

/**
 * The run ribbon: the newest runs as 6 x 14 segments, 2 apart, newest on the right; empty slots use
 * the "none" tone. One tooltip per ribbon follows the pointer, so a table of ribbons does not
 * create a tooltip per segment.
 */
public final class RunRibbon extends HBox {

  /** One run: its tone and the text the tooltip shows for it. */
  public record Segment(RunTone tone, String description) {}

  static final double WIDTH = 6;
  static final double HEIGHT = 14;
  static final double GAP = 2;

  private final List<Rectangle> slots = new ArrayList<>();
  private final List<Segment> shown = new ArrayList<>();
  private final Tooltip tooltip = new Tooltip();

  public RunRibbon(int size) {
    getStyleClass().add("run-ribbon");
    for (int index = 0; index < size; index++) {
      Rectangle slot = new Rectangle(WIDTH, HEIGHT);
      slot.getStyleClass().addAll("ribbon-segment", "none");
      slots.add(slot);
    }
    getChildren().addAll(slots);
    setMinWidth(size * WIDTH + (size - 1) * GAP);
    setMaxWidth(USE_PREF_SIZE);
    tooltip.setShowDelay(Duration.millis(250));
    tooltip.setText("尚无运行");
    Tooltip.install(this, tooltip);
    setOnMouseMoved(
        event -> {
          Segment segment = segmentAt((int) (event.getX() / (WIDTH + GAP)));
          tooltip.setText(segment == null ? "尚无运行" : segment.description());
        });
  }

  /** Shows the runs, newest first; only the slots whose tone changed are restyled. */
  public void set(List<Segment> newestFirst) {
    int size = slots.size();
    shown.clear();
    for (int index = 0; index < Math.min(size, newestFirst.size()); index++) {
      shown.add(newestFirst.get(index));
    }
    for (int slot = 0; slot < size; slot++) {
      Segment segment = segmentAt(slot);
      String tone = segment == null ? "none" : Tones.of(segment.tone());
      Rectangle rectangle = slots.get(slot);
      if (!rectangle.getStyleClass().contains(tone)) {
        rectangle.getStyleClass().removeAll(Tones.ALL);
        rectangle.getStyleClass().add(tone);
      }
    }
  }

  /** The segment drawn in a slot (0 is the leftmost), or null for an empty slot. */
  private Segment segmentAt(int slot) {
    int fromNewest = slots.size() - 1 - slot;
    return fromNewest >= 0 && fromNewest < shown.size() ? shown.get(fromNewest) : null;
  }
}
