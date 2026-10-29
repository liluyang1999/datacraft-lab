package com.example.datacraft.desktop.store;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The app's SQLite database. One connection, owned by one thread: every read and write is queued on
 * that thread, so there is no lock contention and no statement ever runs on the JavaFX thread. The
 * schema version is {@code PRAGMA user_version}; {@link #open} migrates it step by step.
 */
public final class LocalStore implements AutoCloseable {

  /** A unit of work on the store's connection. */
  @FunctionalInterface
  public interface Work<T> {
    T run(Connection connection) throws SQLException;
  }

  /** Schema steps; step {@code n} brings the database from version {@code n} to {@code n + 1}. */
  static final List<List<String>> MIGRATIONS =
      List.of(
          List.of(
              "CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL,"
                  + " updated_at INTEGER NOT NULL)",
              "CREATE TABLE profiles (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                  + " name TEXT NOT NULL UNIQUE, engine_url TEXT NOT NULL, engine_token BLOB,"
                  + " airflow_url TEXT NOT NULL, airflow_user TEXT NOT NULL, airflow_password BLOB,"
                  + " remember_secrets INTEGER NOT NULL, created_at INTEGER NOT NULL,"
                  + " updated_at INTEGER NOT NULL)",
              "CREATE TABLE job_presets (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                  + " job_name TEXT NOT NULL, name TEXT NOT NULL, params TEXT NOT NULL,"
                  + " updated_at INTEGER NOT NULL, UNIQUE (job_name, name))",
              "CREATE TABLE recent_runs (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                  + " profile_id INTEGER NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,"
                  + " job_name TEXT NOT NULL, params TEXT NOT NULL, status TEXT NOT NULL,"
                  + " message TEXT NOT NULL, metrics TEXT NOT NULL, started_at INTEGER NOT NULL,"
                  + " duration_ms INTEGER NOT NULL)",
              "CREATE INDEX recent_runs_by_job ON recent_runs (profile_id, job_name, started_at)",
              "CREATE TABLE activity (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL,"
                  + " level TEXT NOT NULL, source TEXT NOT NULL, text TEXT NOT NULL,"
                  + " detail TEXT NOT NULL)",
              "CREATE INDEX activity_by_time ON activity (at)"));

  public static final int SCHEMA_VERSION = MIGRATIONS.size();

  private final Path file;
  private final ExecutorService thread;
  private final Connection connection;

  private LocalStore(Path file, ExecutorService thread, Connection connection) {
    this.file = file;
    this.thread = thread;
    this.connection = connection;
  }

  /** Opens (and creates or migrates) the database file; blocks until it is ready. */
  public static LocalStore open(Path file) {
    ExecutorService thread =
        Executors.newSingleThreadExecutor(
            runnable -> {
              Thread worker = new Thread(runnable, "datacraft-store");
              worker.setDaemon(true);
              return worker;
            });
    try {
      Connection connection =
          thread
              .submit(
                  () -> {
                    Connection opened = DriverManager.getConnection("jdbc:sqlite:" + file);
                    try {
                      try (Statement statement = opened.createStatement()) {
                        statement.execute("PRAGMA journal_mode=WAL");
                        statement.execute("PRAGMA foreign_keys=ON");
                        statement.execute("PRAGMA busy_timeout=3000");
                      }
                      migrate(opened);
                      return opened;
                    } catch (SQLException | RuntimeException failure) {
                      // A database this app cannot use stays closed, so nothing keeps it locked.
                      opened.close();
                      throw failure;
                    }
                  })
              .get();
      return new LocalStore(file, thread, connection);
    } catch (Exception exception) {
      thread.shutdownNow();
      throw new IllegalStateException("Cannot open the local store " + file, exception);
    }
  }

  static void migrate(Connection connection) throws SQLException {
    int version = userVersion(connection);
    if (version > SCHEMA_VERSION) {
      throw new SQLException(
          "The local store has schema version "
              + version
              + "; this app knows "
              + SCHEMA_VERSION
              + ". Use a newer app or another DATACRAFT_DESKTOP_HOME.");
    }
    boolean autoCommit = connection.getAutoCommit();
    connection.setAutoCommit(false);
    try (Statement statement = connection.createStatement()) {
      for (int step = version; step < SCHEMA_VERSION; step++) {
        for (String sql : MIGRATIONS.get(step)) {
          statement.execute(sql);
        }
        statement.execute("PRAGMA user_version=" + (step + 1));
      }
      connection.commit();
    } catch (SQLException exception) {
      connection.rollback();
      throw exception;
    } finally {
      connection.setAutoCommit(autoCommit);
    }
  }

  static int userVersion(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement();
        var result = statement.executeQuery("PRAGMA user_version")) {
      return result.next() ? result.getInt(1) : 0;
    }
  }

  /** Runs work on the store's thread; the future fails with the {@link SQLException}, if any. */
  public <T> CompletableFuture<T> submit(Work<T> work) {
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            return work.run(connection);
          } catch (SQLException exception) {
            throw new CompletionException(exception);
          }
        },
        thread);
  }

  /** Runs work in one transaction on the store's thread. */
  public <T> CompletableFuture<T> transaction(Work<T> work) {
    return submit(
        connection -> {
          connection.setAutoCommit(false);
          Throwable failure = null;
          boolean settled = false;
          try {
            T result = work.run(connection);
            connection.commit();
            settled = true;
            return result;
          } catch (SQLException | RuntimeException | Error exception) {
            // Restoring auto-commit commits an unfinished transaction. Roll back errors too,
            // while preserving the original failure if the connection cannot roll back.
            failure = exception;
            try {
              connection.rollback();
              settled = true;
            } catch (SQLException rollbackFailure) {
              exception.addSuppressed(rollbackFailure);
            }
            throw exception;
          } finally {
            try {
              if (settled) {
                connection.setAutoCommit(true);
              } else {
                // An unsuccessful rollback leaves the outcome unknown. Never implicitly commit
                // or reuse that connection; subsequent work must fail until the store reopens.
                connection.close();
              }
            } catch (SQLException cleanupFailure) {
              try {
                connection.close();
              } catch (SQLException closeFailure) {
                cleanupFailure.addSuppressed(closeFailure);
              }
              if (failure == null) {
                throw cleanupFailure;
              }
              failure.addSuppressed(cleanupFailure);
            }
          }
        });
  }

  public Path file() {
    return file;
  }

  @Override
  public void close() {
    thread.execute(
        () -> {
          try {
            connection.close();
          } catch (SQLException ignored) {
            // Closing on exit: nothing is left to tell.
          }
        });
    thread.shutdown();
    try {
      thread.awaitTermination(3, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
