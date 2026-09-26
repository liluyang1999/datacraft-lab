package com.example.datacraft.config;

import com.example.datacraft.common.DataCraftException;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Immutable string configuration, loaded from a file or built from a map.
 *
 * <p>Files use {@link java.util.Properties} syntax and are read as UTF-8; one leading byte order
 * mark is ignored. Backslash is an escape character and a trailing backslash continues the line, so
 * write Windows paths with {@code /} or doubled backslashes ({@code D:/out/x.parquet} or {@code
 * D:\\out\\x.parquet}).
 *
 * <p>Keys are trimmed and must not be blank. Values are kept verbatim, exactly as {@code
 * Properties} unescapes them, so an escaped tab or space survives; the typed readers {@link
 * #getInt} and {@link #getBoolean} ignore surrounding whitespace.
 */
public final class DataCraftConfig {

  private static final int BYTE_ORDER_MARK = 0xFEFF;

  private final Map<String, String> values;

  private DataCraftConfig(Map<String, String> values) {
    this.values = Collections.unmodifiableMap(new TreeMap<>(values));
  }

  public static DataCraftConfig empty() {
    return new DataCraftConfig(Map.of());
  }

  /**
   * Builds a configuration from programmatic entries. Keys are trimmed, values are stored verbatim,
   * and entries with a null value are skipped.
   *
   * @throws IllegalArgumentException if a key is null or blank
   */
  public static DataCraftConfig fromMap(Map<String, String> values) {
    if (values == null) {
      return empty();
    }

    TreeMap<String, String> normalized = new TreeMap<>();
    values.forEach(
        (key, value) -> {
          if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Configuration key must not be blank.");
          }
          if (value != null) {
            normalized.put(key.trim(), value);
          }
        });
    return new DataCraftConfig(normalized);
  }

  /**
   * Loads a UTF-8 {@code .properties} file, as described in the class documentation.
   *
   * @throws DataCraftException if the file cannot be read, or if it holds a malformed escape or a
   *     blank key; the message names the file but never a property value
   */
  public static DataCraftConfig load(Path file) {
    Properties properties = new Properties();
    try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      reader.mark(1);
      if (reader.read() != BYTE_ORDER_MARK) {
        reader.reset();
      }
      properties.load(reader);
    } catch (IOException exception) {
      throw new DataCraftException("Failed to load configuration file: " + file, exception);
    } catch (IllegalArgumentException exception) {
      throw invalidFile(file, exception);
    }

    TreeMap<String, String> values = new TreeMap<>();
    properties.forEach((key, value) -> values.put(String.valueOf(key), String.valueOf(value)));
    try {
      return fromMap(values);
    } catch (IllegalArgumentException exception) {
      throw invalidFile(file, exception);
    }
  }

  private static DataCraftException invalidFile(Path file, IllegalArgumentException cause) {
    return new DataCraftException(
        "Invalid configuration file: " + file + ": " + cause.getMessage(), cause);
  }

  public Optional<String> get(String key) {
    return Optional.ofNullable(values.get(key));
  }

  public String getOrDefault(String key, String defaultValue) {
    return get(key).orElse(defaultValue);
  }

  public String require(String key) {
    return get(key)
        .orElseThrow(() -> new DataCraftException("Missing required configuration key: " + key));
  }

  public int getInt(String key, int defaultValue) {
    String value = getOrDefault(key, Integer.toString(defaultValue)).trim();
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException exception) {
      throw new DataCraftException("Configuration key must be an integer: " + key, exception);
    }
  }

  public boolean getBoolean(String key, boolean defaultValue) {
    String value = getOrDefault(key, Boolean.toString(defaultValue)).trim();
    if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
      throw new DataCraftException("Configuration key must be true or false: " + key);
    }
    return Boolean.parseBoolean(value);
  }

  public DataCraftConfig withPrefix(String prefix) {
    TreeMap<String, String> prefixed = new TreeMap<>();
    values.forEach(
        (key, value) -> {
          if (key.startsWith(prefix)) {
            prefixed.put(key.substring(prefix.length()), value);
          }
        });
    return new DataCraftConfig(prefixed);
  }

  public Map<String, String> asMap() {
    return values;
  }
}
