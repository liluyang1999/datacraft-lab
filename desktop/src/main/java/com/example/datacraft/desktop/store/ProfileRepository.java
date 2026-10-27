package com.example.datacraft.desktop.store;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Connection profiles. Secrets are sealed by the {@link SecretVault} before they are written and
 * only when the profile asks to remember them; otherwise they live in memory for the session.
 */
public final class ProfileRepository {

  private final LocalStore store;
  private final SecretVault vault;

  public ProfileRepository(LocalStore store, SecretVault vault) {
    this.store = store;
    this.vault = vault;
  }

  public CompletableFuture<List<Profile>> list() {
    return store.submit(
        connection -> {
          List<Profile> profiles = new ArrayList<>();
          try (PreparedStatement statement =
                  connection.prepareStatement(
                      "SELECT id, name, engine_url, airflow_url, airflow_user, remember_secrets"
                          + " FROM profiles ORDER BY name");
              ResultSet result = statement.executeQuery()) {
            while (result.next()) {
              profiles.add(
                  new Profile(
                      result.getLong(1),
                      result.getString(2),
                      URI.create(result.getString(3)),
                      URI.create(result.getString(4)),
                      result.getString(5),
                      result.getInt(6) != 0));
            }
          }
          return profiles;
        });
  }

  /** Inserts or updates the profile; returns it with its id. Secrets are kept only if asked to. */
  public CompletableFuture<Profile> save(Profile profile, Profile.Secrets secrets) {
    return store.transaction(
        connection -> {
          // Sealing may call into the OS (DPAPI): on the store's thread, never the FX thread.
          byte[] token = profile.rememberSecrets() ? seal(secrets.engineToken()) : null;
          byte[] password = profile.rememberSecrets() ? seal(secrets.airflowPassword()) : null;
          long now = System.currentTimeMillis();
          if (profile.id() == Profile.NEW) {
            try (PreparedStatement statement =
                connection.prepareStatement(
                    "INSERT INTO profiles (name, engine_url, engine_token, airflow_url, airflow_user,"
                        + " airflow_password, remember_secrets, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
              bind(statement, profile, token, password);
              statement.setLong(8, now);
              statement.setLong(9, now);
              statement.executeUpdate();
              try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                  throw new SQLException("No id for the new profile");
                }
                return profile.withId(keys.getLong(1));
              }
            }
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE profiles SET name = ?, engine_url = ?, engine_token = ?, airflow_url = ?,"
                      + " airflow_user = ?, airflow_password = ?, remember_secrets = ?, updated_at = ?"
                      + " WHERE id = ?")) {
            bind(statement, profile, token, password);
            statement.setLong(8, now);
            statement.setLong(9, profile.id());
            if (statement.executeUpdate() != 1) {
              throw new SQLException("Profile " + profile.id() + " does not exist");
            }
            return profile;
          }
        });
  }

  /** The remembered secrets of a profile, opened; none when it does not remember them. */
  public CompletableFuture<Profile.Secrets> secrets(long profileId) {
    return store.submit(
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT engine_token, airflow_password FROM profiles WHERE id = ?")) {
            statement.setLong(1, profileId);
            try (ResultSet result = statement.executeQuery()) {
              if (!result.next()) {
                return Profile.Secrets.NONE;
              }
              // Opening may call into the OS (DPAPI): on the store's thread as well.
              return new Profile.Secrets(open(result.getBytes(1)), open(result.getBytes(2)));
            }
          }
        });
  }

  /** Deletes the profile, its sealed secrets and its cached runs. */
  public CompletableFuture<Void> delete(long profileId) {
    return store.transaction(
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement("DELETE FROM profiles WHERE id = ?")) {
            statement.setLong(1, profileId);
            statement.executeUpdate();
          }
          return null;
        });
  }

  private static void bind(
      PreparedStatement statement, Profile profile, byte[] token, byte[] password)
      throws SQLException {
    statement.setString(1, profile.name());
    statement.setString(2, profile.engineUrl().toString());
    statement.setBytes(3, token);
    statement.setString(4, profile.airflowUrl().toString());
    statement.setString(5, profile.airflowUser());
    statement.setBytes(6, password);
    statement.setInt(7, profile.rememberSecrets() ? 1 : 0);
  }

  private byte[] seal(Optional<String> secret) {
    return secret.map(value -> vault.seal(value.getBytes(StandardCharsets.UTF_8))).orElse(null);
  }

  private Optional<String> open(byte[] sealed) {
    return sealed == null
        ? Optional.empty()
        : Optional.of(new String(vault.open(sealed), StandardCharsets.UTF_8));
  }
}
