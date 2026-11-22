package com.example.datacraft.desktop.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * The product name and version of this build. The version has one source, the root POM's {@code
 * revision}, which the build copies into {@code version.properties}; the app image and the
 * installer carry the same number.
 */
public final class BuildInfo {

  /** The product name everywhere a person sees it: window, menus, installer, uninstall list. */
  public static final String PRODUCT = "数艺工坊";

  static final String RESOURCE = "/com/example/datacraft/desktop/version.properties";

  private static final Pattern RELEASE = Pattern.compile("\\d{1,5}\\.\\d{1,5}\\.\\d{1,5}");

  private static final String VERSION = read(BuildInfo.class.getResourceAsStream(RESOURCE));

  private BuildInfo() {}

  /** {@code MAJOR.MINOR.PATCH}. */
  public static String version() {
    return VERSION;
  }

  /**
   * Reads the version from the filtered resource.
   *
   * @throws IllegalStateException when it is missing or was not filtered by the build
   */
  static String read(InputStream resource) {
    if (resource == null) {
      throw new IllegalStateException("Missing " + RESOURCE + " (build the desktop module).");
    }
    Properties properties = new Properties();
    try (InputStream input = resource) {
      properties.load(input);
    } catch (IOException exception) {
      throw new UncheckedIOException("Cannot read " + RESOURCE, exception);
    }
    String version = properties.getProperty("version", "").strip();
    if (!RELEASE.matcher(version).matches()) {
      throw new IllegalStateException(
          RESOURCE + " holds '" + version + "', not MAJOR.MINOR.PATCH.");
    }
    return version;
  }
}
