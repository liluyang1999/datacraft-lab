package com.example.datacraft.desktop.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.datacraft.desktop.app.AppPaths;
import com.example.datacraft.desktop.store.ActivityRepository.Entry;
import com.example.datacraft.desktop.store.ActivityRepository.Level;
import com.example.datacraft.desktop.store.ActivityRepository.Source;
import com.example.datacraft.desktop.store.PresetRepository.Preset;
import com.example.datacraft.desktop.store.RecentRunRepository.RecentRun;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalStoreTest {

  @TempDir Path home;
  private LocalStore store;
  private SecretVault vault;

  @BeforeEach
  void open() {
    store = LocalStore.open(new AppPaths(home).ensureCreated().database());
    vault = new FileKeyVault(home.resolve("vault.key"));
  }

  @AfterEach
  void close() {
    store.close();
  }

  @Test
  void aNewDatabaseIsMigratedToTheCurrentSchema() throws Exception {
    int version = store.submit(LocalStore::userVersion).join();
    assertEquals(LocalStore.SCHEMA_VERSION, version);
    String journal = store.submit(connection -> pragma(connection, "journal_mode")).join();
    assertEquals("wal", journal);
  }

  @Test
  void anErrorRollsBackPartialWritesAndTheNextTransactionStillWorks() {
    AssertionError original = new AssertionError("controlled transaction failure");
    CompletionException failure =
        assertThrows(
            CompletionException.class,
            () ->
                store
                    .transaction(
                        connection -> {
                          try (Statement statement = connection.createStatement()) {
                            statement.executeUpdate(
                                "INSERT INTO settings VALUES ('partial', 'bad', 0)");
                          }
                          throw original;
                        })
                    .join());
    assertSame(original, failure.getCause());
    assertTrue(new SettingsRepository(store).all().join().isEmpty());
    assertTrue(store.submit(Connection::getAutoCommit).join());
    new SettingsRepository(store).put("next", "committed").join();
    assertEquals(Map.of("next", "committed"), new SettingsRepository(store).all().join());
  }

  @Test
  void aBrokenConnectionDoesNotHideTheOriginalTransactionFailure() {
    AssertionError original = new AssertionError("controlled connection failure");
    CompletionException failure =
        assertThrows(
            CompletionException.class,
            () ->
                store
                    .transaction(
                        connection -> {
                          connection.close();
                          throw original;
                        })
                    .join());
    assertSame(original, failure.getCause());
    assertTrue(original.getSuppressed().length > 0, "rollback failure remains available");
    assertTrue(store.submit(Connection::isClosed).join());
  }

  @Test
  void aDatabaseOfANewerAppIsRefusedInsteadOfDowngraded() throws Exception {
    store.close();
    Path file = home.resolve("datacraft.db");
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
        Statement statement = connection.createStatement()) {
      statement.execute("PRAGMA user_version=" + (LocalStore.SCHEMA_VERSION + 1));
    }
    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> LocalStore.open(file));
    assertTrue(error.getCause().getMessage().contains("newer app"), error.getCause().getMessage());
    store = LocalStore.open(home.resolve("other.db"));
  }

  @Test
  void settingsSurviveReopening() {
    new SettingsRepository(store).putAll(Map.of("theme", "dark", "window.width", "1280")).join();
    new SettingsRepository(store).put("theme", "light").join();
    store.close();
    store = LocalStore.open(home.resolve("datacraft.db"));
    assertEquals(
        Map.of("theme", "light", "window.width", "1280"),
        new SettingsRepository(store).all().join());
  }

  @Test
  void rememberedSecretsAreStoredSealedAndOpenAgain() throws Exception {
    ProfileRepository profiles = new ProfileRepository(store, vault);
    Profile.Secrets secrets =
        new Profile.Secrets(Optional.of("t".repeat(40)), Optional.of("p@ss word"));
    Profile saved = profiles.save(Profile.localDefaults(), secrets).join();

    byte[] stored =
        store
            .submit(
                connection -> {
                  try (PreparedStatement statement =
                      connection.prepareStatement(
                          "SELECT engine_token FROM profiles WHERE id = ?")) {
                    statement.setLong(1, saved.id());
                    try (ResultSet result = statement.executeQuery()) {
                      result.next();
                      return result.getBytes(1);
                    }
                  }
                })
            .join();
    assertFalse(
        new String(stored, StandardCharsets.ISO_8859_1).contains("tttt"),
        "the token is not stored in clear");
    assertEquals(secrets, profiles.secrets(saved.id()).join());
    assertEquals("Secrets[engineToken=<redacted>, airflowPassword=<redacted>]", secrets.toString());
  }

  @Test
  void secretsAreNotStoredWhenTheProfileDoesNotRememberThem() {
    ProfileRepository profiles = new ProfileRepository(store, vault);
    Profile forgetful =
        new Profile(
            Profile.NEW,
            "无痕",
            Profile.localDefaults().engineUrl(),
            Profile.localDefaults().airflowUrl(),
            "admin",
            false);
    Profile saved =
        profiles
            .save(forgetful, new Profile.Secrets(Optional.of("t".repeat(40)), Optional.of("pw")))
            .join();
    assertEquals(Profile.Secrets.NONE, profiles.secrets(saved.id()).join());
  }

  @Test
  void deletingAProfileDeletesItsCachedRuns() {
    ProfileRepository profiles = new ProfileRepository(store, vault);
    Profile saved = profiles.save(Profile.localDefaults(), Profile.Secrets.NONE).join();
    RecentRunRepository runs = new RecentRunRepository(store);
    Instant now = Instant.parse("2026-10-27T10:00:00Z");
    runs.add(run(saved.id(), "noop", now), now).join();
    profiles.delete(saved.id()).join();
    assertTrue(profiles.list().join().isEmpty());
    assertTrue(runs.list(saved.id(), null, 10).join().isEmpty());
  }

  @Test
  void presetsAreSavedPerJobAndReplacedByName() {
    PresetRepository presets = new PresetRepository(store);
    presets.save(new Preset("csv-profile", "销售", Map.of("input", "/data/a.csv"))).join();
    presets
        .save(new Preset("csv-profile", "销售", Map.of("input", "/data/b.csv", "header", "false")))
        .join();
    presets.save(new Preset("row-count", "销售", Map.of("input", "/data/c"))).join();
    assertEquals(
        java.util.List.of(
            new Preset("csv-profile", "销售", Map.of("input", "/data/b.csv", "header", "false"))),
        presets.list("csv-profile").join());
    presets.delete("csv-profile", "销售").join();
    assertTrue(presets.list("csv-profile").join().isEmpty());
  }

  @Test
  void theRunCacheKeepsTwoHundredRunsPerProfileAndForgetsOldOnes() {
    long profile =
        new ProfileRepository(store, vault)
            .save(Profile.localDefaults(), Profile.Secrets.NONE)
            .join()
            .id();
    RecentRunRepository runs = new RecentRunRepository(store);
    Instant now = Instant.parse("2026-10-27T10:00:00Z");
    runs.add(run(profile, "old", now.minus(Duration.ofDays(15))), now.minus(Duration.ofDays(15)))
        .join();
    for (int index = 0; index < RecentRunRepository.KEEP_PER_PROFILE + 5; index++) {
      runs.add(run(profile, "noop", now.plusSeconds(index)), now).join();
    }
    var kept = runs.list(profile, null, 1000).join();
    assertEquals(RecentRunRepository.KEEP_PER_PROFILE, kept.size());
    assertEquals(
        now.plusSeconds(RecentRunRepository.KEEP_PER_PROFILE + 4), kept.getFirst().startedAt());
    assertTrue(runs.list(profile, "old", 10).join().isEmpty());
  }

  @Test
  void theActivityLogKeepsItsNewestEntries() {
    ActivityRepository activity = new ActivityRepository(store);
    Instant start = Instant.parse("2026-10-27T10:00:00Z");
    for (int index = 0; index < ActivityRepository.KEEP + 3; index++) {
      activity
          .add(new Entry(start.plusSeconds(index), Level.INFO, Source.APP, "事件 " + index, ""))
          .join();
    }
    var entries = activity.list(1000).join();
    assertEquals(ActivityRepository.KEEP, entries.size());
    assertEquals("事件 " + (ActivityRepository.KEEP + 2), entries.getFirst().text());
    activity.clear().join();
    assertTrue(activity.list(10).join().isEmpty());
  }

  @Test
  void theKeyFileVaultRefusesASecretSealedWithAnotherKey() {
    byte[] sealed = vault.seal("secret".getBytes(StandardCharsets.UTF_8));
    assertEquals("secret", new String(vault.open(sealed), StandardCharsets.UTF_8));
    assertNotEquals(
        Arrays.toString(sealed),
        Arrays.toString(vault.seal("secret".getBytes(StandardCharsets.UTF_8))),
        "each sealing uses a fresh IV");
    SecretVault other = new FileKeyVault(home.resolve("other.key"));
    assertThrows(SecretVault.VaultException.class, () -> other.open(sealed));
  }

  private static RecentRun run(long profile, String job, Instant at) {
    return new RecentRun(
        profile,
        job,
        Map.of("input", "/data/a.csv"),
        "SUCCEEDED",
        "ok",
        Map.of("rows", "2"),
        at,
        Duration.ofMillis(120));
  }

  private static String pragma(Connection connection, String name) throws java.sql.SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery("PRAGMA " + name)) {
      result.next();
      return result.getString(1);
    }
  }
}
