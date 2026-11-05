package com.example.datacraft.desktop.ui;

import static com.example.datacraft.desktop.ui.FxTesting.onFx;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.datacraft.desktop.api.EngineModels.JobInfo;
import com.example.datacraft.desktop.app.AppContext;
import com.example.datacraft.desktop.app.AppPaths;
import com.example.datacraft.desktop.ui.shell.Dialogs;
import com.example.datacraft.desktop.ui.theme.ThemeManager;
import com.example.datacraft.desktop.ui.view.JobsView;
import com.example.datacraft.desktop.util.Fx;
import com.example.datacraft.desktop.viewmodel.JobsModel;
import java.nio.file.Path;
import java.util.List;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JobsViewTest {
  @TempDir Path home;
  private AppContext context;
  private JobsModel model;
  private JobsView view;
  private Parent root;
  private TextField search;
  private ComboBox<?> kind;
  private ListView<?> list;
  private Button clear;

  @BeforeAll
  static void toolkit() {
    FxTesting.start();
  }

  @BeforeEach
  void open() {
    context = AppContext.open(new AppPaths(home));
    onFx(
        () -> {
          model =
              new JobsModel(
                  context.jobs(),
                  context.catalog(),
                  context.presets(),
                  context.session(),
                  context.preferences(),
                  context.ticker(),
                  Fx.UI);
          view =
              new JobsView(
                  model,
                  context.clock(),
                  new Dialogs(new ThemeManager(context.preferences())),
                  () -> {});
          root = (Parent) view.node();
          new Scene(root);
          root.applyCss();
          model
              .jobs()
              .setAll(
                  new JobInfo("csv-profile", "CSV profile"),
                  new JobInfo("csv-to-parquet", "Convert CSV"),
                  new JobInfo("echo", "Echo message"),
                  new JobInfo("custom-task", "自定义检查"));
          search = (TextField) root.lookup("#job-search");
          kind = (ComboBox<?>) root.lookup("#job-kind");
          clear = (Button) root.lookup("#job-filter-clear");
          list = (ListView<?>) root.lookup("#job-list");
          view.select("echo");
        });
  }

  @AfterEach
  void close() {
    context.close();
    FxTesting.assertHealthy();
  }

  private List<String> names() {
    return list.getItems().stream().map(item -> ((JobInfo) item).name()).toList();
  }

  private void filterKind(String label) {
    for (int index = 0; index < kind.getItems().size(); index++) {
      if (label.equals(kind.getItems().get(index).toString())) {
        kind.getSelectionModel().select(index);
        return;
      }
    }
    throw new AssertionError("No kind: " + label);
  }

  @Test
  void nameAndChineseSummarySearchCombineWithKindAndClear() {
    onFx(
        () -> {
          search.setText("  CSV  ");
          assertEquals(List.of("csv-profile", "csv-to-parquet"), names());
          filterKind("Spark");
          assertEquals(List.of("csv-to-parquet"), names());
          clear.fire();
          assertEquals(4, names().size());
          assertTrue(clear.isDisabled());
          search.setText("字节");
          assertEquals(List.of("csv-profile"), names());
          search.setText("自定义");
          filterKind("其他");
          assertEquals(List.of("custom-task"), names());
        });
  }

  @Test
  void hidingTheSelectionPreservesItsDraftAndRestoresTheHighlight() {
    onFx(
        () -> {
          var draft = model.params().getFirst();
          draft.value().set("中文草稿");
          search.setText("csv");
          assertEquals("echo", model.selected().get().name());
          assertNull(list.getSelectionModel().getSelectedItem());
          assertSame(draft, model.params().getFirst());
          assertEquals("中文草稿", draft.value().get());
          assertTrue(
              FxTesting.find(root, Label.class).stream()
                  .anyMatch(label -> label.isVisible() && "当前作业不在筛选结果中".equals(label.getText())));
          search.setText("does-not-exist");
          assertTrue(list.getItems().isEmpty());
          assertEquals("没有匹配的作业", ((Label) list.getPlaceholder()).getText());
          clear.fire();
          assertEquals(model.selected().get(), list.getSelectionModel().getSelectedItem());
          assertSame(draft, model.params().getFirst());
        });
  }

  @Test
  void aDirectoryRefreshDoesNotChooseAnotherJobOrDiscardTheDraft() {
    onFx(
        () -> {
          var draft = model.params().getFirst();
          draft.value().set("keep");
          search.setText("csv");
          model
              .jobs()
              .setAll(
                  new JobInfo("csv-to-parquet", "New description"),
                  new JobInfo("echo", "Echo message"));
          assertEquals(List.of("csv-to-parquet"), names());
          assertEquals("echo", model.selected().get().name());
          assertSame(draft, model.params().getFirst());
          assertEquals("keep", draft.value().get());
          search.setText("echo");
          model.jobs().set(1, new JobInfo("echo", "Updated description"));
          assertEquals(
              "Updated description",
              ((JobInfo) list.getSelectionModel().getSelectedItem()).description());
          assertSame(draft, model.params().getFirst());
          search.setText("csv");
          list.getSelectionModel().selectFirst();
          assertEquals("csv-to-parquet", model.selected().get().name());
        });
  }

  @Test
  void navigationToAJobRevealsItAndUnknownNamesLeaveTheFilterAlone() {
    onFx(
        () -> {
          search.setText("no match");
          filterKind("Spark");
          view.select("unknown-name");
          assertEquals("no match", search.getText());
          view.select("csv-profile");
          assertEquals("", search.getText());
          assertEquals("全部", kind.getValue().toString());
          assertEquals("csv-profile", model.selected().get().name());
          assertEquals(model.selected().get(), list.getSelectionModel().getSelectedItem());
          assertFalse(list.getItems().isEmpty());
        });
  }
}
