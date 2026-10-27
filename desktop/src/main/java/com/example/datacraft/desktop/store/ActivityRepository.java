package com.example.datacraft.desktop.store;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The activity log: connection changes, runs, triggers and errors, at most {@link #KEEP} entries.
 */
public final class ActivityRepository {

  public static final int KEEP = 500;

  /** How an entry is shown. */
  public enum Level {
    INFO,
    SUCCESS,
    WARNING,
    ERROR
  }

  /** Where an entry comes from. */
  public enum Source {
    ENGINE,
    AIRFLOW,
    APP
  }

  /** One entry; {@code detail} holds technical text shown only when the entry is expanded. */
  public record Entry(Instant at, Level level, Source source, String text, String detail) {}

  private final LocalStore store;

  public ActivityRepository(LocalStore store) {
    this.store = store;
  }

  public CompletableFuture<Void> add(Entry entry) {
    return store.transaction(
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO activity (at, level, source, text, detail) VALUES (?, ?, ?, ?, ?)")) {
            statement.setLong(1, entry.at().toEpochMilli());
            statement.setString(2, entry.level().name());
            statement.setString(3, entry.source().name());
            statement.setString(4, entry.text());
            statement.setString(5, entry.detail());
            statement.executeUpdate();
          }
          try (PreparedStatement trim =
              connection.prepareStatement(
                  "DELETE FROM activity WHERE id NOT IN (SELECT id FROM activity ORDER BY at DESC, id DESC LIMIT ?)")) {
            trim.setInt(1, KEEP);
            trim.executeUpdate();
          }
          return null;
        });
  }

  /** The newest entries, newest first. */
  public CompletableFuture<List<Entry>> list(int limit) {
    return store.submit(
        connection -> {
          List<Entry> entries = new ArrayList<>();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT at, level, source, text, detail FROM activity ORDER BY at DESC, id DESC LIMIT ?")) {
            statement.setInt(1, limit);
            try (ResultSet result = statement.executeQuery()) {
              while (result.next()) {
                entries.add(
                    new Entry(
                        Instant.ofEpochMilli(result.getLong(1)),
                        Level.valueOf(result.getString(2)),
                        Source.valueOf(result.getString(3)),
                        result.getString(4),
                        result.getString(5)));
              }
            }
          }
          return entries;
        });
  }

  public CompletableFuture<Void> clear() {
    return store.transaction(
        connection -> {
          try (PreparedStatement statement = connection.prepareStatement("DELETE FROM activity")) {
            statement.executeUpdate();
          }
          return null;
        });
  }
}
