package com.example.datacraft.desktop.ui.component;

import java.util.function.BiConsumer;
import java.util.function.Supplier;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.layout.Pane;

/**
 * Mirrors a short list (a handful of DAGs, the last eight runs) into rows of a pane. A replaced
 * element updates its existing row; only additions and removals create or drop rows. Long lists use
 * a virtualised ListView or TableView instead.
 */
public final class Rows<T, R extends Node> {

  private final Pane pane;
  private final Supplier<R> create;
  private final BiConsumer<R, T> update;

  private Rows(Pane pane, Supplier<R> create, BiConsumer<R, T> update) {
    this.pane = pane;
    this.create = create;
    this.update = update;
  }

  public static <T, R extends Node> Rows<T, R> bind(
      Pane pane, ObservableList<T> items, Supplier<R> create, BiConsumer<R, T> update) {
    Rows<T, R> rows = new Rows<>(pane, create, update);
    items.addListener((ListChangeListener<T>) change -> rows.sync(items));
    rows.sync(items);
    return rows;
  }

  /** Updates every row again, e.g. so that "3 分钟前" moves on while the data stays the same. */
  @SuppressWarnings("unchecked")
  public void refresh() {
    for (Node child : pane.getChildren()) {
      update.accept((R) child, (T) child.getUserData());
    }
  }

  @SuppressWarnings("unchecked")
  private void sync(ObservableList<T> items) {
    var children = pane.getChildren();
    while (children.size() > items.size()) {
      children.removeLast();
    }
    for (int index = 0; index < items.size(); index++) {
      R row;
      if (index < children.size()) {
        row = (R) children.get(index);
      } else {
        row = create.get();
        children.add(row);
      }
      T item = items.get(index);
      if (row.getUserData() != item) {
        row.setUserData(item);
        update.accept(row, item);
      }
    }
  }
}
