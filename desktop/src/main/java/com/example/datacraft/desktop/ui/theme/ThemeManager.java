package com.example.datacraft.desktop.ui.theme;

import com.example.datacraft.desktop.app.Preferences;
import com.example.datacraft.desktop.app.Preferences.Theme;
import java.util.List;
import java.util.Objects;
import javafx.application.ColorScheme;
import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.css.PseudoClass;
import javafx.scene.Parent;
import javafx.scene.Scene;

/**
 * Applies the light or dark theme. Both theme files stay loaded; the dark tokens apply under the
 * root's "dark" pseudo-class. A state transition updates the palette without changing selector
 * membership and rematching every base style. "跟随系统" follows the platform's colour scheme as it
 * changes.
 */
public final class ThemeManager {

  static final String BASE = stylesheet("base.css");
  static final String LIGHT = stylesheet("light.css");
  static final String DARK = stylesheet("dark.css");
  private static final PseudoClass DARK_STATE = PseudoClass.getPseudoClass("dark");

  private final Preferences preferences;
  private final SimpleBooleanProperty dark = new SimpleBooleanProperty(false);
  private final List<Scene> scenes = new java.util.ArrayList<>();
  private final String fontFamily;
  private Runnable beforeSwitch = () -> {};

  public ThemeManager(Preferences preferences) {
    this.preferences = preferences;
    this.fontFamily = Fonts.load();
    Platform.getPreferences()
        .colorSchemeProperty()
        .addListener((property, before, scheme) -> apply());
    dark.set(resolveDark());
  }

  private static String stylesheet(String name) {
    return Objects.requireNonNull(
            ThemeManager.class.getResource("/com/example/datacraft/desktop/theme/" + name), name)
        .toExternalForm();
  }

  /** Styles a scene (the main window, a dialog) and keeps it in step with later changes. */
  public void style(Scene scene) {
    scenes.add(scene);
    scene.getStylesheets().setAll(stylesheets());
    style(scene.getRoot());
  }

  /** The root of a scene (or a dialog pane): the UI font and the current theme's state. */
  public void style(Parent root) {
    root.setStyle("-fx-font-family: \"" + fontFamily + "\";");
    mark(root);
  }

  /** Stylesheets for a separately created scene, e.g. a dialog pane. */
  public List<String> stylesheets() {
    return List.of(BASE, LIGHT, DARK);
  }

  /** Runs just before the theme changes, e.g. to take hidden views out of the scene. */
  public void beforeSwitch(Runnable action) {
    this.beforeSwitch = action;
  }

  /** Re-reads the preference and the platform scheme; switches every styled scene if it changed. */
  public void apply() {
    boolean wantDark = resolveDark();
    if (wantDark == dark.get()) {
      return;
    }
    beforeSwitch.run();
    dark.set(wantDark);
    for (Scene scene : scenes) {
      mark(scene.getRoot());
    }
  }

  private void mark(Parent root) {
    root.pseudoClassStateChanged(DARK_STATE, dark.get());
  }

  private boolean resolveDark() {
    Theme theme = preferences.theme();
    if (theme == Theme.SYSTEM) {
      return Platform.getPreferences().getColorScheme() == ColorScheme.DARK;
    }
    return theme == Theme.DARK;
  }
}
