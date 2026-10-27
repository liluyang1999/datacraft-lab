package com.example.datacraft.desktop.ui.view;

import com.example.datacraft.desktop.app.Preferences;
import com.example.datacraft.desktop.service.BackendStatus;
import com.example.datacraft.desktop.service.ProfileService.TestResult;
import com.example.datacraft.desktop.store.Profile;
import com.example.datacraft.desktop.ui.Motion;
import com.example.datacraft.desktop.ui.component.Controls;
import com.example.datacraft.desktop.ui.component.Icons;
import com.example.datacraft.desktop.ui.component.Icons.Glyph;
import com.example.datacraft.desktop.ui.component.StatusRing;
import com.example.datacraft.desktop.ui.component.Tones;
import com.example.datacraft.desktop.ui.shell.Dialogs;
import com.example.datacraft.desktop.ui.shell.Toasts;
import com.example.datacraft.desktop.util.Formats;
import com.example.datacraft.desktop.viewmodel.Labels;
import com.example.datacraft.desktop.viewmodel.SettingsModel;
import java.util.List;
import java.util.function.Consumer;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ObjectProperty;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/** 设置: connections, appearance, refresh, and the local data in ~/.datacraft. */
public final class SettingsView implements View {

  private final SettingsModel model;
  private final ScrollPane root;

  public SettingsView(
      SettingsModel model,
      Dialogs dialogs,
      Toasts toasts,
      Consumer<java.nio.file.Path> openFolder) {
    this.model = model;
    VBox page =
        new VBox(
            32,
            connections(dialogs, toasts),
            Controls.rule(),
            appearance(),
            Controls.rule(),
            refreshSection(),
            Controls.rule(),
            localData(dialogs, toasts, openFolder));
    page.getStyleClass().add("view-content");
    page.setMaxWidth(980);
    root = new ScrollPane(page);
    root.setFitToWidth(true);
  }

  // ---------- connections ----------

  private Node connections(Dialogs dialogs, Toasts toasts) {
    ListView<Profile> list = new ListView<>(model.profiles());
    list.setPrefWidth(200);
    list.setMinWidth(180);
    list.setPrefHeight(36 * 5 + 8);
    list.getStyleClass().add("profile-list");
    list.setPlaceholder(Controls.label("还没有连接", "secondary"));
    list.setCellFactory(
        view ->
            new ListCell<>() {
              private final Label name = new Label();
              private final Label current = Controls.label("当前", "caption");
              private final HBox row = Controls.row(8, name, Controls.grow(), current);

              @Override
              protected void updateItem(Profile profile, boolean empty) {
                super.updateItem(profile, empty);
                if (empty || profile == null) {
                  setGraphic(null);
                  return;
                }
                name.setText(profile.name());
                current.setVisible(profile.id() == model.activeId().get());
                setGraphic(row);
              }
            });
    model.activeId().addListener((property, before, now) -> list.refresh());
    list.getSelectionModel()
        .selectedItemProperty()
        .addListener(
            (property, before, profile) -> {
              if (profile != null && !profile.equals(model.editing().get())) {
                model.edit(profile);
              }
            });
    model
        .editing()
        .addListener(
            (property, before, profile) -> {
              if (profile == null) {
                list.getSelectionModel().clearSelection();
              } else if (!profile.equals(list.getSelectionModel().getSelectedItem())) {
                list.getSelectionModel().select(profile);
              }
            });
    Button add = Controls.button("添加连接", model::newProfile, "quiet");
    add.setGraphic(Icons.plus());
    VBox left = new VBox(8, list, add);

    TextField name = field(model.name(), "例如 本机部署");
    TextField engineUrl = field(model.engineUrl(), "http://127.0.0.1:8088");
    engineUrl.getStyleClass().add("latin");
    PasswordField token = new PasswordField();
    token.textProperty().bindBidirectional(model.engineToken());
    token.setPromptText("DATACRAFT_API_TOKEN 的值");
    TextField airflowUrl = field(model.airflowUrl(), "http://127.0.0.1:8080");
    airflowUrl.getStyleClass().add("latin");
    TextField user = field(model.airflowUser(), "admin");
    PasswordField password = new PasswordField();
    password.textProperty().bindBidirectional(model.airflowPassword());
    password.setPromptText("Airflow 口令");
    ToggleButton remember = Controls.toggleSwitch("加密保存令牌与口令", on -> {});
    remember.selectedProperty().bindBidirectional(model.remember());
    HBox rememberRow =
        Controls.row(
            10,
            remember,
            Controls.label("加密保存", ""),
            Controls.info("令牌与口令在本机加密保存（" + model.vaultDescription() + "）。关闭时只在本次运行中使用，不写入磁盘。"));

    GridPane form = new GridPane();
    form.setHgap(16);
    form.setVgap(10);
    ColumnConstraints labels = new ColumnConstraints(96);
    ColumnConstraints fields = new ColumnConstraints();
    fields.setHgrow(Priority.ALWAYS);
    form.getColumnConstraints().addAll(labels, fields);
    int line = 0;
    form.addRow(line++, fieldLabel("名称"), name);
    form.addRow(line++, fieldLabel("引擎地址"), engineUrl);
    form.addRow(line++, fieldLabel("引擎令牌"), token);
    form.addRow(line++, fieldLabel("Airflow 地址"), airflowUrl);
    form.addRow(line++, fieldLabel("用户名"), user);
    form.addRow(line++, fieldLabel("口令"), password);
    form.add(rememberRow, 1, line++);

    Label error = Controls.label("", "notice", "failed");
    error.setWrapText(true);
    error.setMaxWidth(Double.MAX_VALUE);
    error.textProperty().bind(model.formError());
    error.visibleProperty().bind(model.formError().isNotEmpty());
    error.managedProperty().bind(error.visibleProperty());

    Button save =
        Controls.button(
            "保存",
            () ->
                model
                    .save()
                    .thenAccept(
                        saved ->
                            javafx.application.Platform.runLater(
                                () -> toasts.success("已保存连接 " + saved.name()))),
            "primary");
    Button test = Controls.button("测试连接", model::test, "");
    test.setGraphic(Icons.of(Glyph.LINK, 16));
    Button connect =
        Controls.button(
            "使用此连接",
            () ->
                model
                    .activate()
                    .thenRun(
                        () ->
                            javafx.application.Platform.runLater(
                                () -> toasts.info("已切换到 " + model.name().get()))),
            "");
    connect
        .disableProperty()
        .bind(
            Bindings.createBooleanBinding(
                () ->
                    model.editing().get() != null
                        && model.editing().get().id() == model.activeId().get(),
                model.editing(),
                model.activeId()));
    Button delete =
        Controls.button(
            "删除",
            () -> {
              Profile profile = model.editing().get();
              if (profile != null
                  && dialogs.confirm(
                      "删除连接", "删除连接“" + profile.name() + "”？它保存的令牌、口令与最近运行一并删除。", "删除")) {
                model.delete();
              }
            },
            "danger");
    delete.disableProperty().bind(model.editing().isNull());
    save.disableProperty().bind(model.busy());
    test.disableProperty().bind(model.busy());
    HBox buttons = Controls.row(12, save, test, connect, Controls.grow(), delete);

    HBox testResult = new HBox(24);
    model.testResult().addListener((property, before, result) -> showTest(testResult, result));

    VBox right = new VBox(16, form, error, buttons, testResult);
    HBox.setHgrow(right, Priority.ALWAYS);
    HBox body = new HBox(32, left, right);
    return new VBox(16, OverviewView.heading(Glyph.LINK, "连接"), body);
  }

  private static void showTest(HBox box, TestResult result) {
    box.getChildren().clear();
    if (result == null) {
      return;
    }
    box.getChildren()
        .addAll(
            testTile("引擎 API", Glyph.ENGINE, result.engine()),
            testTile("Airflow", Glyph.AIRFLOW, result.airflow()));
  }

  /** One backend's answer to the test: its ring, its name and latency; the details on hover. */
  private static Node testTile(String name, Glyph glyph, BackendStatus status) {
    StatusRing ring = new StatusRing(36, 3, Icons.of(glyph, 16));
    ring.set(Tones.of(status), name + " " + Labels.status(status));
    boolean up = status.state() == BackendStatus.State.UP;
    Label title = Controls.label(name, "name");
    Label detail =
        Controls.label(
            up ? status.latency().map(Formats::latency).orElse("") : Labels.status(status),
            up ? "latin" : "text-failed");
    HBox tile = Controls.row(10, ring, new VBox(2, title, detail));
    tile.getStyleClass().add("test-tile");
    Tooltip.install(tile, new Tooltip(status.summary()));
    return tile;
  }

  private static TextField field(javafx.beans.property.StringProperty value, String prompt) {
    TextField field = new TextField();
    field.textProperty().bindBidirectional(value);
    field.setPromptText(prompt);
    return field;
  }

  private static Label fieldLabel(String text) {
    return Controls.label(text, "field-label");
  }

  // ---------- appearance and refresh ----------

  private Node appearance() {
    HBox theme = themeTiles(model.theme());
    ToggleButton reduced = Controls.toggleSwitch("减少动效", on -> {});
    reduced.selectedProperty().bindBidirectional(model.reducedMotion());
    reduced.selectedProperty().addListener((property, before, now) -> Motion.reduced(now));
    HBox motion =
        Controls.row(
            10,
            reduced,
            Controls.label("减少动效", ""),
            Controls.info("视图切换、侧滑面板、通知与图表改为直接出现，运行中的圈不再转动。"));
    GridPane grid = settingsGrid();
    grid.addRow(0, fieldLabel("主题"), theme);
    grid.addRow(1, fieldLabel("动效"), motion);
    return new VBox(16, OverviewView.heading(Glyph.SUN, "外观"), grid);
  }

  /** Three small windows to pick from: light, dark, and half of each for "follow the system". */
  private static HBox themeTiles(ObjectProperty<Preferences.Theme> value) {
    List<Preferences.Theme> themes =
        List.of(Preferences.Theme.LIGHT, Preferences.Theme.DARK, Preferences.Theme.SYSTEM);
    List<String> names = List.of("浅色", "深色", "跟随系统");
    List<String> previews = List.of("light", "dark", "system");
    ToggleGroup group = new ToggleGroup();
    HBox tiles = new HBox(12);
    for (int index = 0; index < themes.size(); index++) {
      Preferences.Theme theme = themes.get(index);
      Region preview = new Region();
      preview.getStyleClass().addAll("theme-preview", previews.get(index));
      ToggleButton tile = new ToggleButton(names.get(index));
      tile.getStyleClass().add("theme-tile");
      tile.setGraphic(preview);
      tile.setContentDisplay(ContentDisplay.TOP);
      tile.setToggleGroup(group);
      tile.setUserData(theme);
      tile.setSelected(theme == value.get());
      tiles.getChildren().add(tile);
    }
    group
        .selectedToggleProperty()
        .addListener(
            (property, before, selected) -> {
              if (selected == null) {
                before.setSelected(true);
              } else {
                value.set((Preferences.Theme) selected.getUserData());
              }
            });
    value.addListener(
        (property, before, now) ->
            group.getToggles().forEach(toggle -> toggle.setSelected(now == toggle.getUserData())));
    return tiles;
  }

  private Node refreshSection() {
    HBox mode =
        Controls.segmented(
            List.of(Preferences.Refresh.STANDARD, Preferences.Refresh.SAVER),
            List.of("标准", "节能"),
            List.of(Glyph.BOLT, Glyph.LEAF),
            model.refresh());
    Spinner<Integer> timeout = new Spinner<>(1, 24 * 60, model.runTimeout().get());
    timeout.setEditable(true);
    timeout.setPrefWidth(110);
    timeout.valueProperty().addListener((property, before, now) -> model.runTimeout().set(now));
    GridPane grid = settingsGrid();
    grid.addRow(
        0,
        fieldLabel("刷新频率"),
        Controls.row(10, mode, Controls.info("标准：健康检查每 5 秒一次，流水线每 10 秒刷新。节能：都放慢到三分之一。")));
    grid.addRow(
        1,
        fieldLabel("等待上限"),
        Controls.row(
            10,
            timeout,
            Controls.label("分钟", "secondary"),
            Controls.info("运行作业时最多等这么久；超过后停止等待结果，作业在引擎上继续运行。")));
    return new VBox(16, OverviewView.heading(Glyph.REFRESH, "刷新"), grid);
  }

  private Node localData(Dialogs dialogs, Toasts toasts, Consumer<java.nio.file.Path> openFolder) {
    TextField location = new TextField(model.dataLocation().toString());
    location.setEditable(false);
    location.getStyleClass().add("latin");
    Button open =
        Controls.iconButton(
            Icons.of(Glyph.FOLDER), "打开文件夹", () -> openFolder.accept(model.dataLocation()));
    Label size = Controls.label("", "figure-small", "latin");
    size.textProperty().bind(model.dataSize());
    Button clear =
        Controls.button(
            "清除缓存",
            () -> {
              if (dialogs.confirm("清除缓存", "删除本机缓存的最近运行与活动记录？连接配置、预设与设置保留。", "清除")) {
                model
                    .clearCache()
                    .thenRun(
                        () -> javafx.application.Platform.runLater(() -> toasts.success("已清除缓存")));
              }
            },
            "danger");
    clear.setGraphic(Icons.of(Glyph.TRASH, 16));
    GridPane grid = settingsGrid();
    HBox locationRow =
        Controls.row(8, location, open, Controls.info("这里只保存设置、连接配置、参数预设与缓存，不保存任何业务数据。"));
    HBox.setHgrow(location, Priority.ALWAYS);
    grid.addRow(0, fieldLabel("位置"), locationRow);
    grid.addRow(1, fieldLabel("占用"), Controls.row(12, size, clear));
    return new VBox(16, OverviewView.heading(Glyph.DATABASE, "本机数据"), grid);
  }

  private static GridPane settingsGrid() {
    GridPane grid = new GridPane();
    grid.setHgap(16);
    grid.setVgap(14);
    ColumnConstraints labels = new ColumnConstraints(96);
    ColumnConstraints fields = new ColumnConstraints();
    fields.setHgrow(Priority.ALWAYS);
    grid.getColumnConstraints().addAll(labels, fields);
    return grid;
  }

  @Override
  public Node node() {
    return root;
  }

  @Override
  public void shown() {
    model.load();
  }
}
