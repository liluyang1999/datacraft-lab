package com.example.datacraft.desktop.ui.component;

import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;

/**
 * Line icons on a 20 x 20 grid, drawn as strokes so that CSS colours them ({@code .icon}). Each is
 * a few path commands; there is no icon font or image to load. {@link #of(Glyph, double)} scales a
 * glyph for headers and empty states.
 */
public final class Icons {

  /** Every glyph the app draws. */
  public enum Glyph {
    OVERVIEW("M10 3.2a6.8 6.8 0 1 0 0.01 0Z M10 7.6a2.4 2.4 0 1 0 0.01 0Z M10 3.2v2.2"),
    JOBS("M4.2 4.2h11.6v11.6H4.2Z M8.4 7.6l4.2 2.4-4.2 2.4Z"),
    PIPELINES(
        "M2.8 4.6h5v3.4h-5Z M12.2 12h5v3.4h-5Z M7.8 6.3h2.4a1.6 1.6 0 0 1 1.6 1.6v4.1 M12.2 6.3h5"),
    ACTIVITY("M2.6 10h3.6l2-5.2 3.8 10.4 2-5.2h3.4"),
    SETTINGS("M3.5 6h13 M3.5 14h13 M7.5 3.8v4.4 M12.5 11.8v4.4"),
    REFRESH("M15.6 8.2A6 6 0 1 0 16 11.6 M16 4.2v4h-4"),
    SIDEBAR("M3.6 4.4h12.8v11.2H3.6Z M8 4.4v11.2"),
    PLUS("M10 5v10 M5 10h10"),
    CLOSE("M5.6 5.6l8.8 8.8 M14.4 5.6l-8.8 8.8"),
    LOG("M5 3.4h6.8l3.2 3.2v10H5Z M7.6 9.4h4.8 M7.6 12.4h4.8"),
    TRASH("M4.8 6.4h10.4 M8 6.4V4.4h4v2 M6.4 6.4l0.8 9.2h5.6l0.8-9.2"),
    PLAY("M7 5l8 5-8 5Z"),
    STOP("M6.6 6.6h6.8v6.8H6.6Z"),
    PAUSE("M7.6 5.2v9.6 M12.4 5.2v9.6"),
    CHECK("M5 10.4l3.2 3.2L15 6.8"),
    CROSS("M6.2 6.2l7.6 7.6 M13.8 6.2l-7.6 7.6"),
    ENGINE("M10 2.8l6.2 3.6v7.2L10 17.2l-6.2-3.6V6.4Z M3.8 6.4L10 10l6.2-3.6 M10 10v7.2"),
    AIRFLOW(
        "M10 10C10 6.6 11.4 3.4 14.6 3.4 14.6 6.6 12.8 9 10 10Z"
            + " M10 10C13.4 10 16.6 11.4 16.6 14.6 13.4 14.6 11 12.8 10 10Z"
            + " M10 10C10 13.4 8.6 16.6 5.4 16.6 5.4 13.4 7.2 11 10 10Z"
            + " M10 10C6.6 10 3.4 8.6 3.4 5.4 6.6 5.4 9 7.2 10 10Z"),
    APP("M3.4 4.6h13.2v10.8H3.4Z M3.4 7.6h13.2"),
    ALL("M4 4h5v5H4Z M11 4h5v5h-5Z M4 11h5v5H4Z M11 11h5v5h-5Z"),
    CLOCK("M10 3.4a6.6 6.6 0 1 0 0.01 0Z M10 6.4v3.8l2.6 1.6"),
    MANUAL("M5.4 3.6l9 5.8-3.8 1 2.4 4.6-1.8 0.9-2.4-4.6-2.8 2.7Z"),
    JVM(
        "M4.6 8h8.8v4.2a3.4 3.4 0 0 1-3.4 3.4H8a3.4 3.4 0 0 1-3.4-3.4Z"
            + " M13.4 9h1.2a1.8 1.8 0 0 1 0 3.6h-1.2 M7.4 3.6v2.2 M10.4 3.6v2.2"),
    SPARK(
        "M10 2.8v4.4 M10 12.8v4.4 M2.8 10h4.4 M12.8 10h4.4"
            + " M4.9 4.9l3.1 3.1 M12 12l3.1 3.1 M4.9 15.1L8 12 M12 8l3.1-3.1"),
    BUILT_IN("M4.6 4.6h10.8v10.8H4.6Z M10 7.8a2.2 2.2 0 1 0 0.01 0Z"),
    SUN(
        "M10 6.8a3.2 3.2 0 1 0 0.01 0Z M10 2.6v1.6 M10 15.8v1.6 M2.6 10h1.6 M15.8 10h1.6"
            + " M4.8 4.8l1.1 1.1 M14.1 14.1l1.1 1.1 M4.8 15.2l1.1-1.1 M14.1 5.9l1.1-1.1"),
    MOON("M15.4 12.4A6 6 0 0 1 7.6 4.6a6 6 0 1 0 7.8 7.8Z"),
    MONITOR("M3.4 4.6h13.2v8.6H3.4Z M7.4 16.4h5.2 M10 13.2v3.2"),
    FOLDER("M3 6h5l1.6 1.8H17v7.6H3Z"),
    COPY("M7 7h8.4v8.4H7Z M4.6 12.6V4.6h8"),
    INFO("M10 3.4a6.6 6.6 0 1 0 0.01 0Z M10 9.2v4.4 M10 6.6v0.2"),
    DATABASE(
        "M4.4 5.6c0-1.4 2.5-2.4 5.6-2.4s5.6 1 5.6 2.4v8.8c0 1.4-2.5 2.4-5.6 2.4s-5.6-1-5.6-2.4Z"
            + " M4.4 5.6c0 1.4 2.5 2.4 5.6 2.4s5.6-1 5.6-2.4"
            + " M4.4 10c0 1.4 2.5 2.4 5.6 2.4s5.6-1 5.6-2.4"),
    BOLT("M11 2.8L5.2 11h4.2l-1 6.2L14.8 9h-4.2Z"),
    LEAF("M4.4 15.6C4.4 8.6 9 4.4 15.6 4.4c0 7-4.4 11.2-11.2 11.2Z M4.4 15.6l6-6"),
    TIMER("M10 5a5.8 5.8 0 1 0 0.01 0Z M10 8v3.2 M8.4 2.6h3.2"),
    MOTION("M2.8 7h5.4 M2.8 13h5.4 M4.4 10h3.8 M14 10a3.4 3.4 0 1 0 0.01 0Z"),
    LINK(
        "M8.6 11.4a3 3 0 0 0 4.2 0l2.4-2.4a3 3 0 0 0-4.2-4.2l-0.8 0.8"
            + " M11.4 8.6a3 3 0 0 0-4.2 0L4.8 11a3 3 0 0 0 4.2 4.2l0.8-0.8"),
    KEY("M7.4 12.6a3.4 3.4 0 1 1 2.4-1L16.4 18 M13.6 15.2l1.6-1.6"),
    USER("M10 4a3 3 0 1 0 0.01 0Z M4.4 16.4c0.8-3 3-4.6 5.6-4.6s4.8 1.6 5.6 4.6");

    final String path;

    Glyph(String path) {
      this.path = path;
    }
  }

  private Icons() {}

  /** A glyph at {@code size} pixels; the stroke scales with it. */
  public static Node of(Glyph glyph, double size) {
    SVGPath shape = new SVGPath();
    shape.setContent(glyph.path);
    shape.getStyleClass().add("icon");
    if (size != 20) {
      shape.setScaleX(size / 20);
      shape.setScaleY(size / 20);
    }
    StackPane box = new StackPane(shape);
    box.getStyleClass().add("glyph");
    box.setMinSize(size, size);
    box.setPrefSize(size, size);
    box.setMaxSize(size, size);
    box.setMouseTransparent(true);
    return box;
  }

  public static Node of(Glyph glyph) {
    return of(glyph, 20);
  }

  public static Node overview() {
    return of(Glyph.OVERVIEW);
  }

  public static Node jobs() {
    return of(Glyph.JOBS);
  }

  public static Node pipelines() {
    return of(Glyph.PIPELINES);
  }

  public static Node activity() {
    return of(Glyph.ACTIVITY);
  }

  public static Node settings() {
    return of(Glyph.SETTINGS);
  }

  public static Node refresh() {
    return of(Glyph.REFRESH);
  }

  public static Node sidebar() {
    return of(Glyph.SIDEBAR);
  }

  public static Node plus() {
    return of(Glyph.PLUS);
  }

  public static Node close() {
    return of(Glyph.CLOSE);
  }

  public static Node log() {
    return of(Glyph.LOG);
  }

  public static Node trash() {
    return of(Glyph.TRASH);
  }

  public static Node play() {
    return of(Glyph.PLAY);
  }
}
