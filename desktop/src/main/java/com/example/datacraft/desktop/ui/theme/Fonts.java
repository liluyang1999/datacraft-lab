package com.example.datacraft.desktop.ui.theme;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Set;
import javafx.scene.text.Font;

/**
 * The type of the app: the system's CJK UI font for all text, and the bundled Barlow (SIL OFL) for
 * Latin-only figures and the wordmark. Fonts are loaded once, before the first scene.
 */
public final class Fonts {

  /** System CJK UI fonts by platform, first installed wins. */
  static final List<String> UI_FAMILIES =
      List.of(
          "Microsoft YaHei UI",
          "Microsoft YaHei",
          "PingFang SC",
          "Noto Sans CJK SC",
          "Noto Sans SC",
          "Source Han Sans SC",
          "WenQuanYi Micro Hei");

  private static final String[] BUNDLED = {"Barlow-Medium.ttf", "Barlow-SemiBold.ttf"};

  private static String uiFamily;

  private Fonts() {}

  /** Loads Barlow and picks the UI family; returns the family for the root style. */
  public static synchronized String load() {
    if (uiFamily != null) {
      return uiFamily;
    }
    for (String file : BUNDLED) {
      try (InputStream in =
          Fonts.class.getResourceAsStream("/com/example/datacraft/desktop/fonts/" + file)) {
        if (in == null || Font.loadFont(in, 13) == null) {
          throw new IllegalStateException("The bundled font " + file + " cannot be loaded");
        }
      } catch (IOException failure) {
        throw new UncheckedIOException(failure);
      }
    }
    uiFamily = pick(Set.copyOf(Font.getFamilies()));
    return uiFamily;
  }

  static String pick(Set<String> installed) {
    return UI_FAMILIES.stream().filter(installed::contains).findFirst().orElse("System");
  }
}
