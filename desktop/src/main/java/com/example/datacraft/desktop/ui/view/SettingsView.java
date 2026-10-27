package com.example.datacraft.desktop.ui.view;

import com.example.datacraft.desktop.app.Preferences;
import com.example.datacraft.desktop.service.BackendStatus;
import com.example.datacraft.desktop.service.ProfileService.TestResult;
import com.example.datacraft.desktop.store.Profile;
import com.example.datacraft.desktop.ui.Motion;
import com.example.datacraft.desktop.ui.component.Controls;
import com.example.datacraft.desktop.ui.component.Icons;
import com.example.datacraft.desktop.ui.component.StatusBadge;
import com.example.datacraft.desktop.ui.component.Tones;
import com.example.datacraft.desktop.ui.shell.Dialogs;
import com.example.datacraft.desktop.ui.shell.Toasts;
import com.example.datacraft.desktop.util.Formats;
import com.example.datacraft.desktop.viewmodel.Labels;
import com.example.datacraft.desktop.viewmodel.SettingsModel;
import java.util.List;
import java.util.function.Consumer;
import javafx.beans.binding.Bindings;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
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
    CheckBox remember = new CheckBox("在本机加密保存令牌与口令");
    remember.selectedProperty().bindBidirectional(model.remember());
    Label rememberHint =
        Controls.label("不保存时，令牌与口令只在本次运行中使用。加密方式：" + model.vaultDescription() + "。", "caption");
    rememberHint.setWrapText(true);

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
    form.add(new VBox(4, remember, rememberHint), 1, line++);

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

    VBox testResult = new VBox(6);
    model.testResult().addListener((property, before, result) -> showTest(testResult, result));

    VBox right = new VBox(16, form, error, buttons, testResult);
    HBox.setHgrow(right, Priority.ALWAYS);
    HBox body = new HBox(32, left, right);
    return new VBox(16, Controls.label("连接", "section-title"), body);
  }

  private static void showTest(VBox box, TestResult result) {
    box.getChildren().clear();
    if (result == null) {
      return;
    }
    box.getChildren()
        .addAll(testLine("引擎 API", result.engine()), testLine("Airflow", result.airflow()));
  }

  private static Node testLine(String name, BackendStatus status) {
    StatusBadge badge = new StatusBadge();
    badge.set(Tones.of(status), name + " " + Labels.status(status));
    String detail =
        status.state() == BackendStatus.State.UP
            ? status.latency().map(Formats::latency).orElse("") + "  " + status.summary()
            : status.summary();
    Label text = Controls.label(detail, "secondary");
    text.setWrapText(true);
    HBox line = Controls.row(12, badge, text);
    line.setAlignment(Pos.TOP_LEFT);
    return line;
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
    HBox theme =
        Controls.segmented(
            List.of(Preferences.Theme.LIGHT, Preferences.Theme.DARK, Preferences.Theme.SYSTEM),
            List.of("浅色", "深色", "跟随系统"),
            model.theme());
    CheckBox reduced = new CheckBox("减少动效");
    reduced.selectedProperty().bindBidirectional(model.reducedMotion());
    reduced.selectedProperty().addListener((property, before, now) -> Motion.reduced(now));
    Label hint = Controls.label("视图切换、侧滑面板与通知改为直接出现。", "caption");
    GridPane grid = settingsGrid();
    grid.addRow(0, fieldLabel("主题"), theme);
    grid.addRow(1, fieldLabel("动效"), new VBox(4, reduced, hint));
    return new VBox(16, Controls.label("外观", "section-title"), grid);
  }

  private Node refreshSection() {
    HBox mode =
        Controls.segmented(
            List.of(Preferences.Refresh.STANDARD, Preferences.Refresh.SAVER),
            List.of("标准", "节能"),
            model.refresh());
    Label hint = Controls.label("", "caption");
    hint.textProperty()
        .bind(
            Bindings.createStringBinding(
                () ->
                    model.refresh().get() == Preferences.Refresh.STANDARD
                        ? "健康检查每 5 秒一次，流水线每 10 秒刷新。"
                        : "健康检查每 15 秒一次，其余刷新也放慢到三分之一。",
                model.refresh()));
    Spinner<Integer> timeout = new Spinner<>(1, 24 * 60, model.runTimeout().get());
    timeout.setEditable(true);
    timeout.setPrefWidth(110);
    timeout.valueProperty().addListener((property, before, now) -> model.runTimeout().set(now));
    Label minutes = Controls.label("分钟。超过后停止等待结果，作业在引擎上继续运行。", "caption");
    minutes.setWrapText(true);
    GridPane grid = settingsGrid();
    grid.addRow(0, fieldLabel("刷新频率"), new VBox(4, mode, hint));
    grid.addRow(1, fieldLabel("作业等待上限"), Controls.row(8, timeout, minutes));
    return new VBox(16, Controls.label("刷新", "section-title"), grid);
  }

  private Node localData(Dialogs dialogs, Toasts toasts, Consumer<java.nio.file.Path> openFolder) {
    TextField location = new TextField(model.dataLocation().toString());
    location.setEditable(false);
    location.getStyleClass().add("latin");
    Button open = Controls.button("打开文件夹", () -> openFolder.accept(model.dataLocation()), "");
    Label size = Controls.label("", "");
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
    Label what = Controls.label("这里只保存设置、连接配置、参数预设与缓存，不保存任何业务数据。", "caption");
    what.setWrapText(true);
    GridPane grid = settingsGrid();
    HBox locationRow = Controls.row(8, location, open);
    HBox.setHgrow(location, Priority.ALWAYS);
    grid.addRow(0, fieldLabel("位置"), locationRow);
    grid.addRow(1, fieldLabel("占用"), Controls.row(12, size, clear));
    grid.add(what, 1, 2);
    return new VBox(16, Controls.label("本机数据", "section-title"), grid);
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
