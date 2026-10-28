package com.example.datacraft.desktop.ui;

import static com.example.datacraft.desktop.ui.FxTesting.onFx;
import static org.junit.jupiter.api.Assertions.assertEquals;

import javafx.scene.CacheHint;
import javafx.scene.layout.Region;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MotionTest {
  @BeforeAll
  static void toolkit() {
    FxTesting.start();
  }

  @Test
  void interruptingRepeatedFadesRestoresOpacityAndTheOriginalCachePolicy() {
    onFx(
        () -> {
          for (boolean cached : new boolean[] {false, true}) {
            Region view = new Region();
            view.setCache(cached);
            view.setCacheHint(CacheHint.QUALITY);
            Motion.reduced(false);
            Motion.fadeIn(view);
            Motion.fadeIn(view);
            Motion.reduced(true);
            Motion.fadeIn(view);
            assertEquals(1, view.getOpacity());
            assertEquals(cached, view.isCache());
            assertEquals(CacheHint.QUALITY, view.getCacheHint());
          }
        });
  }
}
