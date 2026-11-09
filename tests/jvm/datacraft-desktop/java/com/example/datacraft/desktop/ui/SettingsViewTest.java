package com.example.datacraft.desktop.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.datacraft.desktop.app.AppContext;
import com.example.datacraft.desktop.app.AppPaths;
import com.example.datacraft.desktop.service.FakeBackends;
import com.example.datacraft.desktop.store.Profile;
import com.example.datacraft.desktop.ui.shell.Dialogs;
import com.example.datacraft.desktop.ui.shell.Toasts;
import com.example.datacraft.desktop.ui.theme.ThemeManager;
import com.example.datacraft.desktop.ui.view.SettingsView;
import com.example.datacraft.desktop.util.Fx;
import com.example.datacraft.desktop.viewmodel.SettingsModel;
import java.nio.file.Path;
import java.util.List;
import javafx.scene.AccessibleAttribute;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.stage.Window;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsViewTest {
  @TempDir Path home;

  @BeforeAll
  static void toolkit() {
    FxTesting.start();
  }

  @Test
  void connectionFieldsHaveLabelsWithoutReplacingTheirActualValues() {
    withView(
        root -> {
          for (String name : List.of("名称", "引擎地址", "Airflow 地址", "用户名")) {
            TextField field = assertInstanceOf(TextField.class, labeled(root, name));
            field.setText("中文实际值");
            assertEquals("中文实际值", field.queryAccessibleAttribute(AccessibleAttribute.TEXT));
          }
          assertInstanceOf(PasswordField.class, labeled(root, "引擎令牌"));
          assertInstanceOf(PasswordField.class, labeled(root, "口令"));
        });
  }

  @Test
  void timeoutAndDataLocationHaveSeparateLabels() {
    withView(
        root -> {
          assertInstanceOf(Spinner.class, labeled(root, "等待上限"));
          TextField location = assertInstanceOf(TextField.class, labeled(root, "位置"));
          assertEquals(
              home.toString(), location.queryAccessibleAttribute(AccessibleAttribute.TEXT));
        });
  }

  @Test
  void aLongConnectionNameFitsItsListAndRetainsItsFullTooltip() {
    withView(
        (root, model) -> {
          String name = "很长的连接名称".repeat(70);
          Profile defaults = Profile.localDefaults();
          model
              .profiles()
              .setAll(
                  new Profile(
                      12, name, defaults.engineUrl(), defaults.airflowUrl(), "admin", false));
          root.applyCss();
          root.layout();
          Label label =
              FxTesting.find(root, Label.class).stream()
                  .filter(item -> name.equals(item.getText()))
                  .findFirst()
                  .orElseThrow();
          assertTrue(label.getWidth() <= 140, "connection names must not widen the profile list");
          assertEquals(name, label.getTooltip().getText());
        });
  }

  @Test
  void connectionTestDiagnosticsCanBeOpenedWithoutChangingTheDraft() {
    try (AppContext context = AppContext.open(new AppPaths(home));
        FakeBackends backends = new FakeBackends()) {
      String detail = "服务诊断与恢复方法 ".repeat(90);
      backends.engine.on("GET /health", 500, "{\"error\":\"" + detail + "\"}");
      SettingsModel model =
          FxTesting.onFx(
              () ->
                  new SettingsModel(
                      context.profiles(),
                      context.session(),
                      context.preferences(),
                      context.activity(),
                      context.recentRuns(),
                      context.paths(),
                      context.vault().description(),
                      Fx.UI));
      Parent root =
          FxTesting.onFx(
              () -> {
                ThemeManager theme = new ThemeManager(context.preferences());
                Dialogs dialogs = new Dialogs(theme);
                SettingsView view =
                    new SettingsView(model, dialogs, new Toasts(dialogs::details), ignored -> {});
                Parent page = (Parent) view.node();
                Scene scene = new Scene(page, 760, 700);
                scene.getStylesheets().setAll(theme.stylesheets());
                theme.style(page);
                page.resize(760, 700);
                page.applyCss();
                page.layout();
                model.name().set("尚未保存的连接");
                model.engineUrl().set(backends.engine.uri().toString());
                model.engineToken().set(FakeBackends.TOKEN);
                model.airflowUrl().set(backends.airflow.uri().toString());
                model.airflowUser().set("admin");
                model.airflowPassword().set(FakeBackends.PASSWORD);
                model.test();
                return page;
              });
      FxTesting.waitUntil("connection diagnostics arrive", () -> model.testResult().get() != null);
      try {
        FxTesting.onFx(
            () -> {
              root.applyCss();
              root.layout();
              String complete = model.testResult().get().engine().summary();
              assertTrue(complete.contains("服务诊断"));
              Button action =
                  FxTesting.find(root, Button.class).stream()
                      .filter(button -> "引擎 API 详情".equals(button.getAccessibleText()))
                      .findFirst()
                      .orElseThrow();
              assertTrue(action.isFocusTraversable());
              action.fire();
              TextArea shown =
                  Window.getWindows().stream()
                      .flatMap(
                          window ->
                              FxTesting.find(window.getScene().getRoot(), TextArea.class).stream())
                      .filter(area -> complete.equals(area.getText()))
                      .findFirst()
                      .orElseThrow();
              assertFalse(shown.isEditable());
              assertEquals("尚未保存的连接", model.name().get());
              assertEquals(backends.engine.uri().toString(), model.engineUrl().get());
              assertTrue(context.profiles().list().join().isEmpty());
            });
      } finally {
        FxTesting.onFx(() -> List.copyOf(Window.getWindows()).forEach(Window::hide));
      }
    }
    FxTesting.assertHealthy();
  }

  private void withView(java.util.function.Consumer<Parent> check) {
    withView((root, model) -> check.accept(root));
  }

  private void withView(java.util.function.BiConsumer<Parent, SettingsModel> check) {
    try (AppContext context = AppContext.open(new AppPaths(home))) {
      FxTesting.onFx(
          () -> {
            SettingsModel model =
                new SettingsModel(
                    context.profiles(),
                    context.session(),
                    context.preferences(),
                    context.activity(),
                    context.recentRuns(),
                    context.paths(),
                    context.vault().description(),
                    Fx.UI);
            ThemeManager theme = new ThemeManager(context.preferences());
            Dialogs dialogs = new Dialogs(theme);
            SettingsView view =
                new SettingsView(model, dialogs, new Toasts(dialogs::details), ignored -> {});
            Parent root = (Parent) view.node();
            Scene scene = new Scene(root, 760, 700);
            scene.getStylesheets().setAll(theme.stylesheets());
            theme.style(root);
            root.resize(760, 700);
            root.applyCss();
            root.layout();
            check.accept(root, model);
          });
    }
    FxTesting.assertHealthy();
  }

  private static Node labeled(Parent root, String name) {
    Label label =
        FxTesting.find(root, Label.class).stream()
            .filter(item -> name.equals(item.getText()))
            .findFirst()
            .orElseThrow();
    Node field = label.getLabelFor();
    assertNotNull(field, name + " must label its input");
    assertEquals(label, field.queryAccessibleAttribute(AccessibleAttribute.LABELED_BY));
    return field;
  }
}
