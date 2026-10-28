package com.example.datacraft.desktop.ui.shell;

import com.example.datacraft.desktop.app.AppContext;
import com.example.datacraft.desktop.app.Preferences;
import com.example.datacraft.desktop.ui.Motion;
import com.example.datacraft.desktop.ui.shell.Navigator.Destination;
import com.example.datacraft.desktop.ui.theme.ThemeManager;
import com.example.datacraft.desktop.ui.view.ActivityView;
import com.example.datacraft.desktop.ui.view.JobsView;
import com.example.datacraft.desktop.ui.view.OverviewView;
import com.example.datacraft.desktop.ui.view.PipelinesView;
import com.example.datacraft.desktop.ui.view.SettingsView;
import com.example.datacraft.desktop.ui.view.View;
import com.example.datacraft.desktop.util.Fx;
import com.example.datacraft.desktop.viewmodel.ActivityModel;
import com.example.datacraft.desktop.viewmodel.JobsModel;
import com.example.datacraft.desktop.viewmodel.OverviewModel;
import com.example.datacraft.desktop.viewmodel.PipelinesModel;
import com.example.datacraft.desktop.viewmodel.SettingsModel;
import com.example.datacraft.desktop.viewmodel.StatusModel;
import java.nio.file.Path;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
import javafx.stage.Stage;

/**
 * The window: sidebar, top bar, the content area with the side panel over it, and the toast layer.
 * Views and their models are created the first time they are opened.
 */
public final class MainWindow {

  private final AppContext context;
  private final Stage stage;
  private final ThemeManager theme;
  private final Dialogs dialogs;
  private final Toasts toasts = new Toasts();
  private final Drawer drawer = new Drawer();
  private final StackPane viewHost = new StackPane();
  private final StatusModel status;
  private final Sidebar sidebar;
  private final TopBar topBar;
  private final Navigator navigator;
  private final Consumer<Path> openFolder;
  private PipelinesView pipelinesView;
  private JobsView jobsView;
  private Preferences.Bounds normalBounds;
  private boolean boundsQueued;
  private boolean compact;

  public MainWindow(AppContext context, Stage stage, Consumer<Path> openFolder) {
    this.context = context;
    this.stage = stage;
    this.openFolder = openFolder;
    Preferences preferences = context.preferences();
    Motion.reduced(preferences.reducedMotion());
    this.theme = new ThemeManager(preferences);
    this.dialogs = new Dialogs(theme);
    this.status = new StatusModel(context.monitor(), Fx.UI);
    this.navigator = new Navigator(viewHost, this::create);
    this.sidebar =
        new Sidebar(this::go, context.profiles(), context.session(), Fx.UI, toasts::failure);
    this.topBar =
        new TopBar(
            status,
            this::refresh,
            () -> {
              sidebar.collapsed(!sidebar.collapsed());
              preferences.sidebarCollapsed(sidebar.collapsed());
            });
    sidebar.collapsed(preferences.sidebarCollapsed());
    theme.beforeSwitch(navigator::detachHidden);

    StackPane content = new StackPane(viewHost, drawer);
    VBox center = new VBox(topBar, content);
    VBox.setVgrow(content, Priority.ALWAYS);
    BorderPane frame = new BorderPane(center);
    frame.setLeft(sidebar);
    StackPane root = new StackPane(frame, toasts);
    StackPane.setAlignment(toasts, Pos.BOTTOM_RIGHT);

    Scene scene = new Scene(root);
    theme.style(scene);
    dialogs.owner(stage);
    keys(scene);
    stage.setScene(scene);
    stage.setTitle("数艺工坊");
    restoreBounds(preferences);
    adjustSidebar();
    stage.widthProperty().addListener(ignored -> adjustSidebar());
    javafx.beans.InvalidationListener moved = ignored -> rememberNormalBounds();
    stage.xProperty().addListener(moved);
    stage.yProperty().addListener(moved);
    stage.widthProperty().addListener(moved);
    stage.heightProperty().addListener(moved);
    stage.maximizedProperty().addListener(moved);

    preferences.subscribe(
        key ->
            Fx.UI.execute(
                () -> {
                  theme.apply();
                  Motion.reduced(preferences.reducedMotion());
                }));
    stage
        .iconifiedProperty()
        .addListener(
            (property, before, minimised) -> {
              navigator.windowShown(!minimised);
              if (minimised) {
                context.monitor().pause();
              } else {
                context.monitor().start();
                context.monitor().refresh();
              }
            });
    go(Destination.of(preferences.lastView()));
  }

  private View create(Destination destination) {
    return switch (destination) {
      case OVERVIEW ->
          new OverviewView(
              new OverviewModel(
                  context.pipelines(),
                  context.jobs(),
                  context.session(),
                  context.preferences(),
                  context.ticker(),
                  context.clock(),
                  Fx.UI),
              status,
              context.clock(),
              this::openPipeline,
              this::triggerPipeline,
              this::openJob,
              () -> go(Destination.SETTINGS));
      case JOBS -> {
        jobsView =
            new JobsView(
                new JobsModel(
                    context.jobs(),
                    context.catalog(),
                    context.presets(),
                    context.session(),
                    context.preferences(),
                    context.ticker(),
                    Fx.UI),
                context.clock(),
                dialogs,
                () -> go(Destination.SETTINGS));
        yield jobsView;
      }
      case PIPELINES -> {
        pipelinesView =
            new PipelinesView(
                new PipelinesModel(
                    context.pipelines(),
                    context.catalog(),
                    context.json(),
                    context.session(),
                    context.preferences(),
                    context.ticker(),
                    Fx.UI),
                context.clock(),
                drawer,
                toasts,
                context.json(),
                () -> go(Destination.SETTINGS));
        yield pipelinesView;
      }
      case ACTIVITY ->
          new ActivityView(new ActivityModel(context.activity(), Fx.UI), context.clock(), dialogs);
      case SETTINGS ->
          new SettingsView(
              new SettingsModel(
                  context.profiles(),
                  context.session(),
                  context.preferences(),
                  context.activity(),
                  context.recentRuns(),
                  context.paths(),
                  context.vault().description(),
                  Fx.UI),
              dialogs,
              toasts,
              openFolder);
    };
  }

  public void go(Destination destination) {
    if (drawer.isOpen()) {
      drawer.close();
    }
    navigator.go(destination);
    sidebar.select(destination);
    topBar.title(destination.title());
    context.preferences().lastView(destination.id());
  }

  private void openPipeline(String dagId) {
    go(Destination.PIPELINES);
    if (pipelinesView != null) {
      pipelinesView.select(dagId);
    }
  }

  private void triggerPipeline(String dagId) {
    go(Destination.PIPELINES);
    if (pipelinesView != null) {
      pipelinesView.trigger(dagId);
    }
  }

  private void openJob(String jobName) {
    go(Destination.JOBS);
    if (jobsView != null) {
      jobsView.select(jobName);
    }
  }

  private void refresh() {
    status.refresh();
    View view = navigator.view();
    if (view != null) {
      view.refresh();
    }
  }

  private void keys(Scene scene) {
    Destination[] order = Destination.values();
    KeyCode[] digits = {
      KeyCode.DIGIT1, KeyCode.DIGIT2, KeyCode.DIGIT3, KeyCode.DIGIT4, KeyCode.DIGIT5
    };
    for (int index = 0; index < order.length; index++) {
      Destination destination = order[index];
      scene
          .getAccelerators()
          .put(
              new KeyCodeCombination(digits[index], KeyCombination.SHORTCUT_DOWN),
              () -> go(destination));
    }
    scene
        .getAccelerators()
        .put(new KeyCodeCombination(KeyCode.R, KeyCombination.SHORTCUT_DOWN), this::refresh);
    scene.addEventFilter(
        javafx.scene.input.KeyEvent.KEY_PRESSED,
        event -> {
          if (event.getCode() == KeyCode.ESCAPE && drawer.isOpen()) {
            drawer.close();
            event.consume();
          }
        });
  }

  private void restoreBounds(Preferences preferences) {
    var placement =
        WindowPlacement.resolve(
            preferences.window(),
            Screen.getScreens().stream().map(Screen::getVisualBounds).toList(),
            Screen.getPrimary().getVisualBounds());
    var area = placement.area();
    var bounds = placement.bounds();
    normalBounds = bounds;
    // These are OUTER window dimensions, including the title bar. Screen bounds already
    // account for output scale and taskbars; applying DPI scaling again would overflow.
    stage.setMinWidth(Math.min(WindowPlacement.MIN_WIDTH, area.getWidth()));
    stage.setMinHeight(Math.min(WindowPlacement.MIN_HEIGHT, area.getHeight()));
    stage.setX(bounds.x());
    stage.setY(bounds.y());
    stage.setWidth(bounds.width());
    stage.setHeight(bounds.height());
    stage.setMaximized(bounds.maximized());
  }

  /** Draws the window icons; called after the first frame so that they do not delay it. */
  public void loadIcons() {
    stage.getIcons().setAll(AppIcon.images());
  }

  /** Remembers where the window was, for the next start. */
  public void saveBounds() {
    if (stage.isIconified()) {
      return;
    }
    if (!stage.isMaximized()) {
      normalBounds = currentBounds();
    }
    context
        .preferences()
        .window(
            new Preferences.Bounds(
                normalBounds.x(),
                normalBounds.y(),
                normalBounds.width(),
                normalBounds.height(),
                stage.isMaximized()));
  }

  private Preferences.Bounds currentBounds() {
    return new Preferences.Bounds(
        stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight(), false);
  }

  private void rememberNormalBounds() {
    if (boundsQueued) {
      return;
    }
    boundsQueued = true;
    // Window managers report position, size and maximized state separately. Coalesce their
    // notifications before deciding whether the resulting rectangle is a normal window.
    Platform.runLater(
        () -> {
          boundsQueued = false;
          if (stage.isShowing() && !stage.isMaximized() && !stage.isIconified()) {
            normalBounds = currentBounds();
          }
        });
  }

  private void adjustSidebar() {
    boolean narrow = stage.getWidth() < 1100;
    if (narrow != compact) {
      compact = narrow;
      // Apply only when crossing the breakpoint; a user can still expand it manually.
      sidebar.collapsed(narrow || context.preferences().sidebarCollapsed());
    }
  }

  public void show() {
    stage.show();
  }

  public Navigator navigator() {
    return navigator;
  }
}
