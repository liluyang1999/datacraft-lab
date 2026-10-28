package com.example.datacraft.desktop.ui.component;

import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Keeps native accessibility traversal bounded as well as visual scrolling. All retained source
 * lines remain reachable; appends follow the last page without disturbing a reader on an earlier
 * page. Owned and updated on the FX thread, like the source list.
 */
public final class LogPager extends VBox implements AutoCloseable {
  private static final int PAGE_SIZE = 100;

  private final ObservableList<String> source;
  private final ListView<String> lines = new ListView<>(FXCollections.observableArrayList());
  private final Label position = Controls.label("", "caption");
  private int page;
  private int count;
  private final Button first = Controls.button("首页", () -> go(0));
  private final Button previous = Controls.button("上一页", () -> go(page - 1));
  private final Button next = Controls.button("下一页", () -> go(page + 1));
  private final Button last = Controls.button("末页", () -> go(lastPage()));
  private final ListChangeListener<String> changed = change -> refresh();

  public LogPager(ObservableList<String> source) {
    this.source = source;
    getStyleClass().add("log-pager");
    setSpacing(8);
    lines.getStyleClass().add("log-view");
    lines.setAccessibleText("本页日志");
    lines.setCellFactory(
        view ->
            new ListCell<>() {
              @Override
              protected void updateItem(String line, boolean empty) {
                super.updateItem(line, empty);
                setText(empty ? null : line);
              }
            });
    position.setMaxWidth(Double.MAX_VALUE);
    position.setAlignment(Pos.CENTER);
    HBox.setHgrow(position, Priority.ALWAYS);
    getChildren().addAll(lines, Controls.row(6, first, previous, position, next, last));
    VBox.setVgrow(lines, Priority.ALWAYS);
    source.addListener(changed);
    refresh();
  }

  public ListView<String> lines() {
    return lines;
  }

  public int lineCount() {
    return count;
  }

  private int lastPage() {
    return Math.max(0, (count - 1) / PAGE_SIZE);
  }

  private void refresh() {
    boolean following = page == lastPage();
    count = source.size();
    page = following ? lastPage() : Math.min(page, lastPage());
    render();
    if (following && count > 0) {
      lines.scrollTo(lines.getItems().size() - 1);
    }
  }

  private void go(int target) {
    page = Math.clamp(target, 0, lastPage());
    render();
    lines.scrollTo(page == lastPage() ? lines.getItems().size() - 1 : 0);
  }

  private void render() {
    int start = page * PAGE_SIZE;
    int end = Math.min(start + PAGE_SIZE, count);
    var visible = source.subList(start, end);
    if (!lines.getItems().equals(visible)) {
      lines.getItems().setAll(visible);
    }
    position.setText(count == 0 ? "0 行" : (start + 1) + "–" + end + " / " + count + " 行");
    first.setDisable(page == 0);
    previous.setDisable(page == 0);
    next.setDisable(page == lastPage());
    last.setDisable(page == lastPage());
  }

  @Override
  public void close() {
    source.removeListener(changed);
  }
}
