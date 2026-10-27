package com.example.datacraft.desktop.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Where the desktop app keeps its own files: {@code ~/.datacraft} unless {@code
 * DATACRAFT_DESKTOP_HOME} (environment) or {@code datacraft.desktop.home} (system property) names
 * another directory. Only settings and caches live here; the app stores no business data.
 */
public record AppPaths(Path home) {

  public static final String HOME_ENV = "DATACRAFT_DESKTOP_HOME";
  public static final String HOME_PROPERTY = "datacraft.desktop.home";

  /** The directory for this process: the property, then the environment, then the default. */
  public static AppPaths resolve() {
    return resolve(System.getProperties().getProperty(HOME_PROPERTY), System.getenv());
  }

  static AppPaths resolve(String property, Map<String, String> environment) {
    String configured =
        property != null && !property.isBlank() ? property : environment.get(HOME_ENV);
    Path home =
        configured != null && !configured.isBlank()
            ? Path.of(configured.strip())
            : Path.of(System.getProperty("user.home"), ".datacraft");
    return new AppPaths(home.toAbsolutePath().normalize());
  }

  /** Creates the directory tree when it does not exist yet. */
  public AppPaths ensureCreated() {
    try {
      Files.createDirectories(logs());
      Files.createDirectories(pipes());
      return this;
    } catch (IOException exception) {
      throw new UncheckedIOException("Cannot create " + home, exception);
    }
  }

  public Path database() {
    return home.resolve("datacraft.db");
  }

  public Path vaultKey() {
    return home.resolve("vault.key");
  }

  public Path logs() {
    return home.resolve("logs");
  }

  /**
   * Where the JDK puts the AF_UNIX sockets behind its NIO pipes on Windows (every HTTP client needs
   * one). The user's TEMP folder cannot always host them, so the app uses its own folder.
   */
  public Path pipes() {
    return home.resolve("tmp");
  }
}
