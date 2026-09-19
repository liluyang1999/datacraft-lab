package com.example.datacraft.config;

import com.example.datacraft.common.DataCraftException;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;

public final class DataCraftConfig {

  private final Map<String, String> values;

  private DataCraftConfig(Map<String, String> values) {
    this.values = Collections.unmodifiableMap(new TreeMap<>(values));
  }

  public static DataCraftConfig empty() {
    return new DataCraftConfig(Map.of());
  }

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
            normalized.put(key.trim(), value.trim());
          }
        });
    return new DataCraftConfig(normalized);
  }

  public static DataCraftConfig load(Path file) {
    Properties properties = new Properties();
    try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      properties.load(reader);
    } catch (IOException exception) {
      throw new DataCraftException("Failed to load configuration file: " + file, exception);
    }

    TreeMap<String, String> values = new TreeMap<>();
    properties.forEach((key, value) -> values.put(String.valueOf(key), String.valueOf(value)));
    return fromMap(values);
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
    String value = getOrDefault(key, Integer.toString(defaultValue));
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException exception) {
      throw new DataCraftException("Configuration key must be an integer: " + key, exception);
    }
  }

  public boolean getBoolean(String key, boolean defaultValue) {
    String value = getOrDefault(key, Boolean.toString(defaultValue));
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
