package com.example.datacraft.desktop.ui;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;

class ThemeContrastTest {
  @Test
  void lightTextTokensRemainReadableOnTheirSurfaces() throws IOException {
    check("light");
  }

  @Test
  void darkTextTokensRemainReadableOnTheirSurfaces() throws IOException {
    check("dark");
  }

  private static void check(String theme) throws IOException {
    Map<String, Color> colors = new HashMap<>();
    try (var input =
        ThemeContrastTest.class.getResourceAsStream(
            "/com/example/datacraft/desktop/theme/" + theme + ".css")) {
      assertNotNull(input);
      var tokens =
          Pattern.compile("(-[\\w-]+):\\s*(#[0-9a-fA-F]{6});")
              .matcher(new String(input.readAllBytes(), StandardCharsets.UTF_8));
      while (tokens.find()) {
        colors.put(tokens.group(1), Color.web(tokens.group(2)));
      }
    }
    contrast(colors, "-dc-prompt", "-dc-input");
    contrast(colors, "-fx-prompt-text-fill", "-fx-control-inner-background");
    for (String background : new String[] {"-dc-bg-top", "-dc-bg-bottom", "-dc-surface"}) {
      contrast(colors, "-dc-text", background);
      contrast(colors, "-dc-text-secondary", background);
    }
    for (String tone : new String[] {"success", "failed", "warning"}) {
      contrast(colors, "-dc-" + tone + "-text", "-dc-" + tone + "-wash");
    }
    contrast(colors, "-dc-on-accent", "-dc-accent-fill-top");
    contrast(colors, "-dc-on-accent", "-dc-accent-fill-bottom");
    contrast(colors, "-dc-side-text", "-dc-side-top");
    contrast(colors, "-dc-side-text", "-dc-side-bottom");
  }

  private static void contrast(Map<String, Color> colors, String foreground, String background) {
    Color front = colors.get(foreground);
    Color back = colors.get(background);
    assertNotNull(front, foreground);
    assertNotNull(back, background);
    double a = luminance(front);
    double b = luminance(back);
    double ratio = (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    assertTrue(ratio >= 4.5, foreground + " on " + background + " has contrast " + ratio);
  }

  private static double luminance(Color color) {
    return 0.2126 * linear(color.getRed())
        + 0.7152 * linear(color.getGreen())
        + 0.0722 * linear(color.getBlue());
  }

  private static double linear(double value) {
    return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
  }
}
