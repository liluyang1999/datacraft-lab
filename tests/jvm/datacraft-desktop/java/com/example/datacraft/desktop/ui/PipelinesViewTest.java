package com.example.datacraft.desktop.ui;

import static com.example.datacraft.desktop.ui.FxTesting.onFx;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.datacraft.desktop.app.AppContext;
import com.example.datacraft.desktop.app.AppPaths;
import com.example.datacraft.desktop.ui.shell.Drawer;
import com.example.datacraft.desktop.ui.shell.Toasts;
import com.example.datacraft.desktop.ui.view.PipelinesView;
import com.example.datacraft.desktop.util.Fx;
import com.example.datacraft.desktop.viewmodel.PipelinesModel;
import java.nio.file.Path;
import java.util.List;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.StackPane;
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
          PipelinesView view =
              new PipelinesView(
                  model, context.clock(), drawer, new Toasts(), context.json(), () -> {});
          StackPane root = new StackPane(view.node(), drawer);
          new Scene(root);
          view.trigger("datacraft_spark_etl");
          root.applyCss();
          asJson = FxTesting.find(drawer, ToggleButton.class).getFirst();
        });
  }

  @AfterEach
  void close() {
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
    return FxTesting.find(drawer, Label.class).stream()
        .anyMatch(label -> label.isVisible() && label.getText().contains(text));
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
