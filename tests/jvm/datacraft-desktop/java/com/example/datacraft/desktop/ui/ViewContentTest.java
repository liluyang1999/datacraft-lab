package com.example.datacraft.desktop.ui;

import static com.example.datacraft.desktop.ui.FxTesting.onFx;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.datacraft.desktop.api.AirflowModels.Dag;
import com.example.datacraft.desktop.api.EngineModels.JobInfo;
import com.example.datacraft.desktop.api.RunTone;
import com.example.datacraft.desktop.app.AppContext;
import com.example.datacraft.desktop.app.AppPaths;
import com.example.datacraft.desktop.service.FakeBackends;
import com.example.datacraft.desktop.store.ActivityRepository.Level;
import com.example.datacraft.desktop.store.ActivityRepository.Source;
import com.example.datacraft.desktop.store.Profile;
import com.example.datacraft.desktop.ui.shell.Dialogs;
import com.example.datacraft.desktop.ui.theme.ThemeManager;
import com.example.datacraft.desktop.ui.view.ActivityView;
import com.example.datacraft.desktop.ui.view.JobsView;
import com.example.datacraft.desktop.ui.view.OverviewView;
import com.example.datacraft.desktop.util.Fx;
import com.example.datacraft.desktop.viewmodel.ActivityModel;
import com.example.datacraft.desktop.viewmodel.DagRow;
import com.example.datacraft.desktop.viewmodel.JobsModel;
import com.example.datacraft.desktop.viewmodel.OverviewModel;
import com.example.datacraft.desktop.viewmodel.StatusModel;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.Region;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ViewContentTest {
  @TempDir Path home;
  private AppContext context;
  private FakeBackends backends;
  private ThemeManager theme;
  private Dialogs dialogs;

  @BeforeAll
  static void toolkit() {
    FxTesting.start();
  }

  @BeforeEach
  void open() {
    backends = new FakeBackends().withPipelines();
    context = AppContext.open(new AppPaths(home));
    context
        .profiles()
        .save(
            new Profile(
                Profile.NEW, "A", backends.engine.uri(), backends.airflow.uri(), "admin", false),
            Profile.Secrets.NONE)
        .join();
    onFx(
        () -> {
          theme = new ThemeManager(context.preferences());
          dialogs = new Dialogs(theme);
        });
  }

  @AfterEach
  void close() {
    context.close();
    backends.close();
    FxTesting.assertHealthy();
  }

  private Parent layout(Parent root) {
    Scene scene = new Scene(root, 760, 520);
    scene.getStylesheets().setAll(theme.stylesheets());
    theme.style(root);
    root.resize(760, 520);
    root.applyCss();
    root.layout();
    return root;
  }

  @Test
  void aShortMetricWithAnUnknownLongKeyCannotWidenItsCard() {
    String key = "自定义指标名称".repeat(100);
    backends.engine.on(
        "POST /jobs/echo/runs",
        200,
        "{\"jobName\":\"echo\",\"status\":\"SUCCEEDED\",\"message\":\"ok\",\"metrics\":{\""
            + key
            + "\":\"42\",\"rows\":\"9223372036854775807\"}}");
    JobsModel model =
        onFx(
            () ->
                new JobsModel(
                    context.jobs(),
                    context.catalog(),
                    context.presets(),
                    context.session(),
                    context.preferences(),
                    context.ticker(),
                    Fx.UI));
    Parent root =
        onFx(
            () -> {
              JobsView view = new JobsView(model, context.clock(), dialogs, () -> {});
              Parent page = layout((Parent) view.node());
              model.jobs().setAll(new JobInfo("echo", "Echo"));
              view.select("echo");
              model.run();
              return page;
            });
    FxTesting.waitUntil("the metric is shown", () -> model.result().get() != null);
    onFx(
        () -> {
          root.applyCss();
          root.layout();
          Label caption =
              FxTesting.find(root, Label.class).stream()
                  .filter(label -> key.equals(label.getText()))
                  .findFirst()
                  .orElseThrow();
          assertTrue(caption.getWidth() <= 180, "one metric caption must remain a compact card");
          Button details =
              FxTesting.find(root, Button.class).stream()
                  .filter(button -> "指标详情".equals(button.getText()))
                  .findFirst()
                  .orElseThrow();
          String complete = openedDetail(details);
          assertTrue(complete.contains(key + " = 42"));
          assertTrue(
              complete.contains("rows = 9223372036854775807"),
              "details preserve the original integer");
        });
  }

  @Test
  void activityDetailsAreOpenedOnDemandWithoutConsumingTheList() {
    String detail = "诊断信息和原始参数\n".repeat(150);
    context.activity().record(Level.ERROR, Source.ENGINE, "作业失败", detail);
    ActivityModel model = onFx(() -> new ActivityModel(context.activity(), Fx.UI));
    Parent root =
        onFx(
            () -> {
              ActivityView view = new ActivityView(model, context.clock(), dialogs);
              Parent page = layout((Parent) view.node());
              model.shown();
              return page;
            });
    FxTesting.waitUntil(
        "the requested activity loaded",
        () ->
            model.visibleEntries().stream()
                .anyMatch(entry -> "作业失败".equals(entry.text()) && detail.equals(entry.detail())));
    onFx(
        () -> {
          ListView<?> list = FxTesting.find(root, ListView.class).getFirst();
          int selected = -1;
          for (int index = 0; index < model.visibleEntries().size(); index++) {
            var entry = model.visibleEntries().get(index);
            if ("作业失败".equals(entry.text()) && detail.equals(entry.detail())) {
              selected = index;
              break;
            }
          }
          assertTrue(selected >= 0);
          list.getSelectionModel().select(selected);
          root.applyCss();
          root.layout();
          assertTrue(list.getHeight() >= 250, "long details must not consume the activity list");
          Button details =
              FxTesting.find(root, Button.class).stream()
                  .filter(button -> "查看详情".equals(button.getText()))
                  .findFirst()
                  .orElseThrow();
          assertTrue(details.isVisible());
          assertTrue(details.isFocusTraversable());
          String complete = openedDetail(details);
          assertTrue(complete.contains("作业失败"));
          assertTrue(complete.endsWith(detail), "the detail reader retains every line");
        });
  }

  @Test
  void overviewKeepsAllFourMultiDigitCountsInsideItsCard() {
    List<String> states = List.of("success", "failed", "running", "queued");
    String dags =
        java.util.stream.IntStream.range(0, 20)
            .mapToObj(index -> "{\"dag_id\":\"dag" + index + "\"}")
            .collect(java.util.stream.Collectors.joining(","));
    backends.airflow.on("GET /api/v2/dags", 200, "{\"dags\":[" + dags + "]}");
    for (int index = 0; index < 20; index++) {
      String id = "dag" + index;
      String state = states.get(index % states.size());
      String runs =
          java.util.stream.IntStream.range(0, 10)
              .mapToObj(
                  run ->
                      "{\"dag_run_id\":\"run"
                          + run
                          + "\",\"dag_id\":\""
                          + id
                          + "\",\"state\":\""
                          + state
                          + "\",\"run_after\":\"2026-11-09T00:00:00Z\"}")
              .collect(java.util.stream.Collectors.joining(","));
      backends.airflow.on(
          "GET /api/v2/dags/" + id + "/dagRuns", 200, "{\"dag_runs\":[" + runs + "]}");
    }
    Profile profile = context.session().current().orElseThrow().profile();
    context
        .session()
        .activate(
            profile,
            new Profile.Secrets(
                Optional.of(FakeBackends.TOKEN), Optional.of(FakeBackends.PASSWORD)));
    OverviewModel model =
        onFx(
            () ->
                new OverviewModel(
                    context.pipelines(),
                    context.jobs(),
                    context.session(),
                    context.preferences(),
                    context.ticker(),
                    context.clock(),
                    Fx.UI));
    Parent root =
        onFx(
            () -> {
              OverviewView view =
                  new OverviewView(
                      model,
                      new StatusModel(context.monitor(), Fx.UI),
                      context.clock(),
                      ignored -> {},
                      ignored -> {},
                      ignored -> {},
                      () -> {},
                      dialogs);
              Parent page = layout((Parent) view.node());
              model.shown();
              return page;
            });
    try {
      FxTesting.waitUntil("all four outcome counts loaded", () -> model.mix().get().total() == 200);
      onFx(
          () -> {
            root.applyCss();
            root.layout();
            assertEquals(new OverviewModel.Mix(50, 50, 50, 50), model.mix().get());
            Label title =
                FxTesting.find(root, Label.class).stream()
                    .filter(label -> "近期成功率".equals(label.getText()))
                    .findFirst()
                    .orElseThrow();
            Region card = (Region) title.getParent().getParent();
            assertTrue(card.getWidth() <= 260.5, "large counts cannot widen the shared card size");
            var bounds = card.localToScene(card.getBoundsInLocal());
            List<Label> counts =
                FxTesting.find(card, Label.class).stream()
                    .filter(label -> "50".equals(label.getText()))
                    .toList();
            assertEquals(4, counts.size());
            for (Label count : counts) {
              var countBounds = count.localToScene(count.getBoundsInLocal());
              assertTrue(
                  countBounds.getMaxX() <= bounds.getMaxX() - 15,
                  "every outcome count stays within the card padding");
              assertTrue(countBounds.getMaxY() <= bounds.getMaxY() - 15);
            }
          });
    } finally {
      onFx(model::hidden);
    }
  }

  @Test
  void overviewLongNamesStayBoundedAndRetainTheirFullTooltip() {
    String name = "pipeline_".repeat(70);
    onFx(
        () -> {
          OverviewModel model =
              new OverviewModel(
                  context.pipelines(),
                  context.jobs(),
                  context.session(),
                  context.preferences(),
                  context.ticker(),
                  context.clock(),
                  Fx.UI);
          OverviewView view =
              new OverviewView(
                  model,
                  new StatusModel(context.monitor(), Fx.UI),
                  context.clock(),
                  ignored -> {},
                  ignored -> {},
                  ignored -> {},
                  () -> {},
                  dialogs);
          Parent root = layout((Parent) view.node());
          model
              .dags()
              .setAll(
                  new DagRow(
                      new Dag(name, name, false, "", "", List.of(), false, Optional.empty()),
                      List.of()));
          model
              .recent()
              .setAll(
                  new OverviewModel.RecentItem(
                      "key",
                      name,
                      true,
                      RunTone.SUCCESS,
                      "成功",
                      context.clock().instant(),
                      Optional.of(Duration.ofSeconds(1))));
          root.applyCss();
          root.layout();
          List<Label> names =
              FxTesting.find(root, Label.class).stream()
                  .filter(label -> name.equals(label.getText()))
                  .toList();
          assertEquals(2, names.size());
          for (Label label : names) {
            assertTrue(label.getWidth() <= 300, "long names cannot push status and actions away");
            assertEquals(name, label.getTooltip().getText());
          }
        });
  }

  private static String openedDetail(Button button) {
    List<Window> existing = List.copyOf(Window.getWindows());
    button.fire();
    List<Window> opened =
        Window.getWindows().stream().filter(window -> !existing.contains(window)).toList();
    try {
      assertEquals(1, opened.size());
      List<TextArea> fields =
          FxTesting.find(opened.getFirst().getScene().getRoot(), TextArea.class);
      assertEquals(1, fields.size());
      assertTrue(!fields.getFirst().isEditable());
      return fields.getFirst().getText();
    } finally {
      opened.forEach(Window::hide);
    }
  }
}
