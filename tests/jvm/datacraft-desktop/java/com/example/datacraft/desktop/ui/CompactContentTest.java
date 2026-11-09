package com.example.datacraft.desktop.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.datacraft.desktop.api.AirflowModels.Task;
import com.example.datacraft.desktop.app.AppContext;
import com.example.datacraft.desktop.app.AppPaths;
import com.example.datacraft.desktop.ui.component.Controls;
import com.example.datacraft.desktop.ui.component.TaskGraph;
import com.example.datacraft.desktop.ui.shell.Dialogs;
import com.example.datacraft.desktop.ui.shell.Drawer;
import com.example.datacraft.desktop.ui.theme.ThemeManager;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javafx.beans.property.SimpleStringProperty;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompactContentTest {
  @TempDir Path home;

  @BeforeAll
  static void toolkit() {
    FxTesting.start();
  }

  @Test
  void detailsActionUsesTheLatestCompleteValueAndHidesWhenEmpty() {
    FxTesting.onFx(
        () -> {
          SimpleStringProperty detail = new SimpleStringProperty("");
          AtomicReference<String> opened = new AtomicReference<>();
          Button button =
              Controls.details("查看详情", "诊断", detail, (title, text) -> opened.set(title + text));
          assertFalse(button.isVisible());
          assertFalse(button.isManaged());
          detail.set("完整内容".repeat(200));
          assertTrue(button.isFocusTraversable());
          assertTrue(button.isVisible());
          assertEquals("查看详情", button.getText());
          button.fire();
          assertEquals("诊断" + detail.get(), opened.get());
          detail.set(" ");
          assertFalse(button.isManaged());
        });
  }

  @Test
  void graphAndDrawerNamesCannotDisplaceTheirControls() {
    try (AppContext context = AppContext.open(new AppPaths(home))) {
      FxTesting.onFx(
          () -> {
            String name = "很长的任务名称".repeat(100);
            TaskGraph graph = new TaskGraph(task -> {});
            graph.set(List.of(new Task("id", name, List.of(), "operator")), List.of());
            Drawer drawer = new Drawer();
            drawer.open(name, graph, () -> {});
            StackPane root = new StackPane(drawer);
            new Scene(root, 960, 640);
            ThemeManager theme = new ThemeManager(context.preferences());
            root.getStylesheets().setAll(theme.stylesheets());
            theme.style(root);
            root.resize(960, 640);
            root.applyCss();
            root.layout();
            Label taskName = (Label) graph.lookup(".task-name");
            assertTrue(taskName.getWidth() <= 150, "task name stays inside its card");
            Label title = (Label) drawer.lookup(".section-title");
            assertTrue(title.getWidth() <= 440, "drawer title leaves its close action visible");
            assertEquals(name, title.getTooltip().getText());
            drawer.close();
            graph.pause();
          });
    }
    FxTesting.assertHealthy();
  }

  @Test
  void detailsDialogKeepsLongContentScrollableAndComplete() {
    try (AppContext context = AppContext.open(new AppPaths(home))) {
      FxTesting.onFx(
          () -> {
            Stage owner = new Stage();
            owner.setScene(new Scene(new StackPane(), 960, 640));
            owner.show();
            try {
              Dialogs dialogs = new Dialogs(new ThemeManager(context.preferences()));
              dialogs.owner(owner);
              String content = "完整的诊断信息 😀\n".repeat(500);
              dialogs.details("诊断详情", content);
              Window window =
                  Window.getWindows().stream()
                      .filter(
                          candidate ->
                              candidate != owner
                                  && candidate instanceof Stage stage
                                  && stage.getOwner() == owner)
                      .findFirst()
                      .orElseThrow();
              TextArea area = (TextArea) window.getScene().lookup(".text-area");
              assertEquals(content, area.getText());
              assertEquals(
                  content, area.queryAccessibleAttribute(javafx.scene.AccessibleAttribute.TEXT));
              assertFalse(area.isEditable());
              assertTrue(window.getWidth() <= owner.getWidth());
              assertTrue(window.getHeight() <= owner.getHeight());
              window.hide();
            } finally {
              owner.close();
            }
          });
    }
    FxTesting.assertHealthy();
  }
}
