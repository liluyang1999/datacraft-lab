package com.example.datacraft.desktop.ui.component;

import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;

/**
 * Line icons on a 20 x 20 grid, drawn as strokes so that CSS colours them ({@code .icon}). Each is
 * a few path commands; there is no icon font or image to load.
 */
public final class Icons {

  private Icons() {}

  public static Node overview() {
    return icon("M10 3.2a6.8 6.8 0 1 0 0.01 0Z M10 7.6a2.4 2.4 0 1 0 0.01 0Z M10 3.2v2.2");
  }

  public static Node jobs() {
    return icon("M4.2 4.2h11.6v11.6H4.2Z M8.4 7.6l4.2 2.4-4.2 2.4Z");
  }

  public static Node pipelines() {
    return icon(
        "M2.8 4.6h5v3.4h-5Z M12.2 12h5v3.4h-5Z M7.8 6.3h2.4a1.6 1.6 0 0 1 1.6 1.6v4.1 M12.2 6.3h5");
  }

  public static Node activity() {
    return icon("M2.6 10h3.6l2-5.2 3.8 10.4 2-5.2h3.4");
  }

  public static Node settings() {
    return icon("M3.5 6h13 M3.5 14h13 M7.5 3.8v4.4 M12.5 11.8v4.4");
  }

  public static Node refresh() {
    return icon("M15.6 8.2A6 6 0 1 0 16 11.6 M16 4.2v4h-4");
  }

  public static Node sidebar() {
    return icon("M3.6 4.4h12.8v11.2H3.6Z M8 4.4v11.2");
  }

  public static Node plus() {
    return icon("M10 5v10 M5 10h10");
  }

  public static Node close() {
    return icon("M5.6 5.6l8.8 8.8 M14.4 5.6l-8.8 8.8");
  }

  public static Node log() {
    return icon("M5 3.4h6.8l3.2 3.2v10H5Z M7.6 9.4h4.8 M7.6 12.4h4.8");
  }

  public static Node trash() {
    return icon("M4.8 6.4h10.4 M8 6.4V4.4h4v2 M6.4 6.4l0.8 9.2h5.6l0.8-9.2");
  }

  public static Node play() {
    return icon("M7 5l8 5-8 5Z");
  }

  private static Node icon(String path) {
    SVGPath shape = new SVGPath();
    shape.setContent(path);
    shape.getStyleClass().add("icon");
    StackPane box = new StackPane(shape);
    box.setMinSize(20, 20);
    box.setPrefSize(20, 20);
    box.setMaxSize(20, 20);
    box.setMouseTransparent(true);
    return box;
  }
}
