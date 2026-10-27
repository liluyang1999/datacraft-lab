package com.example.datacraft.desktop.ui;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Labeled;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javax.imageio.ImageIO;

/** Starting the toolkit once, running code on the FX thread, waiting for the UI, snapshots. */
final class FxTesting {

  private static boolean started;
  private static final List<LogRecord> WARNINGS = new ArrayList<>();

  private FxTesting() {}

  /** Starts JavaFX once per JVM and starts collecting its warnings (CSS, layout, threading). */
  static synchronized void start() {
    if (started) {
      return;
    }
    Logger.getLogger("")
        .addHandler(
            new Handler() {
              @Override
              public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()
                    && record.getLoggerName() != null
                    && record.getLoggerName().startsWith("javafx")) {
                  synchronized (WARNINGS) {
                    WARNINGS.add(record);
                  }
                }
              }

              @Override
              public void flush() {}

              @Override
              public void close() {}
            });
    CompletableFuture<Void> ready = new CompletableFuture<>();
    Platform.startup(() -> ready.complete(null));
    Platform.setImplicitExit(false);
    ready.join();
    started = true;
  }

  /** JavaFX's own warnings since the toolkit started, as "logger: message" lines. */
  static List<String> javafxWarnings() {
    synchronized (WARNINGS) {
      return WARNINGS.stream()
          .map(record -> record.getLoggerName() + ": " + record.getMessage())
          .toList();
    }
  }

  static <T> T onFx(Callable<T> work) {
    CompletableFuture<T> result = new CompletableFuture<>();
    Platform.runLater(
        () -> {
          try {
            result.complete(work.call());
          } catch (Throwable failure) {
            result.completeExceptionally(failure);
          }
        });
    try {
      return result.get(30, TimeUnit.SECONDS);
    } catch (Exception failure) {
      throw new AssertionError("FX work failed", failure);
    }
  }

  static void onFx(Runnable work) {
    onFx(
        () -> {
          work.run();
          return null;
        });
  }

  /** Waits until the condition, checked on the FX thread, holds; fails after ten seconds. */
  static void waitUntil(String what, Callable<Boolean> condition) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      if (Boolean.TRUE.equals(onFx(condition))) {
        return;
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new AssertionError(interrupted);
      }
    }
    throw new AssertionError("Timed out waiting until " + what);
  }

  /** Lets a few pulses pass so that layout and CSS settle. */
  static void settle() {
    for (int pulse = 0; pulse < 3; pulse++) {
      onFx(() -> {});
    }
  }

  /** Whether a visible labeled node under {@code root} shows exactly this text. */
  static boolean shows(Parent root, String text) {
    return find(root, Labeled.class).stream()
        .anyMatch(node -> text.equals(node.getText()) && visible(node));
  }

  /** Whether a visible labeled node under {@code root} shows text starting with the prefix. */
  static boolean showsPrefix(Parent root, String prefix) {
    return find(root, Labeled.class).stream()
        .anyMatch(
            node -> node.getText() != null && node.getText().startsWith(prefix) && visible(node));
  }

  static boolean visible(Node node) {
    for (Node current = node; current != null; current = current.getParent()) {
      if (!current.isVisible()) {
        return false;
      }
    }
    return node.getScene() != null;
  }

  static <T> List<T> find(Parent root, Class<T> type) {
    List<T> found = new ArrayList<>();
    collect(root, type, found);
    return found;
  }

  private static <T> void collect(Node node, Class<T> type, List<T> found) {
    if (type.isInstance(node)) {
      found.add(type.cast(node));
    }
    if (node instanceof Parent parent) {
      for (Node child : parent.getChildrenUnmodifiable()) {
        collect(child, type, found);
      }
    }
  }

  /** Saves the scene as a PNG under target/ui-snapshots for review. */
  static Path snapshot(Scene scene, String name) {
    WritableImage image = onFx(() -> scene.snapshot(null));
    int width = (int) image.getWidth();
    int height = (int) image.getHeight();
    int[] pixels = new int[width * height];
    image
        .getPixelReader()
        .getPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), pixels, 0, width);
    BufferedImage buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    buffered.setRGB(0, 0, width, height, pixels, 0, width);
    Path directory = Path.of(System.getProperty("basedir", "."), "target", "ui-snapshots");
    try {
      Files.createDirectories(directory);
      Path file = directory.resolve(name + ".png");
      ImageIO.write(buffered, "png", file.toFile());
      return file;
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }
}
