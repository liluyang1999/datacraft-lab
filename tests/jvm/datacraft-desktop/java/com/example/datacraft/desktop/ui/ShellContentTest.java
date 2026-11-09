package com.example.datacraft.desktop.ui;

import static com.example.datacraft.desktop.ui.FxTesting.find;
import static com.example.datacraft.desktop.ui.FxTesting.onFx;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.datacraft.desktop.app.AppContext;
import com.example.datacraft.desktop.app.AppPaths;
import com.example.datacraft.desktop.ui.shell.Toasts;
import com.example.datacraft.desktop.ui.theme.ThemeManager;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShellContentTest {
  @TempDir Path home;

  @BeforeAll
  static void toolkit() {
    FxTesting.start();
  }

  @Test
  void aLongFailureStaysCompactAndOffersCompleteDetails() {
    try (AppContext context = AppContext.open(new AppPaths(home))) {
      onFx(
          () -> {
            AtomicReference<String> title = new AtomicReference<>();
            AtomicReference<String> opened = new AtomicReference<>();
            Toasts toasts =
                new Toasts(
                    (name, text) -> {
                      title.set(name);
                      opened.set(text);
                    });
            String complete = "服务器诊断信息 😀\n".repeat(150) + "最后一行";
            toasts.failure(complete);
            layout(toasts, context);
            Region toast = (Region) toasts.getChildren().getFirst();
            assertTrue(toast.getHeight() <= 160, "a notification must not cover the page");
            assertTrue(toast.getWidth() <= 380.5);
            Button details =
                find(toast, Button.class).stream()
                    .filter(button -> "查看详情".equals(button.getText()))
                    .findFirst()
                    .orElseThrow();
            assertTrue(details.isVisible());
            assertTrue(details.isFocusTraversable());
            details.fire();
            assertEquals("通知详情", title.get());
            assertEquals(complete, opened.get());
          });
    }
    FxTesting.assertHealthy();
  }

  @Test
  void shortNotificationsKeepTheirLimitAndCloseActionWithoutExtraDetails() {
    onFx(
        () -> {
          boolean reduced = Motion.reduced();
          Motion.reduced(true);
          try {
            Toasts toasts = new Toasts((title, text) -> {});
            toasts.success("已保存");
            Region first = (Region) toasts.getChildren().getFirst();
            assertEquals(1, find(first, Button.class).size());
            toasts.failure("请求失败一");
            toasts.failure("请求失败二");
            toasts.failure("请求失败三");
            assertEquals(3, toasts.getChildren().size());
            assertFalse(toasts.getChildren().contains(first));
            Region latest = (Region) toasts.getChildren().getLast();
            Button close = find(latest, Button.class).getFirst();
            assertEquals("关闭通知", close.getAccessibleText());
            close.fire();
            assertEquals(2, toasts.getChildren().size());
            assertFalse(toasts.getChildren().contains(latest));
          } finally {
            Motion.reduced(reduced);
          }
        });
    FxTesting.assertHealthy();
  }

  private static void layout(Toasts toasts, AppContext context) {
    StackPane root = new StackPane(toasts);
    new Scene(root, 960, 640);
    ThemeManager theme = new ThemeManager(context.preferences());
    root.getStylesheets().setAll(theme.stylesheets());
    theme.style(root);
    root.resize(960, 640);
    root.applyCss();
    root.layout();
  }
}
