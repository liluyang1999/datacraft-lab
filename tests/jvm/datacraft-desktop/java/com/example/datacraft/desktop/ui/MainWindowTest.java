package com.example.datacraft.desktop.ui;

import static com.example.datacraft.desktop.ui.FxTesting.find;
import static com.example.datacraft.desktop.ui.FxTesting.onFx;
import static com.example.datacraft.desktop.ui.FxTesting.settle;
import static com.example.datacraft.desktop.ui.FxTesting.shows;
import static com.example.datacraft.desktop.ui.FxTesting.snapshot;
import static com.example.datacraft.desktop.ui.FxTesting.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.datacraft.desktop.api.StubServer;
import com.example.datacraft.desktop.app.AppContext;
import com.example.datacraft.desktop.app.AppPaths;
import com.example.datacraft.desktop.app.Preferences;
import com.example.datacraft.desktop.service.FakeBackends;
import com.example.datacraft.desktop.store.Profile;
import com.example.datacraft.desktop.ui.component.RunBars;
import com.example.datacraft.desktop.ui.component.RunMark;
import com.example.datacraft.desktop.ui.component.StatusRing;
import com.example.datacraft.desktop.ui.component.TaskGraph;
import com.example.datacraft.desktop.ui.component.TimeStrip;
import com.example.datacraft.desktop.ui.shell.Drawer;
import com.example.datacraft.desktop.ui.shell.MainWindow;
import com.example.datacraft.desktop.ui.shell.Navigator.Destination;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The whole window against scripted backends: every view opens and shows live data, a job runs, the
 * trigger panel opens, both themes apply without CSS warnings, and the FX thread never stalls once
 * warm. Each view is saved as a PNG under target/ui-snapshots for review.
 */
class MainWindowTest {

  @TempDir Path home;
  private FakeBackends backends;
  private AppContext context;
  private Stage stage;
  private MainWindow window;

  @BeforeAll
  static void toolkit() {
    FxTesting.start();
  }

  @BeforeEach
  void open() {
    backends = new FakeBackends().withPipelines();
    context = AppContext.open(new AppPaths(home));
    // Snapshots are taken at rest: no fade or slide in progress.
    context.preferences().reducedMotion(true);
    context
        .profiles()
        .save(
            new Profile(
                Profile.NEW, "本机部署", backends.engine.uri(), backends.airflow.uri(), "admin", true),
            new Profile.Secrets(
                Optional.of(FakeBackends.TOKEN), Optional.of(FakeBackends.PASSWORD)))
        .join();
    onFx(
        () -> {
          stage = new Stage();
          window = new MainWindow(context, stage, folder -> {});
          window.show();
        });
    context.monitor().start();
  }

  @AfterEach
  void close() {
    onFx(() -> stage.close());
    context.close();
    backends.close();
    FxTesting.assertHealthy();
  }

  /**
   * Turns motion on through the preference. Every preference write (the last view, on each
   * navigation) applies the preference again, so setting Motion directly would last one step.
   */
  private void motionOn() {
    context.preferences().reducedMotion(false);
    waitUntil("motion is on", () -> !Motion.reduced());
  }

  private Parent root() {
    return onFx(() -> stage.getScene().getRoot());
  }

  private Scene scene() {
    return onFx(() -> stage.getScene());
  }

  private void go(Destination destination) {
    onFx(() -> window.go(destination));
  }

  private Drawer openFirstTrigger() {
    Parent root = root();
    go(Destination.PIPELINES);
    waitUntil("the first DAG is loaded", () -> pickableRuns(root) == 12);
    onFx(
        () ->
            find(root, Button.class).stream()
                .filter(button -> "触发运行".equals(button.getText()))
                .findFirst()
                .orElseThrow()
                .fire());
    settle(); // ScrollPane's skin attaches the form to the scene during a real pulse.
    return onFx(() -> find(root, Drawer.class).getFirst());
  }

  @Test
  void aLateTriggerResponseCannotCloseThePanelThatReplacedItsForm() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    backends.airflow.on(
        "POST /api/v2/dags/datacraft_engine_jobs/dagRuns",
        request -> {
          entered.countDown();
          try {
            if (!release.await(10, TimeUnit.SECONDS)) {
              return StubServer.Answer.json(504, "{}");
            }
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return StubServer.Answer.json(503, "{}");
          }
          return StubServer.Answer.json(
              200,
              """
              {"dag_run_id":"manual__held","dag_id":"datacraft_engine_jobs",
               "state":"queued","run_type":"manual","run_after":"2026-10-27T10:00:00Z",
               "conf":{},"queued_at":"2026-10-27T10:00:00Z","start_date":null,
               "end_date":null,"duration":null}
              """);
        });
    Drawer drawer = openFirstTrigger();
    Button submit =
        onFx(
            () ->
                find(drawer, Button.class).stream()
                    .filter(button -> "触发运行".equals(button.getText()))
                    .findFirst()
                    .orElseThrow());
    try {
      onFx(submit::fire);
      assertTrue(entered.await(10, TimeUnit.SECONDS), "the trigger request reached Airflow");
      onFx(() -> drawer.open("新的面板", new Label("新的内容"), () -> {}));
      release.countDown();
      waitUntil("the original response was handled", () -> !submit.isDisabled());
      assertTrue(onFx(() -> drawer.isOpen() && shows(drawer, "新的内容")));
    } finally {
      release.countDown();
    }
  }

  @Test
  void reopeningADrawerDuringItsExitKeepsTheNewContentAndReleasesTheOldOnce() {
    motionOn();
    Parent root = root();
    Drawer drawer = onFx(() -> find(root, Drawer.class).getFirst());
    AtomicInteger oldClosed = new AtomicInteger();
    AtomicInteger newClosed = new AtomicInteger();
    Label replacement = onFx(() -> new Label("新的内容"));
    onFx(
        () -> {
          drawer.open("原来的面板", new Label("原来的内容"), oldClosed::incrementAndGet);
          drawer.close();
          drawer.open("新的面板", replacement, newClosed::incrementAndGet);
        });
    Parent panel = onFx(replacement::getParent);
    settle();
    waitUntil("the entrance animation finished", () -> Math.abs(panel.getTranslateX()) < 0.1);
    assertTrue(onFx(() -> drawer.isOpen() && shows(drawer, "新的内容")));
    assertEquals(1, oldClosed.get());
    assertEquals(0, newClosed.get());
  }

  @Test
  void changingConnectionClosesThePreviousTriggerForm() {
    Drawer drawer = openFirstTrigger();
    Parent root = root();
    context.session().clear();
    waitUntil("the connection is cleared", () -> shows(root, "先连接你的 Airflow"));
    assertFalse(onFx(drawer::isOpen));
  }

  @Test
  void everyViewShowsLiveDataInBothThemes() {
    // The same compiled styles must be present in tests and the packaged app, without a text
    // fallback.
    for (String name : List.of("base", "light", "dark")) {
      assertNotNull(
          MainWindow.class.getResource("/com/example/datacraft/desktop/theme/" + name + ".bss"));
    }
    Parent root = root();
    waitUntil("both backends are up", () -> bothUp(root));

    go(Destination.OVERVIEW);
    waitUntil(
        "the overview lists the DAGs",
        () -> shows(root, "datacraft_spark_etl") && shows(root, "datacraft_engine_jobs"));
    // 14 successes and 3 failures among the finished runs on screen.
    waitUntil("the donut shows the success share", () -> shows(root, "82%"));
    waitUntil(
        "the recent runs are dots on the time line, the newest four named below it",
        () ->
            find(root, TimeStrip.class).stream().anyMatch(strip -> strip.count() == 8)
                && find(root, javafx.scene.layout.HBox.class).stream()
                        .filter(row -> row.getStyleClass().contains("recent-row"))
                        .count()
                    == 4);
    waitUntil(
        "each lane draws its runs as bars",
        () ->
            find(root, RunBars.class).stream()
                .map(RunBars::count)
                .sorted()
                .toList()
                .equals(List.of(0, 6, 10)));
    settle();
    snapshot(scene(), "overview-light");

    go(Destination.JOBS);
    waitUntil("the job list is loaded", () -> shows(root, "csv-profile"));
    onFx(
        () -> {
          TextField input =
              find(root, TextField.class).stream()
                  .filter(field -> "input".equals(field.getAccessibleText()))
                  .findFirst()
                  .orElseThrow();
          input.setText("/opt/datacraft/data/input.csv");
        });
    Button run =
        onFx(
            () ->
                find(root, Button.class).stream()
                    .filter(b -> "运行作业".equals(b.getText()))
                    .findFirst()
                    .orElseThrow());
    waitUntil("the run button is enabled", () -> !run.isDisabled());
    onFx(run::fire);
    waitUntil("the result is shown", () -> shows(root, "Profiled 12,480 rows in 6 columns"));
    waitUntil(
        "the outcome is a success mark with the metrics as figures",
        () ->
            find(root, RunMark.class).stream().anyMatch(mark -> "success".equals(mark.tone()))
                && shows(root, "12,480")
                && shows(root, "1.8 MB"));
    waitUntil(
        "the run is a bar of the recent runs",
        () ->
            find(root, RunBars.class).stream()
                .anyMatch(
                    bars ->
                        bars.count() == 1
                            && bars.segments().getFirst().tone()
                                == com.example.datacraft.desktop.api.RunTone.SUCCESS));
    settle();
    snapshot(scene(), "jobs-light");

    go(Destination.PIPELINES);
    waitUntil("the runs of the first DAG are bars to pick from", () -> pickableRuns(root) == 12);
    waitUntil(
        "its newest run is drawn as the DAG",
        () ->
            find(root, TaskGraph.class).stream()
                .anyMatch(
                    graph -> graph.taskIds().equals(List.of("noop", "echo", "spark_version"))));
    settle();
    snapshot(scene(), "pipelines-light");

    go(Destination.ACTIVITY);
    waitUntil("the activity lists the run", () -> shows(root, "作业 csv-profile 运行成功"));
    settle();
    snapshot(scene(), "activity-light");

    go(Destination.SETTINGS);
    waitUntil(
        "the profile form is filled",
        () -> find(root, TextField.class).stream().anyMatch(f -> "本机部署".equals(f.getText())));
    settle();
    snapshot(scene(), "settings-light");

    Scene scene = scene();
    context.preferences().theme(Preferences.Theme.DARK);
    waitUntil("the dark theme is applied", () -> scene.getRoot().getStyleClass().contains("dark"));
    for (Destination destination :
        List.of(
            Destination.OVERVIEW,
            Destination.JOBS,
            Destination.PIPELINES,
            Destination.ACTIVITY,
            Destination.SETTINGS)) {
      go(destination);
      settle();
      snapshot(scene(), destination.id() + "-dark");
    }
    assertEquals(List.of(), FxTesting.javafxWarnings(), "JavaFX logged warnings (CSS or layout)");
  }

  private static javafx.scene.input.MouseEvent click() {
    return new javafx.scene.input.MouseEvent(
        javafx.scene.input.MouseEvent.MOUSE_CLICKED,
        0,
        0,
        0,
        0,
        javafx.scene.input.MouseButton.PRIMARY,
        1,
        false,
        false,
        false,
        false,
        true,
        false,
        false,
        true,
        false,
        true,
        null);
  }

  @Test
  void pipelineActionsRemainVisibleAtTheMinimumWindowWidth() {
    Parent root = root();
    onFx(
        () -> {
          stage.setWidth(stage.getMinWidth());
          stage.setHeight(stage.getMinHeight());
        });
    for (Destination destination : List.of(Destination.PIPELINES, Destination.OVERVIEW)) {
      go(destination);
      waitUntil("the DAGs are listed", () -> shows(root, "datacraft_spark_etl"));
      settle();
      snapshot(scene(), destination.id() + "-minimum-light");
      onFx(
          () -> {
            var actions =
                find(root, Button.class).stream()
                    .filter(button -> "触发运行".equals(button.getAccessibleText()))
                    .filter(FxTesting::visible)
                    .toList();
            assertEquals(3, actions.size());
            for (Button action : actions) {
              var bounds = action.localToScene(action.getBoundsInLocal());
              assertTrue(
                  bounds.getMaxX() <= stage.getScene().getWidth(),
                  "a pipeline action lies outside the window: " + bounds);
            }
            var page =
                find(root, javafx.scene.control.ScrollPane.class).stream()
                    .filter(scroll -> scroll.getContent().getStyleClass().contains("view-content"))
                    .filter(FxTesting::visible)
                    .findFirst()
                    .orElseThrow();
            assertTrue(
                page.getContent().getLayoutBounds().getWidth()
                    <= page.getViewportBounds().getWidth() + 1,
                destination + " should fit without horizontal scrolling");
          });
    }
  }

  private static boolean bothUp(Parent root) {
    return find(root, StatusRing.class).stream()
            .filter(ring -> "success".equals(ring.tone()))
            .count()
        >= 2;
  }

  /** The run bars of the selected DAG, the ones a click picks from, if they are on screen. */
  private static java.util.Optional<RunBars> selectableBars(Parent root) {
    return find(root, RunBars.class).stream()
        .filter(bars -> bars.getStyleClass().contains("selectable") && FxTesting.visible(bars))
        .findFirst();
  }

  private static int pickableRuns(Parent root) {
    return selectableBars(root).map(RunBars::count).orElse(-1);
  }

  /** The card of a task in the graph; its user data is the task instance it shows. */
  private static java.util.Optional<javafx.scene.Node> card(Parent root, String taskId) {
    return find(root, javafx.scene.layout.HBox.class).stream()
        .filter(
            node ->
                node.getStyleClass().contains("task-card")
                    && node.getUserData()
                        instanceof com.example.datacraft.desktop.api.AirflowModels.TaskInstance task
                    && task.taskId().equals(taskId))
        .<javafx.scene.Node>map(node -> node)
        .findFirst();
  }

  private static boolean cardIs(Parent root, String taskId, String tone) {
    return card(root, taskId).map(node -> node.getStyleClass().contains(tone)).orElse(false);
  }

  /** A primary click at a point of a node, given in the node's own coordinates. */
  private static javafx.scene.input.MouseEvent clickAt(javafx.scene.Node node, double x, double y) {
    javafx.geometry.Point2D scene = node.localToScene(x, y);
    return new javafx.scene.input.MouseEvent(
        javafx.scene.input.MouseEvent.MOUSE_CLICKED,
        scene.getX(),
        scene.getY(),
        scene.getX(),
        scene.getY(),
        javafx.scene.input.MouseButton.PRIMARY,
        1,
        false,
        false,
        false,
        false,
        true,
        false,
        false,
        true,
        false,
        true,
        null);
  }

  @Test
  void theOverviewsPlayButtonOpensTheTriggerPanelAndABarPicksTheRunTheGraphShows() {
    Parent root = root();
    go(Destination.OVERVIEW);
    waitUntil("the overview lists the DAGs", () -> shows(root, "datacraft_spark_etl"));
    onFx(
        () ->
            find(root, javafx.scene.layout.HBox.class).stream()
                .filter(
                    row ->
                        row.getStyleClass().contains("lane")
                            && row.getUserData()
                                instanceof com.example.datacraft.desktop.viewmodel.DagRow dag
                            && dag.dagId().equals("datacraft_spark_etl"))
                .findFirst()
                .flatMap(
                    lane ->
                        find(lane, Button.class).stream()
                            .filter(button -> "触发运行".equals(button.getAccessibleText()))
                            .findFirst())
                .orElseThrow()
                .fire());
    waitUntil(
        "the pipelines view opened the spark ETL trigger panel",
        () -> shows(root, "触发 datacraft_spark_etl") && shows(root, "encoding"));
    onFx(() -> window.go(Destination.PIPELINES)); // closes the panel
    waitUntil("the spark ETL runs are bars", () -> pickableRuns(root) == 6);
    waitUntil("the running run is drawn", () -> cardIs(root, "row_count", "running"));
    // The second bar from the right is the failed run before it.
    RunBars bars = onFx(() -> selectableBars(root).orElseThrow());
    onFx(() -> bars.fireEvent(clickAt(bars, 23 * (12 + 4) + 6, 40)));
    waitUntil(
        "the failed run is drawn instead",
        () -> cardIs(root, "row_count", "failed") && cardIs(root, "csv_to_parquet", "success"));
    assertEquals(
        List.of(23),
        onFx(
            () -> {
              List<Integer> marked = new java.util.ArrayList<>();
              var children = bars.getChildrenUnmodifiable();
              for (int slot = 0; slot < children.size(); slot++) {
                if (children.get(slot).getStyleClass().contains("selected")) {
                  marked.add(slot);
                }
              }
              return marked;
            }),
        "the picked bar, and only it, is marked");
    // The keyboard does the same: Left picks the older run, a successful one.
    onFx(() -> bars.fireEvent(key(javafx.scene.input.KeyCode.LEFT)));
    waitUntil(
        "Left picked the older, successful run",
        () -> cardIs(root, "row_count", "success") && cardIs(root, "csv_to_parquet", "success"));
    // Enter on a lane selects its pipeline: the SFTP DAG has no run yet, so its shape stands alone.
    onFx(
        () ->
            find(root, javafx.scene.layout.HBox.class).stream()
                .filter(
                    row ->
                        row.getStyleClass().contains("lane")
                            && FxTesting.visible(row)
                            && row.getUserData()
                                instanceof com.example.datacraft.desktop.viewmodel.DagRow dag
                            && dag.dagId().equals("datacraft_sftp_ingest"))
                .findFirst()
                .orElseThrow()
                .fireEvent(key(javafx.scene.input.KeyCode.ENTER)));
    waitUntil(
        "Enter selected the SFTP pipeline and drew its shape",
        () ->
            find(root, TaskGraph.class).stream()
                .anyMatch(
                    graph ->
                        graph
                            .taskIds()
                            .equals(List.of("download", "csv_to_parquet", "row_count"))));
    assertEquals(List.of(), FxTesting.javafxWarnings(), "JavaFX logged warnings (CSS or layout)");
  }

  private static javafx.scene.input.KeyEvent key(javafx.scene.input.KeyCode code) {
    return new javafx.scene.input.KeyEvent(
        javafx.scene.input.KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false);
  }

  @Test
  void theTriggerPanelShowsTheKnownParametersAndClosesWithEscape() {
    Parent root = root();
    go(Destination.PIPELINES);
    waitUntil("the DAGs are listed", () -> shows(root, "datacraft_spark_etl"));
    onFx(() -> window.go(Destination.OVERVIEW));
    waitUntil("the overview lists the DAGs", () -> shows(root, "datacraft_spark_etl"));
    // Open the spark ETL DAG by clicking its overview lane.
    onFx(
        () ->
            find(root, javafx.scene.layout.HBox.class).stream()
                .filter(
                    row ->
                        row.getStyleClass().contains("lane")
                            && row.getUserData()
                                instanceof com.example.datacraft.desktop.viewmodel.DagRow dag
                            && dag.dagId().equals("datacraft_spark_etl"))
                .findFirst()
                .orElseThrow()
                .fireEvent(click()));
    waitUntil("the spark ETL runs are bars", () -> pickableRuns(root) == 6);
    waitUntil(
        "its tasks are drawn with the newest run's states",
        () -> shows(root, "row_count") && card(root, "row_count").isPresent());
    Button trigger =
        onFx(
            () ->
                find(root, Button.class).stream()
                    .filter(b -> "触发运行".equals(b.getText()))
                    .findFirst()
                    .orElseThrow());
    onFx(trigger::fire);
    waitUntil(
        "the trigger panel shows the spark ETL parameters",
        () -> shows(root, "encoding") && !shows(root, "remote_path"));
    settle();
    snapshot(scene(), "trigger-light");
    onFx(
        () ->
            javafx.event.Event.fireEvent(
                stage.getScene().getRoot(),
                new javafx.scene.input.KeyEvent(
                    javafx.scene.input.KeyEvent.KEY_PRESSED,
                    "",
                    "",
                    javafx.scene.input.KeyCode.ESCAPE,
                    false,
                    false,
                    false,
                    false)));
    waitUntil("Esc closed the panel", () -> !shows(root, "encoding"));

    // A click on the task's card opens its log.
    onFx(() -> card(root, "row_count").orElseThrow().fireEvent(click()));
    waitUntil(
        "the log is read",
        () ->
            shows(root, "row_count 的日志")
                && find(root, javafx.scene.control.ListView.class).stream()
                    .anyMatch(list -> list.getItems().size() == 3));
    settle();
    snapshot(scene(), "log-light");
  }

  @Test
  void aRunningJobTurnsItsRingUntilTheMarkOfItsOutcomeArrives() throws Exception {
    motionOn(); // the ring only turns with motion on
    java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    backends.engine.on(
        "POST /jobs/echo/runs",
        request -> {
          try {
            release.await(10, java.util.concurrent.TimeUnit.SECONDS);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
          return com.example.datacraft.desktop.api.StubServer.Answer.json(
              200,
              "{\"jobName\":\"echo\",\"status\":\"FAILED\",\"message\":\"echo was told to fail\","
                  + "\"metrics\":{\"durationMillis\":\"5\"}}");
        });
    Parent root = root();
    go(Destination.JOBS);
    waitUntil("the job list is loaded", () -> shows(root, "echo"));
    onFx(() -> window.go(Destination.JOBS));
    @SuppressWarnings("unchecked")
    javafx.scene.control.ListView<com.example.datacraft.desktop.api.EngineModels.JobInfo> jobs =
        (javafx.scene.control.ListView<com.example.datacraft.desktop.api.EngineModels.JobInfo>)
            onFx(
                () ->
                    find(root, javafx.scene.control.ListView.class).stream()
                        .filter(list -> list.getStyleClass().contains("job-list"))
                        .findFirst()
                        .orElseThrow());
    onFx(
        () ->
            jobs.getSelectionModel()
                .select(
                    jobs.getItems().stream()
                        .filter(job -> job.name().equals("echo"))
                        .findFirst()
                        .orElseThrow()));
    Button run =
        onFx(
            () ->
                find(root, Button.class).stream()
                    .filter(b -> "运行作业".equals(b.getText()))
                    .findFirst()
                    .orElseThrow());
    waitUntil("the run button is enabled", () -> !run.isDisabled());
    onFx(run::fire);
    waitUntil("the button offers to stop waiting", () -> "停止等待".equals(run.getText()));
    waitUntil(
        "the button holds the ring",
        () -> run.getGraphic() instanceof com.example.datacraft.desktop.ui.component.BusyRing);
    waitUntil(
        "the ring turns",
        () -> ((com.example.datacraft.desktop.ui.component.BusyRing) run.getGraphic()).spinning());
    com.example.datacraft.desktop.ui.component.BusyRing busy =
        onFx(() -> (com.example.datacraft.desktop.ui.component.BusyRing) run.getGraphic());
    // Another view hides the ring: it stops turning, and turns again on the way back.
    go(Destination.ACTIVITY);
    assertTrue(!onFx(busy::spinning), "a hidden ring does not turn");
    go(Destination.JOBS);
    assertTrue(onFx(busy::spinning), "it turns again when shown");
    release.countDown();
    waitUntil(
        "the failure mark replaces the ring",
        () ->
            "运行作业".equals(run.getText())
                && !busy.spinning()
                && find(root, RunMark.class).stream()
                    .anyMatch(mark -> "failed".equals(mark.tone()) && FxTesting.visible(mark)));
  }

  @Test
  void maximizingDoesNotReplaceTheSavedNormalWindowSize() {
    onFx(
        () -> {
          stage.setWidth(1100);
          stage.setHeight(720);
        });
    settle();
    onFx(() -> stage.setMaximized(true));
    settle();
    onFx(window::saveBounds);
    var saved = context.preferences().window().orElseThrow();
    assertEquals(1100, saved.width());
    assertEquals(720, saved.height());
  }

  @Test
  void allViewsKeepActionsWithinASmallScaledWorkArea() {
    Parent root = root();
    Scene compactScene = scene();
    onFx(
        () -> {
          stage.setMinWidth(800);
          stage.setMinHeight(560);
          stage.setWidth(853);
          stage.setHeight(560);
        });
    assertTrue(
        onFx(
            () ->
                find(root, com.example.datacraft.desktop.ui.shell.Sidebar.class)
                    .getFirst()
                    .collapsed()),
        "compact windows reserve space for page actions");
    for (Destination destination : Destination.values()) {
      go(destination);
      settle();
      snapshot(scene(), destination.id() + "-compact-light");
      onFx(
          () -> {
            for (Label label : find(root, Label.class)) {
              if (FxTesting.visible(label) && List.of("引擎", "Airflow").contains(label.getText())) {
                assertTrue(
                    label.getWidth() + 1 >= label.prefWidth(-1),
                    "backend name is truncated: " + label.getText());
              }
            }
            for (Button button : find(root, Button.class)) {
              if (FxTesting.visible(button)) {
                var bounds = button.localToScene(button.getBoundsInLocal());
                assertTrue(
                    bounds.getMinX() >= -1 && bounds.getMaxX() <= compactScene.getWidth() + 1,
                    destination + " action outside the window: " + button.getText() + " " + bounds);
                if (List.of("保存", "测试连接", "使用此连接", "删除", "运行作业", "触发运行")
                    .contains(button.getText())) {
                  assertTrue(
                      button.getWidth() + 1 >= button.prefWidth(-1),
                      destination + " action text is truncated: " + button.getText());
                }
              }
            }
          });
    }
  }

  @Test
  void aPartlyVisibleSavedWindowIsBroughtEntirelyIntoTheWorkArea() {
    onFx(
        () -> {
          var area = javafx.stage.Screen.getPrimary().getVisualBounds();
          stage.close();
          context
              .preferences()
              .window(
                  new Preferences.Bounds(
                      area.getMaxX() - 20,
                      area.getMinY() - 1000,
                      area.getWidth() * 2,
                      area.getHeight() * 2,
                      false));
          stage = new Stage();
          window = new MainWindow(context, stage, folder -> {});
          window.show();
        });
    settle();
    onFx(
        () -> {
          var area = javafx.stage.Screen.getPrimary().getVisualBounds();
          assertTrue(stage.getX() >= area.getMinX(), "left edge is reachable");
          assertTrue(stage.getY() >= area.getMinY(), "title bar is reachable");
          assertTrue(stage.getX() + stage.getWidth() <= area.getMaxX() + 1, "right edge fits");
          assertTrue(stage.getY() + stage.getHeight() <= area.getMaxY() + 1, "bottom fits");
        });
  }

  @Test
  void theWindowComesBackWhereItWasLeftAfterARestart() {
    onFx(
        () -> {
          window.go(Destination.ACTIVITY);
          stage.setX(140);
          stage.setY(90);
          stage.setWidth(1100);
          stage.setHeight(720);
        });
    context.preferences().theme(Preferences.Theme.DARK);
    onFx(
        () -> {
          window.saveBounds();
          stage.close();
        });
    context.close(); // the store finishes the queued writes before it closes
    context = AppContext.open(new AppPaths(home));
    onFx(
        () -> {
          stage = new Stage();
          window = new MainWindow(context, stage, folder -> {});
          window.show();
        });
    assertEquals(Destination.ACTIVITY, onFx(() -> window.navigator().current().get()));
    assertEquals(
        List.of(140.0, 90.0, 1100.0, 720.0),
        onFx(() -> List.of(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight())));
    assertTrue(onFx(() -> stage.getScene().getRoot().getStyleClass().contains("dark")));
  }

  @Test
  void aLogOfFifteenHundredLinesScrollsWithoutStalls() {
    Parent root = root();
    motionOn();
    go(Destination.PIPELINES);
    waitUntil(
        "the newest engine-jobs run's tasks are on their cards",
        () -> card(root, FakeBackends.LONG_LOG_TASK).isPresent());
    onFx(() -> card(root, FakeBackends.LONG_LOG_TASK).orElseThrow().fireEvent(click()));
    @SuppressWarnings("unchecked")
    javafx.scene.control.ListView<String> lines =
        (javafx.scene.control.ListView<String>)
            onFx(
                () ->
                    find(root, javafx.scene.control.ListView.class).stream()
                        .filter(list -> list.getStyleClass().contains("log-view"))
                        .findFirst()
                        .orElseThrow());
    waitUntil(
        "the whole log is read", () -> lines.getItems().size() == FakeBackends.LONG_LOG_LINES);
    settle();
    FxWatchdog watchdog = new FxWatchdog(50);
    onFx(watchdog::start);
    try {
      for (int line = 0; line < FakeBackends.LONG_LOG_LINES; line += 40) {
        int target = line;
        onFx(() -> lines.scrollTo(target));
        settle();
      }
      onFx(() -> lines.scrollTo(0));
      settle();
    } finally {
      watchdog.stop();
    }
    assertTrue(watchdog.stalls().isEmpty(), "stalls: " + watchdog.stalls());
  }

  @Test
  void theFxThreadDoesNotStallOnceWarm() {
    Parent root = root();
    motionOn(); // the animations are part of what must stay smooth
    for (Destination destination : Destination.values()) {
      go(destination);
    }
    go(Destination.PIPELINES);
    waitUntil("the pipelines are loaded", () -> shows(root, "datacraft_spark_etl"));
    settle();
    // Switching views: no pause over 50 ms.
    FxWatchdog views = new FxWatchdog(50);
    onFx(views::start);
    try {
      for (int round = 0; round < 3; round++) {
        for (Destination destination : Destination.values()) {
          go(destination);
          settle();
        }
      }
    } finally {
      views.stop();
    }
    assertTrue(views.stalls().isEmpty(), "view switches stalled: " + views.stalls());
    // Switching the theme restyles every node on screen at once: its budget is 100 ms.
    FxWatchdog themes = new FxWatchdog(100);
    onFx(themes::start);
    try {
      for (Destination destination : Destination.values()) {
        go(destination);
        settle();
        context.preferences().theme(Preferences.Theme.DARK);
        settle();
        context.preferences().theme(Preferences.Theme.LIGHT);
        settle();
      }
    } finally {
      themes.stop();
    }
    assertTrue(themes.stalls().isEmpty(), "theme switches stalled: " + themes.stalls());
  }
}
