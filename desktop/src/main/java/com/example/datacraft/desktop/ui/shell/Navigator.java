package com.example.datacraft.desktop.ui.shell;

import com.example.datacraft.desktop.ui.Motion;
import com.example.datacraft.desktop.ui.view.View;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Function;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.layout.StackPane;

/**
 * Switches the content area between the views. A view is created the first time it is opened
 * (lazily) and kept; only the shown view polls. Each switch is timed against its budget.
 */
public final class Navigator {

  /** The five views, in sidebar order; Ctrl+1 to Ctrl+5 follow it. */
  public enum Destination {
    OVERVIEW("overview", "总览"),
    JOBS("jobs", "作业"),
    PIPELINES("pipelines", "流水线"),
    ACTIVITY("activity", "活动"),
    SETTINGS("settings", "设置");

    private final String id;
    private final String title;

    Destination(String id, String title) {
      this.id = id;
      this.title = title;
    }

    public String id() {
      return id;
    }

    public String title() {
      return title;
    }

    public static Destination of(String id) {
      for (Destination destination : values()) {
        if (destination.id.equals(id)) {
          return destination;
        }
      }
      return OVERVIEW;
    }
  }

  static final long SWITCH_BUDGET_MS = 100;
  static final long FIRST_OPEN_BUDGET_MS = 200;
  private static final System.Logger LOG = System.getLogger(Navigator.class.getName());

  private final StackPane content;
  private final Function<Destination, View> factory;
  private final Map<Destination, View> views = new EnumMap<>(Destination.class);
  private final ObjectProperty<Destination> current = new SimpleObjectProperty<>();
  private boolean windowShown = true;

  public Navigator(StackPane content, Function<Destination, View> factory) {
    this.content = content;
    this.factory = factory;
  }

  public void go(Destination destination) {
    Destination before = current.get();
    if (destination == before) {
      return;
    }
    long started = System.nanoTime();
    boolean first = !views.containsKey(destination);
    View next = views.computeIfAbsent(destination, factory);
    if (before != null) {
      View previous = views.get(before);
      previous.hidden();
      Motion.finishFade(previous.node());
      show(previous.node(), false);
    }
    // Views stay in the scene once created and are only hidden: re-attaching a subtree makes
    // JavaFX match every CSS selector against all of its nodes again (about 50 ms for a table
    // view).
    if (!content.getChildren().contains(next.node())) {
      content.getChildren().add(next.node());
    }
    show(next.node(), true);
    current.set(destination);
    if (windowShown) {
      next.shown();
    }
    if (before != null) {
      Motion.fadeIn(next.node());
    }
    long elapsed = (System.nanoTime() - started) / 1_000_000;
    long budget = first ? FIRST_OPEN_BUDGET_MS : SWITCH_BUDGET_MS;
    // The view opened with the window is part of the start-up and counted there.
    boolean overBudget = before != null && elapsed > budget;
    LOG.log(
        overBudget ? System.Logger.Level.WARNING : System.Logger.Level.DEBUG,
        "Opening " + destination.id() + " took " + elapsed + " ms (budget " + budget + " ms)");
  }

  /**
   * Takes the hidden views out of the scene, e.g. before the theme changes: otherwise every hidden
   * view would be restyled with the shown one. Each is put back, in the new theme, when shown.
   */
  public void detachHidden() {
    View shown = view();
    content.getChildren().removeIf(node -> shown == null || node != shown.node());
  }

  private static void show(javafx.scene.Node node, boolean shown) {
    node.setVisible(shown);
    node.setManaged(shown);
  }

  /** The window was minimised or restored: the shown view stops or starts polling. */
  public void windowShown(boolean shown) {
    if (shown == windowShown) {
      return;
    }
    windowShown = shown;
    View view = view();
    if (view != null) {
      if (shown) {
        view.shown();
      } else {
        view.hidden();
      }
    }
  }

  public View view() {
    return current.get() == null ? null : views.get(current.get());
  }

  public ObjectProperty<Destination> current() {
    return current;
  }
}
