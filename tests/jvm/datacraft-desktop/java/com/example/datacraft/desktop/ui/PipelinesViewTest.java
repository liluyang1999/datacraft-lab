package com.example.datacraft.desktop.ui;

import static com.example.datacraft.desktop.ui.FxTesting.onFx;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.datacraft.desktop.api.AirflowModels.Dag;
import com.example.datacraft.desktop.app.AppContext;
import com.example.datacraft.desktop.app.AppPaths;
import com.example.datacraft.desktop.service.FakeBackends;
import com.example.datacraft.desktop.store.Profile;
import com.example.datacraft.desktop.ui.shell.Dialogs;
import com.example.datacraft.desktop.ui.shell.Drawer;
import com.example.datacraft.desktop.ui.shell.Toasts;
import com.example.datacraft.desktop.ui.theme.ThemeManager;
import com.example.datacraft.desktop.ui.view.PipelinesView;
import com.example.datacraft.desktop.util.Fx;
import com.example.datacraft.desktop.viewmodel.DagRow;
import com.example.datacraft.desktop.viewmodel.PipelinesModel;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.StackPane;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PipelinesViewTest {
  @TempDir Path home;
  private AppContext context;
  private Drawer drawer;
  private ToggleButton asJson;
  private PipelinesModel model;
  private StackPane root;

  @BeforeAll
  static void toolkit() {
    FxTesting.start();
  }

  @BeforeEach
  void open() {
    context = AppContext.open(new AppPaths(home));
    onFx(
        () -> {
          drawer = new Drawer();
          model =
              new PipelinesModel(
                  context.pipelines(),
                  context.catalog(),
                  context.json(),
                  context.session(),
                  context.preferences(),
                  context.ticker(),
                  Fx.UI);
          ThemeManager theme = new ThemeManager(context.preferences());
          Dialogs dialogs = new Dialogs(theme);
          PipelinesView view =
              new PipelinesView(
                  model,
                  context.clock(),
                  drawer,
                  new Toasts(dialogs::details),
                  context.json(),
                  () -> {},
                  dialogs);
          root = new StackPane(view.node(), drawer);
          Scene scene = new Scene(root, 760, 520);
          scene.getStylesheets().setAll(theme.stylesheets());
          theme.style(root);
          root.resize(760, 520);
          view.trigger("datacraft_spark_etl");
          root.applyCss();
          asJson = FxTesting.find(drawer, ToggleButton.class).getFirst();
        });
  }

  @AfterEach
  void close() {
    onFx(() -> List.copyOf(Window.getWindows()).forEach(Window::hide));
    onFx(drawer::close);
    context.close();
    FxTesting.assertHealthy();
  }

  private TextArea jsonText() {
    drawer.applyCss();
    return FxTesting.find(drawer, TextArea.class).getFirst();
  }

  private Button button(String text) {
    return FxTesting.find(drawer, Button.class).stream()
        .filter(button -> text.equals(button.getText()))
        .findFirst()
        .orElseThrow();
  }

  private boolean errorContains(String text) {
    Optional<Button> action =
        FxTesting.find(drawer, Button.class).stream()
            .filter(button -> button.isVisible() && "运行未提交".equals(button.getText()))
            .findFirst();
    if (action.isEmpty()) {
      return false;
    }
    action.get().fire();
    try {
      return Window.getWindows().stream()
          .flatMap(window -> FxTesting.find(window.getScene().getRoot(), TextArea.class).stream())
          .anyMatch(area -> !area.isEditable() && area.getText().contains(text));
    } finally {
      List.copyOf(Window.getWindows()).forEach(Window::hide);
    }
  }

  @Test
  void longPipelineNamesLeaveTheRunActionsInsideThePage() {
    onFx(
        () -> {
          String name = "很长的流水线名称".repeat(60);
          model
              .dags()
              .setAll(
                  new DagRow(
                      new Dag(name, name, false, "", null, List.of(), false, Optional.empty()),
                      List.of()));
          model.selectDag(name);
          root.applyCss();
          root.layout();
          List<Label> names =
              FxTesting.find(root, Label.class).stream()
                  .filter(label -> name.equals(label.getText()))
                  .toList();
          assertEquals(2, names.size());
          for (Label label : names) {
            assertTrue(label.getWidth() <= 520, "long names must yield to the remaining controls");
            assertEquals(name, label.getTooltip().getText());
          }
          Button trigger =
              FxTesting.find((javafx.scene.Parent) root.getChildren().getFirst(), Button.class)
                  .stream()
                  .filter(button -> "触发运行".equals(button.getText()))
                  .findFirst()
                  .orElseThrow();
          assertTrue(
              trigger.localToScene(trigger.getBoundsInLocal()).getMaxX() <= 760,
              "the trigger action must remain inside the page");
        });
  }

  @Test
  void aLongPipelineFailureHasACompactActionAndCompleteReadableDetails() {
    try (FakeBackends backends = new FakeBackends()) {
      String detail = "诊断原因与下一步 ".repeat(90);
      backends.airflow.on("GET /api/v2/dags", 500, "{\"detail\":\"" + detail + "\"}");
      context
          .profiles()
          .saveAndActivate(
              new Profile(
                  Profile.NEW, "测试", backends.engine.uri(), backends.airflow.uri(), "admin", false),
              new Profile.Secrets(
                  Optional.of(FakeBackends.TOKEN), Optional.of(FakeBackends.PASSWORD)))
          .join();
      onFx(model::shown);
      FxTesting.waitUntil("the pipeline error arrives", () -> !model.dagProblem().get().isEmpty());
      onFx(
          () -> {
            root.applyCss();
            root.layout();
            String complete = model.dagProblem().get();
            assertTrue(complete.contains("诊断原因"));
            assertFalse(
                FxTesting.find(root, Label.class).stream()
                    .anyMatch(label -> complete.equals(label.getText())),
                "diagnostics must not fill the page");
            Button action =
                FxTesting.find(root, Button.class).stream()
                    .filter(button -> "流水线读取失败".equals(button.getText()))
                    .findFirst()
                    .orElseThrow();
            assertTrue(action.isFocusTraversable());
            assertTrue(action.getHeight() < 70);
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
            assertEquals(complete, model.dagProblem().get());
          });
      onFx(model::hidden);
    }
  }

  @Test
  void jsonStringEditsAndUnknownFieldsSurviveARoundTripThroughTheForm() {
    onFx(
        () -> {
          TextField input =
              FxTesting.find(drawer, Label.class).stream()
                  .filter(label -> "input".equals(label.getText()))
                  .map(label -> (TextField) label.getLabelFor())
                  .findFirst()
                  .orElseThrow();
          input.setText("original.csv");
          asJson.fire();
          assertEquals(
              "original.csv",
              model.parseConf(jsonText().getText()).value().orElseThrow().get("input"));
          String configuration = "{\"input\":\"新输入.csv\",\"unknown\":\"保留的值\"}";
          jsonText().setText(configuration);
          asJson.fire();
          assertFalse(asJson.isSelected());
          asJson.fire();
          assertEquals(
              model.parseConf(configuration).value(),
              model.parseConf(jsonText().getText()).value());
        });
  }

  @Test
  void valuesTheFormCannotRepresentStayInJsonWithoutChangingTheDraft() {
    onFx(
        () -> {
          asJson.fire();
          for (String value : List.of("42", "true", "null", "[]", "{}", "\"\"", "\"a\\nb\"")) {
            String configuration = "{\"unknown\":" + value + "}";
            TextArea text = jsonText();
            text.setText(configuration);
            asJson.fire();
            assertTrue(asJson.isSelected(), configuration);
            assertEquals(configuration, text.getText());
            assertTrue(errorContains("JSON"));
          }
        });
  }

  @Test
  void invalidJsonCannotBeSubmittedOrReplaceTheFormAndCanBeCorrected() {
    onFx(
        () -> {
          asJson.fire();
          for (String configuration : List.of("{", "[]", "{\"x\":\"a\",\"x\":\"b\"}", "{} {}")) {
            jsonText().setText(configuration);
            asJson.fire();
            assertTrue(asJson.isSelected(), configuration);
            assertEquals(configuration, jsonText().getText());
            button("触发运行").fire();
            assertFalse(button("触发运行").isDisabled());
            assertTrue(errorContains("JSON"));
          }
          jsonText().setText("{\"unknown\":\"fixed\"}");
          asJson.fire();
          assertFalse(asJson.isSelected());
          assertFalse(errorContains("JSON"));
          asJson.fire();
          assertEquals(
              "fixed", model.parseConf(jsonText().getText()).value().orElseThrow().get("unknown"));
        });
  }

  @Test
  void duplicateAndUnnamedParametersCannotBeConvertedOrSubmitted() {
    onFx(
        () -> {
          button("添加参数").fire();
          drawer.applyCss();
          TextField key =
              FxTesting.find(drawer, TextField.class).stream()
                  .filter(field -> "参数名".equals(field.getPromptText()))
                  .findFirst()
                  .orElseThrow();
          TextField value =
              FxTesting.find(drawer, TextField.class).stream()
                  .filter(field -> "值".equals(field.getPromptText()))
                  .findFirst()
                  .orElseThrow();
          key.setText(" input ");
          value.setText("another.csv");
          asJson.fire();
          assertFalse(asJson.isSelected());
          assertTrue(errorContains("重复"));
          button("触发运行").fire();
          assertFalse(button("触发运行").isDisabled());
          assertTrue(errorContains("重复"));
          key.setText("");
          asJson.fire();
          assertFalse(asJson.isSelected());
          assertTrue(errorContains("参数名称"));
          assertEquals("another.csv", value.getText());
        });
  }
}
