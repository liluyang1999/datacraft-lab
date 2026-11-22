package com.example.datacraft.desktop.ui.shell;

import javafx.scene.layout.Pane;
import javafx.scene.shape.Rectangle;

/**
 * The logomark on the sidebar: the app icon's run ribbon (five segments, the third one running) on
 * a faint plate. The icon itself is a Prussian-blue plate, which would vanish on the sidebar, so
 * here only the ribbon carries the identity. Colours come from the theme ({@code .brand-*}).
 */
final class BrandMark extends Pane {

  static final double SIZE = 32;
  private static final String[] SEGMENTS = {"success", "success", "running", "success", "light"};

  BrandMark() {
    getStyleClass().add("brand-mark");
    Rectangle plate = new Rectangle(0, 0, SIZE, SIZE);
    plate.setArcWidth(16);
    plate.setArcHeight(16);
    plate.getStyleClass().add("brand-plate");
    getChildren().add(plate);
    double gap = 2;
    double width = (SIZE * 0.64 - gap * (SEGMENTS.length - 1)) / SEGMENTS.length;
    double height = SIZE * 0.40;
    double x = SIZE * 0.18;
    double y = (SIZE - height) / 2;
    for (String segment : SEGMENTS) {
      Rectangle bar = new Rectangle(x, y, width, height);
      bar.setArcWidth(width * 0.6);
      bar.setArcHeight(width * 0.6);
      bar.getStyleClass().addAll("brand-bar", segment);
      getChildren().add(bar);
      x += width + gap;
    }
    setMinSize(SIZE, SIZE);
    setPrefSize(SIZE, SIZE);
    setMaxSize(SIZE, SIZE);
    setMouseTransparent(true);
  }
}
