package com.example.datacraft.jobs;

import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Parameter parsing shared by the JVM jobs, with the same rules as the Spark jobs: required values
 * are trimmed, booleans are {@code true}/{@code false} in any case, and malformed values fail the
 * job instead of silently falling back to a default.
 */
final class JobParameters {

  private JobParameters() {}

  /**
   * Rejects a key that differs from a known key only by case, so a misspelled quality gate such as
   * {@code expectedrows} cannot be ignored silently. Other unknown keys (for example {@code
   * spark.master}, which the CLI always adds) are allowed.
   */
  static void rejectCaseVariants(Map<String, String> parameters, Set<String> knownKeys) {
    for (String key : parameters.keySet()) {
      if (knownKeys.contains(key)) {
        continue;
      }
      for (String known : knownKeys) {
        if (known.equalsIgnoreCase(key)) {
          throw new IllegalArgumentException(
              "Unknown parameter " + key + "; did you mean " + known + "?");
        }
      }
    }
  }

  static String required(Map<String, String> parameters, String key) {
    String value = parameters.get(key);
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException("Missing required parameter: " + key);
    }
    return value.trim();
  }

  static boolean booleanValue(Map<String, String> parameters, String key, boolean defaultValue) {
    String value = parameters.get(key);
    if (value == null) {
      return defaultValue;
    }
    return switch (value.trim().toLowerCase(Locale.ROOT)) {
      case "true" -> true;
      case "false" -> false;
      default -> throw new IllegalArgumentException(key + " must be true or false");
    };
  }

  /**
   * Parses an optional 64-bit integer in {@code [min, max]}. A present but blank or malformed value
   * fails; the message names the key and the range but never echoes the raw value.
   */
  static OptionalLong longInRange(Map<String, String> parameters, String key, long min, long max) {
    String value = parameters.get(key);
    if (value == null) {
      return OptionalLong.empty();
    }
    String message =
        max == Long.MAX_VALUE
            ? key + " must be a 64-bit integer >= " + min
            : key + " must be an integer from " + min + " to " + max;
    long parsed;
    try {
      parsed = Long.parseLong(value.trim());
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(message, exception);
    }
    if (parsed < min || parsed > max) {
      throw new IllegalArgumentException(message);
    }
    return OptionalLong.of(parsed);
  }
}
