package com.example.datacraft.desktop.ui;

import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.TranslateTransition;
import javafx.scene.Node;
import javafx.util.Duration;

/**
 * The few motions of the app, all short and all answering a user action or a state change. With
 * "减少动效" every one of them becomes an immediate change.
 */
public final class Motion {

  private static volatile boolean reduced;

  private Motion() {}

  public static boolean reduced() {
    return reduced;
  }

  public static void reduced(boolean value) {
    reduced = value;
  }

  /**
   * A view appearing: 120 ms fade. A whole view at partial opacity is drawn into an offscreen
   * texture on every frame; cached as a bitmap for the fade, it is drawn once and then only
   * blended.
   */
  public static void fadeIn(Node node) {
    if (reduced) {
      node.setOpacity(1);
      return;
    }
    boolean wasCached = node.isCache();
    node.setCache(true);
    node.setCacheHint(javafx.scene.CacheHint.SPEED);
    FadeTransition fade = new FadeTransition(Duration.millis(120), node);
    fade.setFromValue(0.0);
    fade.setToValue(1.0);
    fade.setOnFinished(
        event -> {
          node.setCache(wasCached);
          node.setCacheHint(javafx.scene.CacheHint.DEFAULT);
        });
    fade.play();
  }

  /** A drawer sliding in: 160 ms ease-out. Returns its animation, or null with reduced motion. */
  public static Animation slideIn(Node node, double distance) {
    if (reduced) {
      node.setTranslateX(0);
      return null;
    }
    TranslateTransition slide = new TranslateTransition(Duration.millis(160), node);
    slide.setFromX(distance);
    slide.setToX(0);
    slide.setInterpolator(Interpolator.EASE_OUT);
    slide.play();
    return slide;
  }

  /** A drawer leaving; returns its animation, or null when {@code done} runs immediately. */
  public static Animation slideOut(Node node, double distance, Runnable done) {
    if (reduced) {
      done.run();
      return null;
    }
    TranslateTransition slide = new TranslateTransition(Duration.millis(120), node);
    slide.setToX(distance);
    slide.setInterpolator(Interpolator.EASE_IN);
    slide.setOnFinished(event -> done.run());
    slide.play();
    return slide;
  }

  /** A toast appearing: 160 ms fade and a small rise. */
  public static void rise(Node node) {
    if (reduced) {
      return;
    }
    node.setOpacity(0);
    node.setTranslateY(8);
    FadeTransition fade = new FadeTransition(Duration.millis(160), node);
    fade.setToValue(1);
    TranslateTransition move = new TranslateTransition(Duration.millis(160), node);
    move.setToY(0);
    move.setInterpolator(Interpolator.EASE_OUT);
    fade.play();
    move.play();
  }

  /** A toast leaving: 160 ms fade; {@code done} removes it. */
  public static void fadeOut(Node node, Runnable done) {
    if (reduced) {
      done.run();
      return;
    }
    FadeTransition fade = new FadeTransition(Duration.millis(160), node);
    fade.setToValue(0);
    fade.setOnFinished(event -> done.run());
    fade.play();
  }
}
