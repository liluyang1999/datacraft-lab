package com.example.datacraft.desktop.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.example.datacraft.desktop.app.AppContext;
import com.example.datacraft.desktop.app.AppPaths;
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
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
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

  private void withView(java.util.function.Consumer<Parent> check) {
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
            SettingsView view =
                new SettingsView(
                    model,
                    new Dialogs(new ThemeManager(context.preferences())),
                    new Toasts(),
                    ignored -> {});
            Parent root = (Parent) view.node();
            new Scene(root);
            root.applyCss();
            check.accept(root);
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
