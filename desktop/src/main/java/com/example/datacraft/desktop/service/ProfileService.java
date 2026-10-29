package com.example.datacraft.desktop.service;

import com.example.datacraft.desktop.api.AirflowClient;
import com.example.datacraft.desktop.api.EngineClient;
import com.example.datacraft.desktop.api.HttpTransport;
import com.example.datacraft.desktop.app.Preferences;
import com.example.datacraft.desktop.store.ActivityRepository.Level;
import com.example.datacraft.desktop.store.ActivityRepository.Source;
import com.example.datacraft.desktop.store.Profile;
import com.example.datacraft.desktop.store.ProfileRepository;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Connection profiles: saving, deleting, testing and choosing the active one. The secrets of a
 * profile that does not remember them are kept in memory for this run of the app only.
 */
public final class ProfileService {

  /** The outcome of "测试连接": one status per backend. */
  public record TestResult(BackendStatus engine, BackendStatus airflow) {}

  private final ProfileRepository repository;
  private final Session session;
  private final Preferences preferences;
  private final HttpTransport transport;
  private final Clock clock;
  private final ActivityLog activity;
  private final Map<Long, Profile.Secrets> unsaved = new ConcurrentHashMap<>();
  // The order of user choices, not the order in which storage/decryption happens to finish.
  // Selection and its application share this monitor; no I/O is awaited while holding it.
  private long selectionGeneration;

  public ProfileService(
      ProfileRepository repository,
      Session session,
      Preferences preferences,
      HttpTransport transport,
      Clock clock,
      ActivityLog activity) {
    this.repository = repository;
    this.session = session;
    this.preferences = preferences;
    this.transport = transport;
    this.clock = clock;
    this.activity = activity;
  }

  public CompletableFuture<List<Profile>> list() {
    return repository.list();
  }

  /** Activates the profile used last time, or the first one; none when there is no profile. */
  public CompletableFuture<Optional<Profile>> restore() {
    return restore(beginSelection());
  }

  private CompletableFuture<Optional<Profile>> restore(long selection) {
    return list()
        .thenCompose(
            profiles -> {
              Optional<Profile> chosen =
                  preferences
                      .activeProfile()
                      .flatMap(id -> profiles.stream().filter(p -> p.id() == id).findFirst())
                      .or(() -> profiles.stream().findFirst());
              if (chosen.isEmpty()) {
                synchronized (this) {
                  if (selection == selectionGeneration) {
                    session.clear();
                  }
                }
                return CompletableFuture.completedFuture(Optional.<Profile>empty());
              }
              return activate(chosen.get(), selection).thenApply(ignored -> chosen);
            });
  }

  /** Saves the profile; when it is the active one, the session switches to the new values. */
  public CompletableFuture<Profile> save(Profile profile, Profile.Secrets secrets) {
    return save(profile, secrets, currentSelection(), false);
  }

  /** Saves and selects in one user operation; an older save cannot supersede a later choice. */
  public CompletableFuture<Profile> saveAndActivate(Profile profile, Profile.Secrets secrets) {
    return save(profile, secrets, beginSelection(), true);
  }

  private CompletableFuture<Profile> save(
      Profile profile, Profile.Secrets secrets, long selection, boolean select) {
    return repository
        .save(profile, secrets)
        .thenApply(
            saved -> {
              if (saved.rememberSecrets()) {
                unsaved.remove(saved.id());
              } else {
                unsaved.put(saved.id(), secrets);
              }
              synchronized (this) {
                boolean active =
                    session.current().map(c -> c.profile().id() == saved.id()).orElse(false);
                if (select || active || session.current().isEmpty()) {
                  applySelection(saved, secrets, selection);
                }
              }
              activity.record(Level.INFO, Source.APP, "已保存连接 " + saved.name());
              return saved;
            });
  }

  public CompletableFuture<Void> delete(Profile profile) {
    long selection = currentSelection();
    return repository
        .delete(profile.id())
        .thenCompose(
            ignored -> {
              unsaved.remove(profile.id());
              activity.record(Level.INFO, Source.APP, "已删除连接 " + profile.name());
              synchronized (this) {
                boolean active =
                    session.current().map(c -> c.profile().id() == profile.id()).orElse(false);
                return active && selection == selectionGeneration
                    ? restore(beginSelection()).thenApply(chosen -> null)
                    : CompletableFuture.completedFuture(null);
              }
            });
  }

  /** Makes the profile active with its remembered or in-memory secrets. */
  public CompletableFuture<Void> activate(Profile profile) {
    return activate(profile, beginSelection());
  }

  private CompletableFuture<Void> activate(Profile profile, long selection) {
    return secrets(profile).thenAccept(secrets -> applySelection(profile, secrets, selection));
  }

  private synchronized long beginSelection() {
    return ++selectionGeneration;
  }

  private synchronized long currentSelection() {
    return selectionGeneration;
  }

  private synchronized void applySelection(
      Profile profile, Profile.Secrets secrets, long selection) {
    if (selection == selectionGeneration) {
      preferences.activeProfile(profile.id());
      session.activate(profile, secrets);
    }
  }

  /** The secrets the profile would connect with: from the vault, or from this run's memory. */
  public CompletableFuture<Profile.Secrets> secrets(Profile profile) {
    if (!profile.rememberSecrets()) {
      return CompletableFuture.completedFuture(
          unsaved.getOrDefault(profile.id(), Profile.Secrets.NONE));
    }
    return repository.secrets(profile.id());
  }

  /** Checks both backends with these values, without saving or activating anything. */
  public CompletableFuture<TestResult> test(Profile profile, Profile.Secrets secrets) {
    EngineClient engine = new EngineClient(transport, profile.engineUrl(), secrets.engineToken());
    AirflowClient airflow =
        new AirflowClient(
            transport,
            profile.airflowUrl(),
            profile.airflowUser(),
            secrets.airflowPassword(),
            clock);
    CompletableFuture<BackendStatus> engineStatus =
        engine
            .health()
            .thenCompose(
                health ->
                    engine
                        .jobs()
                        .thenApply(
                            jobs ->
                                BackendStatus.up(
                                    health.latency(), "作业 " + jobs.size() + " 个", clock.instant())))
            .exceptionally(
                failure ->
                    BackendStatus.failed(ConnectionMonitor.ENGINE, failure, clock.instant()));
    CompletableFuture<BackendStatus> airflowStatus =
        airflow
            .health()
            .thenCompose(
                health ->
                    airflow
                        .login()
                        .thenCompose(ignored -> airflow.version())
                        .thenApply(
                            version ->
                                BackendStatus.up(
                                    health.latency(), "版本 " + version, clock.instant())))
            .exceptionally(
                failure ->
                    BackendStatus.failed(ConnectionMonitor.AIRFLOW, failure, clock.instant()));
    return engineStatus.thenCombine(airflowStatus, TestResult::new);
  }
}
