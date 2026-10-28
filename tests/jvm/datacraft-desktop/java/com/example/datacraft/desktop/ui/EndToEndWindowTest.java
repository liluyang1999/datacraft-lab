package com.example.datacraft.desktop.ui;

import static com.example.datacraft.desktop.ui.FxTesting.find;
import static com.example.datacraft.desktop.ui.FxTesting.onFx;
import static com.example.datacraft.desktop.ui.FxTesting.settle;
import static com.example.datacraft.desktop.ui.FxTesting.shows;
import static com.example.datacraft.desktop.ui.FxTesting.showsPrefix;
import static com.example.datacraft.desktop.ui.FxTesting.snapshot;
import static com.example.datacraft.desktop.ui.FxTesting.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.datacraft.desktop.api.AirflowModels.DagRun;
import com.example.datacraft.desktop.api.EngineModels.JobInfo;
import com.example.datacraft.desktop.api.RunTone;
import com.example.datacraft.desktop.app.AppContext;
import com.example.datacraft.desktop.app.AppPaths;
import com.example.datacraft.desktop.store.Profile;
import com.example.datacraft.desktop.ui.component.StatusRing;
import com.example.datacraft.desktop.ui.shell.MainWindow;
import com.example.datacraft.desktop.ui.shell.Navigator.Destination;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * The window against real backends (see EndToEndTest for the variables): the top bar turns green
 * for what is configured, every view opens, and the jobs view runs echo on the real engine. The
 * views are saved under target/ui-snapshots with an e2e- prefix.
 */
@Tag("e2e")
@EnabledIfEnvironmentVariable(named = "DATACRAFT_E2E_ENGINE_URL", matches = ".+")
class EndToEndWindowTest {

  @TempDir Path home;
  private AppContext context;
  private Stage stage;
  private MainWindow window;

  private static String env(String name) {
    String value = System.getenv(name);
    return value == null ? "" : value.strip();
  }

  @BeforeAll
  static void toolkit() {
    FxTesting.start();
  }

  @BeforeEach
  void open() {
    context = AppContext.open(new AppPaths(home));
    context.preferences().reducedMotion(true);
    String airflow = env("DATACRAFT_E2E_AIRFLOW_URL");
    context
        .profiles()
        .save(
            new Profile(
                Profile.NEW,
                "端到端",
                URI.create(env("DATACRAFT_E2E_ENGINE_URL")),
                URI.create(airflow.isEmpty() ? "http://127.0.0.1:9" : airflow),
                env("DATACRAFT_E2E_AIRFLOW_USER"),
                false),
            new Profile.Secrets(
                Optional.of(env("DATACRAFT_E2E_ENGINE_TOKEN")),
                Optional.of(env("DATACRAFT_E2E_AIRFLOW_PASSWORD"))))
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
    FxTesting.assertHealthy();
  }

  @SuppressWarnings("unchecked")
  private static javafx.scene.control.ListView<JobInfo> jobList(Parent root) {
    return (javafx.scene.control.ListView<JobInfo>)
        find(root, javafx.scene.control.ListView.class).stream()
            .filter(list -> list.getStyleClass().contains("job-list"))
            .findFirst()
            .orElseThrow();
  }

  private static JobInfo job(javafx.scene.control.ListView<JobInfo> list, String name) {
    return list.getItems().stream()
        .filter(job -> job.name().equals(name))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void theWindowWorksAgainstTheRealBackends() throws InterruptedException {
    Parent root = onFx(() -> stage.getScene().getRoot());
    int expected = env("DATACRAFT_E2E_AIRFLOW_URL").isEmpty() ? 1 : 2;
    waitUntil(
        "the configured backends are up",
        () ->
            find(root, StatusRing.class).stream().filter(r -> "success".equals(r.tone())).count()
                >= expected);
    if (expected == 2) {
      completeEngineDagRun();
      onFx(() -> window.go(Destination.OVERVIEW));
      waitUntil("the overview lists the DAGs", () -> shows(root, "datacraft_engine_jobs"));
      settle();
      snapshot(onFx(() -> stage.getScene()), "e2e-overview");
      onFx(() -> window.go(Destination.PIPELINES));
      waitUntil("the pipelines view lists the DAGs", () -> shows(root, "datacraft_spark_etl"));
      // This test creates its own successful run, so it also works alone on a fresh deployment.
      // datacraft_engine_jobs is selected first; its newest run and tasks must be drawn.
      waitUntil(
          "the newest run is drawn as the DAG, noop before echo, with its tasks' states",
          () ->
              find(root, com.example.datacraft.desktop.ui.component.TaskGraph.class).stream()
                      .anyMatch(graph -> graph.taskIds().equals(List.of("noop", "echo")))
                  && find(root, javafx.scene.layout.HBox.class).stream()
                      .anyMatch(
                          card ->
                              card.getStyleClass().contains("task-card")
                                  && card.getStyleClass().contains("success")
                                  && card.getUserData()
                                      instanceof
                                      com.example.datacraft.desktop.api.AirflowModels.TaskInstance
                                          task
                                  && task.taskId().equals("echo")));
      settle();
      snapshot(onFx(() -> stage.getScene()), "e2e-pipelines");
    }

    onFx(() -> window.go(Destination.JOBS));
    waitUntil("the real job list is loaded", () -> shows(root, "echo"));
    onFx(() -> jobList(root).getSelectionModel().select(job(jobList(root), "echo")));
    waitUntil("echo is selected", () -> shows(root, "原样返回 message 参数"));
    onFx(
        () ->
            find(root, TextField.class).stream()
                .filter(field -> "message".equals(field.getAccessibleText()))
                .findFirst()
                .orElseThrow()
                .setText("来自数艺工坊的端到端运行"));
    Button run =
        onFx(
            () ->
                find(root, Button.class).stream()
                    .filter(b -> "运行作业".equals(b.getText()))
                    .findFirst()
                    .orElseThrow());
    waitUntil("the run button is enabled", () -> !run.isDisabled());
    onFx(run::fire);
    waitUntil("the real result is shown", () -> shows(root, "来自数艺工坊的端到端运行"));
    waitUntil("the result is marked successful", () -> showsPrefix(root, "成功"));
    settle();
    snapshot(onFx(() -> stage.getScene()), "e2e-jobs");
    assertEquals(List.of(), FxTesting.javafxWarnings());
  }

  private void completeEngineDagRun() throws InterruptedException {
    String dag = "datacraft_engine_jobs";
    var airflow = context.session().current().orElseThrow().airflow();
    boolean wasPaused =
        airflow.dags().join().stream()
            .filter(item -> dag.equals(item.dagId()))
            .findFirst()
            .orElseThrow()
            .paused();
    try {
      if (wasPaused) {
        airflow.setPaused(dag, false).join();
      }
      DagRun triggered = airflow.trigger(dag, Map.of(), "数艺工坊窗口端到端测试").join();
      DagRun run = triggered;
      long deadline = System.nanoTime() + Duration.ofMinutes(5).toNanos();
      while (run.tone().active() && System.nanoTime() < deadline) {
        Thread.sleep(3000);
        run =
            airflow.runs(dag, 25).join().stream()
                .filter(item -> triggered.runId().equals(item.runId()))
                .findFirst()
                .orElseThrow();
      }
      assertEquals(RunTone.SUCCESS, run.tone(), "run " + run.runId() + " ended " + run.state());
    } finally {
      if (wasPaused) {
        airflow.setPaused(dag, true).join();
      }
    }
  }
}
