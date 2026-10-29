package com.example.datacraft.desktop.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.example.datacraft.desktop.service.JobCatalog;
import com.example.datacraft.desktop.ui.component.ParamEditor;
import com.example.datacraft.desktop.viewmodel.JobsModel.ParamRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.collections.FXCollections;
import javafx.scene.AccessibleAction;
import javafx.scene.AccessibleAttribute;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ParamEditorTest {

  private final JobCatalog catalog = JobCatalog.bundled(new ObjectMapper());

  @BeforeAll
  static void toolkit() {
    FxTesting.start();
  }

  @Test
  void textParametersExposeTheirEditedValueAndASeparateLabel() {
    FxTesting.onFx(
        () -> {
          ParamRow row =
              ParamRow.known(catalog.job("echo").orElseThrow().param("message").orElseThrow());
          ParamEditor editor = editor(row);
          TextField field = FxTesting.find(editor, TextField.class).getFirst();
          field.setText("原生中文 input");
          assertEquals("原生中文 input", field.queryAccessibleAttribute(AccessibleAttribute.TEXT));
          assertEquals(
              "message", label(field.queryAccessibleAttribute(AccessibleAttribute.LABELED_BY)));
          field.executeAccessibleAction(AccessibleAction.SET_TEXT, "另一个值");
          assertEquals("另一个值", row.value().get());
          assertEquals("另一个值", field.queryAccessibleAttribute(AccessibleAttribute.TEXT));
        });
    FxTesting.assertHealthy();
  }

  @Test
  void choiceParametersExposeTheirSelectionAndASeparateLabel() {
    FxTesting.onFx(
        () -> {
          ParamRow row =
              ParamRow.known(
                  catalog.job("csv-to-parquet").orElseThrow().param("mode").orElseThrow());
          ParamEditor editor = editor(row);
          ComboBox<?> field = FxTesting.find(editor, ComboBox.class).getFirst();
          row.value().set("append");
          assertEquals("append", field.queryAccessibleAttribute(AccessibleAttribute.TEXT));
          assertEquals(
              "mode", label(field.queryAccessibleAttribute(AccessibleAttribute.LABELED_BY)));
          row.value().set("");
          assertEquals("默认（overwrite）", field.queryAccessibleAttribute(AccessibleAttribute.TEXT));
        });
    FxTesting.assertHealthy();
  }

  private static ParamEditor editor(ParamRow row) {
    ParamEditor editor =
        new ParamEditor(FXCollections.observableArrayList(row), () -> {}, ignored -> {});
    new Scene(editor);
    editor.applyCss();
    return editor;
  }

  private static String label(Object value) {
    return assertInstanceOf(Label.class, value).getText();
  }
}
