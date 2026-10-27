package com.example.datacraft.desktop.util;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** How numbers, sizes, durations and times read in the interface. */
public final class Formats {

  private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");
  private static final DateTimeFormatter DAY_CLOCK = DateTimeFormatter.ofPattern("MM-dd HH:mm");

  private Formats() {}

  /** {@code 23 ms}: a round-trip time. */
  public static String latency(Duration latency) {
    return Math.max(0, latency.toMillis()) + " ms";
  }

  /** {@code 0.4 s}, {@code 1.2 s}, {@code 48 s}, {@code 3 min 05 s}, {@code 2 h 07 min}. */
  public static String duration(Duration duration) {
    long millis = Math.max(0, duration.toMillis());
    if (millis < 10_000) {
      return String.format(Locale.ROOT, "%.1f s", millis / 1000.0);
    }
    long seconds = millis / 1000;
    if (seconds < 60) {
      return seconds + " s";
    }
    if (seconds < 3600) {
      return String.format(Locale.ROOT, "%d min %02d s", seconds / 60, seconds % 60);
    }
    return String.format(Locale.ROOT, "%d h %02d min", seconds / 3600, (seconds % 3600) / 60);
  }

  /** {@code 512 B}, {@code 1.5 KB}, {@code 12.3 MB}: binary multiples. */
  public static String bytes(long bytes) {
    if (bytes < 1024) {
      return bytes + " B";
    }
    String[] units = {"KB", "MB", "GB", "TB", "PB", "EB"};
    double value = bytes;
    int unit = -1;
    while (value >= 1024 && unit < units.length - 1) {
      value /= 1024;
      unit++;
    }
    return String.format(Locale.ROOT, value < 10 ? "%.1f %s" : "%.0f %s", value, units[unit]);
  }

  /** {@code 1,234,567}. */
  public static String thousands(long value) {
    return String.format(Locale.ROOT, "%,d", value);
  }

  /** {@code 10:42:13} in the local time zone. */
  public static String clock(Instant instant, ZoneId zone) {
    return CLOCK.format(instant.atZone(zone));
  }

  /** {@code 10:42:13} today, {@code 10-26 18:05} on another day. */
  public static String when(Instant instant, Instant now, ZoneId zone) {
    if (instant.atZone(zone).toLocalDate().equals(now.atZone(zone).toLocalDate())) {
      return clock(instant, zone);
    }
    return DAY_CLOCK.format(instant.atZone(zone));
  }

  /** {@code 刚刚}, {@code 5 秒前}, {@code 3 分钟前}, {@code 2 小时前}, {@code 4 天前}. */
  public static String ago(Instant then, Instant now) {
    long seconds = Math.max(0, Duration.between(then, now).toSeconds());
    if (seconds < 5) {
      return "刚刚";
    }
    if (seconds < 60) {
      return seconds + " 秒前";
    }
    if (seconds < 3600) {
      return seconds / 60 + " 分钟前";
    }
    if (seconds < 86_400) {
      return seconds / 3600 + " 小时前";
    }
    return seconds / 86_400 + " 天前";
  }

  /** A metric value by its key: durations, sizes and counts get their units and separators. */
  public static String metric(String key, String value) {
    try {
      return switch (key) {
        case "durationMillis" -> duration(Duration.ofMillis(Long.parseLong(value)));
        case "bytes", "maxBytes" -> bytes(Long.parseLong(value));
        case "rows", "columns", "expectedRows" -> thousands(Long.parseLong(value));
        default -> value;
      };
    } catch (NumberFormatException notANumber) {
      return value;
    }
  }
}
