package com.example.datacraft.desktop.ui.shell;

import com.example.datacraft.desktop.service.ProfileService;
import com.example.datacraft.desktop.service.Session;
import com.example.datacraft.desktop.store.Profile;
import com.example.datacraft.desktop.ui.component.Controls;
import com.example.datacraft.desktop.ui.component.Icons;
import com.example.datacraft.desktop.ui.shell.Navigator.Destination;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import javafx.geometry.Insets;
import javafx.scene.CacheHint;
import javafx.scene.Node;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.VBox;

/**
 * The cyanotype plate on the left: the wordmark, the four main views, then settings and the active
 * connection. It can fold to 64 px, keeping only the icons.
 */
public final class Sidebar extends VBox {

  static final double WIDTH = 208;
  static final double COLLAPSED = 64;

  private final Map<Destination, ToggleButton> items = new EnumMap<>(Destination.class);
  private final ToggleGroup group = new ToggleGroup();
  private final Label name = Controls.label("数艺工坊", "wordmark-name");
  private final Label latin = Controls.label("DataCraft", "wordmark-latin");
  private final MenuButton profile = new MenuButton("未连接");
  private boolean collapsed;

  public Sidebar(
      Consumer<Destination> navigate,
      ProfileService profiles,
      Session session,
      Executor ui,
      Consumer<String> failure) {
    getStyleClass().add("sidebar");
    VBox wordmark = new VBox(name, latin);
    wordmark.getStyleClass().add("wordmark");
    getChildren().add(wordmark);
    for (Destination destination : Destination.values()) {
      if (destination == Destination.SETTINGS) {
        continue;
      }
      getChildren().add(item(destination, navigate));
    }
    getChildren().add(Controls.grow());
    Node divider = Controls.rule();
    divider.getStyleClass().setAll("sidebar-divider");
    VBox.setMargin(divider, new Insets(8, 16, 8, 16));
    getChildren().addAll(divider, item(Destination.SETTINGS, navigate));

    profile.getStyleClass().add("profile-switch");
    profile.setMaxWidth(Double.MAX_VALUE);
    profile.setTooltip(new Tooltip("切换连接"));
    VBox.setMargin(profile, new Insets(8, 16, 0, 16));
    profile.setOnShowing(event -> fillProfiles(profiles, ui, navigate, failure));
    getChildren().add(profile);
    session.current().ifPresent(connection -> profile.setText(connection.profile().name()));
    session.subscribe(
        connection ->
            ui.execute(
                () -> profile.setText(connection.map(c -> c.profile().name()).orElse("未连接"))));

    group
        .selectedToggleProperty()
        .addListener(
            (property, before, selected) -> {
              if (selected == null && before != null) {
                before.setSelected(true); // the current view stays marked
              }
            });
    setMinWidth(WIDTH);
    setPrefWidth(WIDTH);
    setMaxWidth(WIDTH);
    // The plate never changes between frames: draw it once and reuse the bitmap.
    setCache(true);
    setCacheHint(CacheHint.SPEED);
  }

  private ToggleButton item(Destination destination, Consumer<Destination> navigate) {
    ToggleButton item = new ToggleButton(destination.title());
    item.getStyleClass().setAll("nav-item");
    item.setGraphic(icon(destination));
    item.setToggleGroup(group);
    item.setMaxWidth(Double.MAX_VALUE);
    item.setAccessibleText(destination.title());
    item.setOnAction(event -> navigate.accept(destination));
    items.put(destination, item);
    return item;
  }

  private static Node icon(Destination destination) {
    return switch (destination) {
      case OVERVIEW -> Icons.overview();
      case JOBS -> Icons.jobs();
      case PIPELINES -> Icons.pipelines();
      case ACTIVITY -> Icons.activity();
      case SETTINGS -> Icons.settings();
    };
  }

  private void fillProfiles(
      ProfileService profiles,
      Executor ui,
      Consumer<Destination> navigate,
      Consumer<String> failure) {
    profiles
        .list()
        .thenAccept(
            list ->
                ui.execute(
                    () -> {
                      profile.getItems().clear();
                      for (Profile each : list) {
                        MenuItem choose = new MenuItem(each.name());
                        choose.setOnAction(
                            event ->
                                profiles
                                    .activate(each)
                                    .exceptionally(
                                        problem -> {
                                          ui.execute(
                                              () ->
                                                  failure.accept(
                                                      "无法切换到 "
                                                          + each.name()
                                                          + "："
                                                          + problem.getMessage()));
                                          return null;
                                        }));
                        profile.getItems().add(choose);
                      }
                      if (!list.isEmpty()) {
                        profile.getItems().add(new SeparatorMenuItem());
                      }
                      MenuItem manage = new MenuItem("管理连接");
                      manage.setOnAction(event -> navigate.accept(Destination.SETTINGS));
                      profile.getItems().add(manage);
                    }));
  }

  /** Marks the current view. */
  public void select(Destination destination) {
    ToggleButton item = items.get(destination);
    if (item != null && !item.isSelected()) {
      item.setSelected(true);
    }
  }

  public void collapsed(boolean fold) {
    collapsed = fold;
    double width = fold ? COLLAPSED : WIDTH;
    setMinWidth(width);
    setPrefWidth(width);
    setMaxWidth(width);
    if (fold) {
      getStyleClass().add("collapsed");
    } else {
      getStyleClass().remove("collapsed");
    }
    latin.setVisible(!fold);
    latin.setManaged(!fold);
    name.setText(fold ? "数" : "数艺工坊");
    profile.setVisible(!fold);
    profile.setManaged(!fold);
    items
        .values()
        .forEach(
            item -> {
              item.setContentDisplay(fold ? ContentDisplay.GRAPHIC_ONLY : ContentDisplay.LEFT);
              item.setTooltip(fold ? new Tooltip(item.getText()) : null);
            });
  }

  public boolean collapsed() {
    return collapsed;
  }
}
