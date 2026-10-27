package com.example.datacraft.desktop.ui.shell;

import java.util.ArrayList;
import java.util.List;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;

/**
 * The window icon, drawn at start instead of shipped as images: a Prussian-blue plate with a run
 * ribbon of five segments, the app's one bold element.
 */
final class AppIcon {

  private static final int[] SIZES = {16, 24, 32, 48, 64, 128, 256};
  private static final Color[] SEGMENTS = {
    Color.web("#4CC2B2"),
    Color.web("#4CC2B2"),
    Color.web("#5DB2E6"),
    Color.web("#4CC2B2"),
    Color.web("#E6EEF5")
  };

  private AppIcon() {}

  static List<Image> images() {
    List<Image> images = new ArrayList<>();
    for (int size : SIZES) {
      images.add(draw(size));
    }
    return images;
  }

  static Image draw(int size) {
    Canvas canvas = new Canvas(size, size);
    GraphicsContext g = canvas.getGraphicsContext2D();
    double radius = size * 0.22;
    g.setFill(
        new LinearGradient(
            0,
            0,
            0,
            1,
            true,
            CycleMethod.NO_CYCLE,
            new Stop(0, Color.web("#12304A")),
            new Stop(1, Color.web("#0E4D5E"))));
    g.fillRoundRect(0, 0, size, size, radius * 2, radius * 2);
    double gap = size * 0.05;
    double width = (size * 0.64 - gap * (SEGMENTS.length - 1)) / SEGMENTS.length;
    double height = size * 0.40;
    double x = size * 0.18;
    double y = (size - height) / 2;
    for (Color segment : SEGMENTS) {
      g.setFill(segment);
      g.fillRoundRect(x, y, width, height, Math.max(1, width * 0.3), Math.max(1, width * 0.3));
      x += width + gap;
    }
    SnapshotParameters parameters = new SnapshotParameters();
    parameters.setFill(Color.TRANSPARENT);
    return canvas.snapshot(parameters, null);
  }
}
