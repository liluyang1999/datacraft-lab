package com.example.datacraft.desktop.store;

import com.example.datacraft.desktop.util.Maps;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Named parameter sets for a job, saved by the user and applied in the parameter editor. */
public final class PresetRepository {

  /** A saved parameter set. */
  public record Preset(String jobName, String name, Map<String, String> params) {
    public Preset {
      params = Maps.frozen(params);
    }
  }

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final TypeReference<LinkedHashMap<String, String>> MAP = new TypeReference<>() {};

  private final LocalStore store;

  public PresetRepository(LocalStore store) {
    this.store = store;
  }

  public CompletableFuture<List<Preset>> list(String jobName) {
    return store.submit(
        connection -> {
          List<Preset> presets = new ArrayList<>();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT name, params FROM job_presets WHERE job_name = ? ORDER BY name")) {
            statement.setString(1, jobName);
            try (ResultSet result = statement.executeQuery()) {
              while (result.next()) {
                presets.add(new Preset(jobName, result.getString(1), parse(result.getString(2))));
              }
            }
          }
          return presets;
        });
  }

  public CompletableFuture<Void> save(Preset preset) {
    return store.transaction(
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO job_presets (job_name, name, params, updated_at) VALUES (?, ?, ?, ?)"
                      + " ON CONFLICT(job_name, name) DO UPDATE SET params = excluded.params,"
                      + " updated_at = excluded.updated_at")) {
            statement.setString(1, preset.jobName());
            statement.setString(2, preset.name());
            statement.setString(3, write(new LinkedHashMap<>(preset.params())));
            statement.setLong(4, System.currentTimeMillis());
            statement.executeUpdate();
          }
          return null;
        });
  }

  public CompletableFuture<Void> delete(String jobName, String name) {
    return store.transaction(
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "DELETE FROM job_presets WHERE job_name = ? AND name = ?")) {
            statement.setString(1, jobName);
            statement.setString(2, name);
            statement.executeUpdate();
          }
          return null;
        });
  }

  static Map<String, String> parse(String json) throws SQLException {
    try {
      return JSON.readValue(json, MAP);
    } catch (JsonProcessingException exception) {
      throw new SQLException("A stored parameter set is not valid JSON", exception);
    }
  }

  static String write(Map<String, String> params) throws SQLException {
    try {
      return JSON.writeValueAsString(params);
    } catch (JsonProcessingException exception) {
      throw new SQLException("Cannot store the parameter set", exception);
    }
  }
}
