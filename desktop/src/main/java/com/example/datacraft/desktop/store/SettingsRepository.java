package com.example.datacraft.desktop.store;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Key-value settings: theme, window bounds, last view, refresh mode and the like. */
public final class SettingsRepository {

  private final LocalStore store;

  public SettingsRepository(LocalStore store) {
    this.store = store;
  }

  public CompletableFuture<Map<String, String>> all() {
    return store.submit(
        connection -> {
          Map<String, String> settings = new HashMap<>();
          try (PreparedStatement statement =
                  connection.prepareStatement("SELECT key, value FROM settings");
              ResultSet result = statement.executeQuery()) {
            while (result.next()) {
              settings.put(result.getString(1), result.getString(2));
            }
          }
          return settings;
        });
  }

  public CompletableFuture<Void> put(String key, String value) {
    return putAll(Map.of(key, value));
  }

  public CompletableFuture<Void> putAll(Map<String, String> values) {
    return store.transaction(
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO settings (key, value, updated_at) VALUES (?, ?, ?)"
                      + " ON CONFLICT(key) DO UPDATE SET value = excluded.value,"
                      + " updated_at = excluded.updated_at")) {
            long now = System.currentTimeMillis();
            for (Map.Entry<String, String> entry : values.entrySet()) {
              statement.setString(1, entry.getKey());
              statement.setString(2, entry.getValue());
              statement.setLong(3, now);
              statement.addBatch();
            }
            statement.executeBatch();
          }
          return null;
        });
  }
}
