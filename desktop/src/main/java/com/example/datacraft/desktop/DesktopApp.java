package com.example.datacraft.desktop;

import com.example.datacraft.desktop.app.AppContext;
import com.example.datacraft.desktop.app.AppPaths;
import com.example.datacraft.desktop.app.Logging;
import com.example.datacraft.desktop.ui.FxWatchdog;
import com.example.datacraft.desktop.ui.shell.MainWindow;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import javafx.animation.AnimationTimer;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.stage.Stage;

/**
 * 数艺工坊. The local store and the services open on a background thread while JavaFX starts ({@link
 * #prepare}), the window is built as soon as both are ready, and the health checks begin after it
 * is shown. Each phase is logged in milliseconds since the JVM started.
 */
public final class DesktopApp extends Application {

  private static final System.Logger LOG = System.getLogger(DesktopApp.class.getName());
  static final long STALL_THRESHOLD_MS = 50;

  /**
   * The watchdog starts this long after the first frame: the first mouse event, the first render
   * and the first health results load classes once, and that warm-up is part of the start-up.
   */
  static final javafx.util.Duration WARM_UP = javafx.util.Duration.seconds(3);

  /** With this property set, the app quits two seconds after its first frame (start-up checks). */
  static final String SMOKE_PROPERTY = "datacraft.desktop.smoke";

  private static volatile CompletableFuture<AppContext> preparing;

  private AppContext context;
  private Throwable openFailure;
  private MainWindow window;
  private FxWatchdog watchdog;

  /** Starts opening ~/.datacraft and the services; called by the launcher before JavaFX starts. */
  static void prepare(AppPaths paths) {
    preparing =
        CompletableFuture.supplyAsync(
            () -> {
              AppContext opened = AppContext.open(paths);
              mark("store and services ready");
              return opened;
            },
            task -> Thread.ofPlatform().name("datacraft-prepare").start(task));
  }

  @Override
  public void init() {
    mark("toolkit ready");
    if (preparing == null) {
      AppPaths paths = AppPaths.resolve().ensureCreated();
      Logging.configure(paths);
      prepare(paths);
    }
    try {
      context = preparing.join();
    } catch (CompletionException failure) {
      openFailure = failure.getCause() == null ? failure : failure.getCause();
      LOG.log(System.Logger.Level.ERROR, "The local data cannot be opened", openFailure);
    }
  }

  @Override
  public void start(Stage stage) {
    if (context == null) {
      showOpenFailure();
      return;
    }
    window = new MainWindow(context, stage, this::openFolder);
    mark("window built");
    window.show();
    mark("window shown");
    context
        .profiles()
        .restore()
        .exceptionally(
            failure -> {
              LOG.log(
                  System.Logger.Level.WARNING,
                  "The last connection could not be restored",
                  failure);
              return java.util.Optional.empty();
            });
    context.monitor().start();
    afterFirstFrame();
  }

  /** The store could not be opened (a newer schema, a locked or damaged file): say so and quit. */
  private void showOpenFailure() {
    Alert alert = new Alert(Alert.AlertType.ERROR);
    alert.setTitle("数艺工坊");
    alert.setHeaderText("无法打开本机数据");
    alert.setContentText(
        openFailure.getMessage()
            + "\n\n数据位于 "
            + AppPaths.resolve().home()
            + "。关闭其他正在运行的数艺工坊后再试；若仍失败，可把这个目录移走后重新启动。");
    alert.showAndWait();
    Platform.exit();
  }

  /** Things that need not delay the first frame: the window icons and the stall watchdog. */
  private void afterFirstFrame() {
    new AnimationTimer() {
      @Override
      public void handle(long now) {
        stop();
        mark("first frame");
        window.loadIcons();
        PauseTransition warmUp = new PauseTransition(WARM_UP);
        warmUp.setOnFinished(
            event -> {
              watchdog = new FxWatchdog(STALL_THRESHOLD_MS);
              watchdog.start();
            });
        warmUp.play();
        if (Boolean.getBoolean(SMOKE_PROPERTY)) {
          PauseTransition quit = new PauseTransition(javafx.util.Duration.seconds(2));
          quit.setOnFinished(event -> Platform.exit());
          quit.play();
        }
      }
    }.start();
  }

  /** Logs how long after the JVM started a phase of the start-up ended. */
  static void mark(String phase) {
    ProcessHandle.current()
        .info()
        .startInstant()
        .ifPresent(
            started ->
                LOG.log(
                    System.Logger.Level.INFO,
                    "Start-up: "
                        + phase
                        + " at "
                        + Duration.between(started, Instant.now()).toMillis()
                        + " ms"));
  }

  /** Opens a folder in the system's file manager (without loading AWT). */
  private void openFolder(Path folder) {
    getHostServices().showDocument(folder.toUri().toString());
  }

  @Override
  public void stop() {
    if (watchdog != null) {
      watchdog.stop();
    }
    if (window != null) {
      window.saveBounds();
    }
    if (context != null) {
      context.close();
    }
  }
}
