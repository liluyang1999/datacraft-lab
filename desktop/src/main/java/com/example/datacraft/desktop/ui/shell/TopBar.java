package com.example.datacraft.desktop.ui.shell;

import com.example.datacraft.desktop.service.BackendStatus;
import com.example.datacraft.desktop.ui.component.Controls;
import com.example.datacraft.desktop.ui.component.Icons;
import com.example.datacraft.desktop.ui.component.StatusRing;
import com.example.datacraft.desktop.ui.component.Tones;
import com.example.datacraft.desktop.util.Formats;
import com.example.datacraft.desktop.viewmodel.Labels;
import com.example.datacraft.desktop.viewmodel.StatusModel;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;

/** The view title on the left; on the right, each backend's dot and latency, and refresh. */
public final class TopBar extends HBox {

  private final Label title = Controls.label("", "view-title");

  public TopBar(StatusModel status, Runnable refresh, Runnable toggleSidebar) {
    getStyleClass().add("top-bar");
    getChildren()
        .addAll(
            Controls.iconButton(Icons.sidebar(), "收起或展开侧栏", toggleSidebar),
            title,
            Controls.grow(),
            backend("引擎", status.engine()),
            backend("Airflow", status.airflow()),
            Controls.iconButton(Icons.refresh(), "刷新（Ctrl+R）", refresh));
  }

  private static HBox backend(
      String name, javafx.beans.value.ObservableValue<BackendStatus> value) {
    StatusRing ring = StatusRing.dot();
    Label title = Controls.label(name, "");
    title.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
    Label detail = Controls.label("", "latency", "secondary");
    Tooltip tooltip = new Tooltip();
    HBox box = new HBox(ring, title, detail);
    box.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
    box.getStyleClass().add("backend-status");
    Tooltip.install(box, tooltip);
    Runnable show =
        () -> {
          BackendStatus status = value.getValue();
          ring.set(Tones.of(status), name + " " + Labels.status(status));
          if (status.state() == BackendStatus.State.UP) {
            detail.setText(status.latency().map(Formats::latency).orElse(""));
            if (!detail.getStyleClass().contains("latin")) {
              detail.getStyleClass().add("latin");
            }
          } else {
            detail.setText(Labels.status(status));
            detail.getStyleClass().remove("latin");
          }
          tooltip.setText(status.summary());
          box.setAccessibleText(name + " " + Labels.status(status));
        };
    value.addListener((property, before, now) -> show.run());
    show.run();
    return box;
  }

  public void title(String text) {
    title.setText(text);
  }
}
