package com.example.datacraft.common;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;
import java.util.regex.Pattern;

/** The product's name and version, as the build recorded them. */
public final class AppInfo {

  /** Prefix of the default Spark application name, {@code datacraft-lab-<job>}. */
  public static final String DEFAULT_APP_NAME = "datacraft-lab";

  /** Where the build writes the product version (the root POM's {@code revision}). */
  static final String VERSION_RESOURCE = "/com/example/datacraft/common/version.properties";

  private static final Pattern RELEASE = Pattern.compile("\\d{1,5}\\.\\d{1,5}\\.\\d{1,5}");

  private static final String VERSION = read(AppInfo.class.getResourceAsStream(VERSION_RESOURCE));

  private AppInfo() {}

  /**
   * The product version, {@code MAJOR.MINOR.PATCH}. It has exactly one source, the root POM's
   * {@code revision} property, which resource filtering copies into this module at build time.
   */
  public static String version() {
    return VERSION;
  }

  /**
   * Reads the version from the filtered resource.
   *
   * @throws IllegalStateException when the resource is missing or was not filtered by the build
   */
  static String read(InputStream resource) {
    if (resource == null) {
      throw new IllegalStateException("Missing " + VERSION_RESOURCE + " (build the module).");
    }
    Properties properties = new Properties();
    try (InputStream input = resource) {
      properties.load(input);
    } catch (IOException exception) {
      throw new UncheckedIOException("Cannot read " + VERSION_RESOURCE, exception);
    }
    String version = properties.getProperty("version", "").strip();
    if (!RELEASE.matcher(version).matches()) {
      throw new IllegalStateException(
          VERSION_RESOURCE + " holds '" + version + "', not a MAJOR.MINOR.PATCH release version.");
    }
    return version;
  }
}
