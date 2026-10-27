package com.example.datacraft.desktop.ui;

import static com.example.datacraft.desktop.ui.FxTesting.find;
import static com.example.datacraft.desktop.ui.FxTesting.onFx;
import static com.example.datacraft.desktop.ui.FxTesting.settle;
import static com.example.datacraft.desktop.ui.FxTesting.shows;
import static com.example.datacraft.desktop.ui.FxTesting.showsPrefix;
import static com.example.datacraft.desktop.ui.FxTesting.snapshot;
import static com.example.datacraft.desktop.ui.FxTesting.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.datacraft.desktop.api.EngineModels.JobInfo;
import com.example.datacraft.desktop.app.AppContext;
import com.example.datacraft.desktop.app.AppPaths;
import com.example.datacraft.desktop.store.Profile;
import com.example.datacraft.desktop.ui.component.StatusBadge;
import com.example.datacraft.desktop.ui.shell.MainWindow;
import com.example.datacraft.desktop.ui.shell.Navigator.Destination;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
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
  void theWindowWorksAgainstTheRealBackends() {
    Parent root = onFx(() -> stage.getScene().getRoot());
    int expected = env("DATACRAFT_E2E_AIRFLOW_URL").isEmpty() ? 1 : 2;
    waitUntil(
        "the configured backends are up",
        () ->
            find(root, StatusBadge.class).stream().filter(b -> "success".equals(b.tone())).count()
                >= expected);
    if (expected == 2) {
      onFx(() -> window.go(Destination.OVERVIEW));
      waitUntil("the overview lists the DAGs", () -> shows(root, "datacraft_engine_jobs"));
      settle();
      snapshot(onFx(() -> stage.getScene()), "e2e-overview");
      onFx(() -> window.go(Destination.PIPELINES));
      waitUntil("the pipelines view lists the DAGs", () -> shows(root, "datacraft_spark_etl"));
      // datacraft_engine_jobs is selected first; once it has run (EndToEndTest triggers it), its
      // newest run is selected and its tasks are listed.
      waitUntil(
          "the tasks of the newest run are listed",
          () -> shows(root, "noop") && shows(root, "echo"));
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
}
