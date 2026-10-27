package com.example.datacraft.desktop.app;

import com.example.datacraft.desktop.store.SettingsRepository;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The user's settings, read once at startup (a handful of rows) and written through to the store in
 * the background. Reads never touch the disk, so the UI can ask at any time.
 */
public final class Preferences {

  /** The colour theme. */
  public enum Theme {
    LIGHT,
    DARK,
    SYSTEM
  }

  /** How often data is refreshed. */
  public enum Refresh {
    STANDARD(Duration.ofSeconds(5)),
    SAVER(Duration.ofSeconds(15));

    private final Duration health;

    Refresh(Duration health) {
      this.health = health;
    }

    /** The interval of the health checks; other polls scale with it. */
    public Duration health() {
      return health;
    }

    /** {@code standard} scaled by the mode: saver polls three times less often. */
    public Duration scale(Duration standard) {
      return this == STANDARD ? standard : standard.multipliedBy(3);
    }
  }

  /** The window's last bounds. */
  public record Bounds(double x, double y, double width, double height, boolean maximized) {}

  static final String THEME = "ui.theme";
  static final String REDUCED_MOTION = "ui.reducedMotion";
  static final String REFRESH = "ui.refresh";
  static final String LAST_VIEW = "ui.lastView";
  static final String SIDEBAR_COLLAPSED = "ui.sidebarCollapsed";
  static final String WINDOW = "ui.window";
  static final String ACTIVE_PROFILE = "connection.activeProfile";
  static final String RUN_TIMEOUT = "jobs.runTimeoutMinutes";

  public static final int DEFAULT_RUN_TIMEOUT_MINUTES = 30;

  private final SettingsRepository repository;
  private final Map<String, String> values;
  private final java.util.List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();

  private Preferences(SettingsRepository repository, Map<String, String> values) {
    this.repository = repository;
    this.values = new ConcurrentHashMap<>(values);
  }

  /** Loads every setting; called once before the window opens. */
  public static Preferences load(SettingsRepository repository) {
    return new Preferences(repository, repository.all().join());
  }

  public Theme theme() {
    return parse(Theme.class, values.get(THEME), Theme.SYSTEM);
  }

  public void theme(Theme theme) {
    put(THEME, theme.name());
  }

  public boolean reducedMotion() {
    return Boolean.parseBoolean(values.getOrDefault(REDUCED_MOTION, "false"));
  }

  public void reducedMotion(boolean reduced) {
    put(REDUCED_MOTION, Boolean.toString(reduced));
  }

  public Refresh refresh() {
    return parse(Refresh.class, values.get(REFRESH), Refresh.STANDARD);
  }

  public void refresh(Refresh refresh) {
    put(REFRESH, refresh.name());
  }

  public String lastView() {
    return values.getOrDefault(LAST_VIEW, "overview");
  }

  public void lastView(String view) {
    put(LAST_VIEW, view);
  }

  public boolean sidebarCollapsed() {
    return Boolean.parseBoolean(values.getOrDefault(SIDEBAR_COLLAPSED, "false"));
  }

  public void sidebarCollapsed(boolean collapsed) {
    put(SIDEBAR_COLLAPSED, Boolean.toString(collapsed));
  }

  public Optional<Bounds> window() {
    String value = values.get(WINDOW);
    if (value == null) {
      return Optional.empty();
    }
    String[] parts = value.split(",");
    if (parts.length != 5) {
      return Optional.empty();
    }
    try {
      return Optional.of(
          new Bounds(
              Double.parseDouble(parts[0]),
              Double.parseDouble(parts[1]),
              Double.parseDouble(parts[2]),
              Double.parseDouble(parts[3]),
              Boolean.parseBoolean(parts[4])));
    } catch (NumberFormatException malformed) {
      return Optional.empty();
    }
  }

  public void window(Bounds bounds) {
    put(
        WINDOW,
        String.format(
            Locale.ROOT,
            "%.0f,%.0f,%.0f,%.0f,%b",
            bounds.x(),
            bounds.y(),
            bounds.width(),
            bounds.height(),
            bounds.maximized()));
  }

  public Optional<Long> activeProfile() {
    try {
      return Optional.ofNullable(values.get(ACTIVE_PROFILE)).map(Long::parseLong);
    } catch (NumberFormatException malformed) {
      return Optional.empty();
    }
  }

  public void activeProfile(long id) {
    put(ACTIVE_PROFILE, Long.toString(id));
  }

  public int runTimeoutMinutes() {
    try {
      int minutes =
          Integer.parseInt(
              values.getOrDefault(RUN_TIMEOUT, Integer.toString(DEFAULT_RUN_TIMEOUT_MINUTES)));
      return Math.clamp(minutes, 1, 24 * 60);
    } catch (NumberFormatException malformed) {
      return DEFAULT_RUN_TIMEOUT_MINUTES;
    }
  }

  public void runTimeoutMinutes(int minutes) {
    put(RUN_TIMEOUT, Integer.toString(Math.clamp(minutes, 1, 24 * 60)));
  }

  /** Called with the key of every changed setting, on the thread that changed it. */
  public void subscribe(Consumer<String> listener) {
    listeners.add(listener);
  }

  private void put(String key, String value) {
    if (value.equals(values.put(key, value))) {
      return;
    }
    repository.put(key, value);
    listeners.forEach(listener -> listener.accept(key));
  }

  private static <E extends Enum<E>> E parse(Class<E> type, String value, E fallback) {
    if (value == null) {
      return fallback;
    }
    try {
      return Enum.valueOf(type, value);
    } catch (IllegalArgumentException unknown) {
      return fallback;
    }
  }
}
