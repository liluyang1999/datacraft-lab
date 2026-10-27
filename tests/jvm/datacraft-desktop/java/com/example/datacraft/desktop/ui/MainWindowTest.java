package com.example.datacraft.desktop.ui;

import static com.example.datacraft.desktop.ui.FxTesting.find;
import static com.example.datacraft.desktop.ui.FxTesting.onFx;
import static com.example.datacraft.desktop.ui.FxTesting.settle;
import static com.example.datacraft.desktop.ui.FxTesting.shows;
import static com.example.datacraft.desktop.ui.FxTesting.showsPrefix;
import static com.example.datacraft.desktop.ui.FxTesting.snapshot;
import static com.example.datacraft.desktop.ui.FxTesting.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.datacraft.desktop.app.AppContext;
import com.example.datacraft.desktop.app.AppPaths;
import com.example.datacraft.desktop.app.Preferences;
import com.example.datacraft.desktop.service.FakeBackends;
import com.example.datacraft.desktop.store.Profile;
import com.example.datacraft.desktop.ui.shell.MainWindow;
import com.example.datacraft.desktop.ui.shell.Navigator.Destination;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
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

  @Test
  void everyViewShowsLiveDataInBothThemes() {
    Parent root = root();
    waitUntil("both backends are up", () -> bothUp(root));

    go(Destination.OVERVIEW);
    waitUntil(
        "the overview lists the DAGs",
        () -> shows(root, "datacraft_spark_etl") && shows(root, "datacraft_engine_jobs"));
    waitUntil("the overview shows recent runs", () -> showsPrefix(root, "上次"));
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
        "the run is in the recent runs",
        () -> shows(root, "Profiled 12,480 rows in 6 columns") && showsPrefix(root, "1.2 s"));
    settle();
    snapshot(scene(), "jobs-light");

    go(Destination.PIPELINES);
    waitUntil("the runs of the first DAG are listed", () -> showsPrefix(root, "scheduled__"));
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

  private static boolean bothUp(Parent root) {
    return find(root, com.example.datacraft.desktop.ui.component.StatusBadge.class).stream()
            .filter(badge -> "success".equals(badge.tone()))
            .count()
        >= 2;
  }

  @Test
  void theTriggerPanelShowsTheKnownParametersAndClosesWithEscape() {
    Parent root = root();
    go(Destination.PIPELINES);
    waitUntil("the DAGs are listed", () -> shows(root, "datacraft_spark_etl"));
    onFx(() -> window.go(Destination.OVERVIEW));
    waitUntil("the overview lists the DAGs", () -> shows(root, "datacraft_spark_etl"));
    // Open the spark ETL DAG by clicking its overview row.
    onFx(
        () ->
            find(root, javafx.scene.layout.HBox.class).stream()
                .filter(
                    row ->
                        row.getUserData()
                                instanceof com.example.datacraft.desktop.viewmodel.DagRow dag
                            && dag.dagId().equals("datacraft_spark_etl"))
                .findFirst()
                .orElseThrow()
                .fireEvent(click()));
    waitUntil("the spark ETL runs are listed", () -> showsPrefix(root, "manual__"));
    waitUntil("its tasks are listed", () -> shows(root, "row_count"));
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

    // The task table refreshes while the run is active and reuses its cells, so pick the button
    // in the same pulse as the check that its row is row_count.
    onFx(
        () ->
            find(root, javafx.scene.control.TableCell.class).stream()
                .filter(
                    cell ->
                        cell.getItem()
                                instanceof
                                com.example.datacraft.desktop.api.AirflowModels.TaskInstance task
                            && task.taskId().equals("row_count")
                            && cell.getGraphic() instanceof Button)
                .map(cell -> (Button) cell.getGraphic())
                .findFirst()
                .orElseThrow()
                .fire());
    waitUntil(
        "the log is read",
        () ->
            showsPrefix(root, "任务 row_count 的日志")
                && find(root, javafx.scene.control.ListView.class).stream()
                    .anyMatch(list -> list.getItems().size() == 3));
    settle();
    snapshot(scene(), "log-light");
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
    Motion.reduced(false);
    go(Destination.PIPELINES);
    waitUntil(
        "the newest engine-jobs run lists its tasks",
        () -> shows(root, FakeBackends.LONG_LOG_TASK));
    onFx(
        () ->
            find(root, javafx.scene.control.TableCell.class).stream()
                .filter(
                    cell ->
                        cell.getItem()
                                instanceof
                                com.example.datacraft.desktop.api.AirflowModels.TaskInstance task
                            && task.taskId().equals(FakeBackends.LONG_LOG_TASK)
                            && cell.getGraphic() instanceof Button)
                .map(cell -> (Button) cell.getGraphic())
                .findFirst()
                .orElseThrow()
                .fire());
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
    Motion.reduced(false); // the animations are part of what must stay smooth
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
