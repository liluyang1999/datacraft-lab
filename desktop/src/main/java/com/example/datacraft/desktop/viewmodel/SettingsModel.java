package com.example.datacraft.desktop.viewmodel;

import com.example.datacraft.desktop.app.AppPaths;
import com.example.datacraft.desktop.app.Preferences;
import com.example.datacraft.desktop.service.ActivityLog;
import com.example.datacraft.desktop.service.ProfileService;
import com.example.datacraft.desktop.service.ProfileService.TestResult;
import com.example.datacraft.desktop.service.Session;
import com.example.datacraft.desktop.store.Profile;
import com.example.datacraft.desktop.store.RecentRunRepository;
import com.example.datacraft.desktop.util.Formats;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Stream;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.LongProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyLongProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/** Settings: connection profiles, appearance, refresh, and the local data in ~/.datacraft. */
public final class SettingsModel {

  private final ProfileService service;
  private final Preferences preferences;
  private final ActivityLog activity;
  private final RecentRunRepository recentRuns;
  private final AppPaths paths;
  private final String vaultDescription;
  private final Executor ui;

  private final ObservableList<Profile> profiles = FXCollections.observableArrayList();
  private final ObjectProperty<Profile> editing = new SimpleObjectProperty<>();
  private final LongProperty activeId = new SimpleLongProperty(Profile.NEW);
  private final StringProperty name = new SimpleStringProperty("");
  private final StringProperty engineUrl = new SimpleStringProperty("");
  private final StringProperty engineToken = new SimpleStringProperty("");
  private final StringProperty airflowUrl = new SimpleStringProperty("");
  private final StringProperty airflowUser = new SimpleStringProperty("");
  private final StringProperty airflowPassword = new SimpleStringProperty("");
  private final BooleanProperty remember = new SimpleBooleanProperty(true);
  private final StringProperty formError = new SimpleStringProperty("");
  private final BooleanProperty busy = new SimpleBooleanProperty(false);
  private final ObjectProperty<TestResult> testResult = new SimpleObjectProperty<>();

  private final ObjectProperty<Preferences.Theme> theme;
  private final BooleanProperty reducedMotion;
  private final ObjectProperty<Preferences.Refresh> refresh;
  private final IntegerProperty runTimeout;
  private final StringProperty dataSize = new SimpleStringProperty("");

  /** A new connection is being typed in: reloading the list must not replace the form. */
  private boolean creating;

  /** Counts the profiles shown in the form; a test result for an earlier one is dropped. */
  private long formGeneration;

  public SettingsModel(
      ProfileService service,
      Session session,
      Preferences preferences,
      ActivityLog activity,
      RecentRunRepository recentRuns,
      AppPaths paths,
      String vaultDescription,
      Executor ui) {
    this.service = service;
    this.preferences = preferences;
    this.activity = activity;
    this.recentRuns = recentRuns;
    this.paths = paths;
    this.vaultDescription = vaultDescription;
    this.ui = ui;
    theme = new SimpleObjectProperty<>(preferences.theme());
    theme.addListener((property, before, value) -> preferences.theme(value));
    reducedMotion = new SimpleBooleanProperty(preferences.reducedMotion());
    reducedMotion.addListener((property, before, value) -> preferences.reducedMotion(value));
    refresh = new SimpleObjectProperty<>(preferences.refresh());
    refresh.addListener((property, before, value) -> preferences.refresh(value));
    runTimeout = new SimpleIntegerProperty(preferences.runTimeoutMinutes());
    runTimeout.addListener(
        (property, before, value) -> preferences.runTimeoutMinutes(value.intValue()));
    // A change made elsewhere (a shortcut, another window) shows here too.
    preferences.subscribe(
        key ->
            ui.execute(
                () -> {
                  theme.set(preferences.theme());
                  reducedMotion.set(preferences.reducedMotion());
                  refresh.set(preferences.refresh());
                  runTimeout.set(preferences.runTimeoutMinutes());
                }));
    session.current().ifPresent(connection -> activeId.set(connection.profile().id()));
    session.subscribe(
        connection ->
            ui.execute(
                () -> activeId.set(connection.map(c -> c.profile().id()).orElse(Profile.NEW))));
  }

  /** Reads the profiles and shows the active one, or the local defaults when there is none. */
  public void load() {
    service
        .list()
        .thenAccept(
            list ->
                ui.execute(
                    () -> {
                      profiles.setAll(list);
                      if (creating) {
                        return;
                      }
                      Optional<Profile> active =
                          list.stream().filter(p -> p.id() == activeId.get()).findFirst();
                      if (editing.get() == null || !list.contains(editing.get())) {
                        active
                            .or(() -> list.stream().findFirst())
                            .ifPresentOrElse(this::edit, this::newProfile);
                      }
                    }));
    updateDataSize();
  }

  /** Fills the form with a profile; its secrets are opened in the background. */
  public void edit(Profile profile) {
    creating = false;
    formGeneration++;
    editing.set(profile);
    name.set(profile.name());
    engineUrl.set(profile.engineUrl().toString());
    airflowUrl.set(profile.airflowUrl().toString());
    airflowUser.set(profile.airflowUser());
    remember.set(profile.rememberSecrets());
    engineToken.set("");
    airflowPassword.set("");
    formError.set("");
    testResult.set(null);
    service
        .secrets(profile)
        .thenAccept(
            secrets ->
                ui.execute(
                    () -> {
                      if (profile.equals(editing.get())) {
                        engineToken.set(secrets.engineToken().orElse(""));
                        airflowPassword.set(secrets.airflowPassword().orElse(""));
                      }
                    }))
        .exceptionally(
            failure -> {
              ui.execute(() -> formError.set("保存的口令无法解密，请重新输入后保存。"));
              return null;
            });
  }

  /** A new profile with the defaults of a local Compose deployment. */
  public void newProfile() {
    Profile defaults = Profile.localDefaults();
    creating = true;
    formGeneration++;
    editing.set(null);
    name.set(profiles.isEmpty() ? defaults.name() : "");
    engineUrl.set(defaults.engineUrl().toString());
    airflowUrl.set(defaults.airflowUrl().toString());
    airflowUser.set(defaults.airflowUser());
    remember.set(true);
    engineToken.set("");
    airflowPassword.set("");
    formError.set("");
    testResult.set(null);
  }

  /** The profile the form describes, or empty with {@link #formError} saying what is wrong. */
  Optional<Profile> fromForm() {
    String profileName = name.get() == null ? "" : name.get().strip();
    if (profileName.isEmpty()) {
      formError.set("给连接起一个名称。");
      return Optional.empty();
    }
    Optional<URI> engine = url(engineUrl.get(), "引擎地址");
    if (engine.isEmpty()) {
      return Optional.empty();
    }
    Optional<URI> airflow = url(airflowUrl.get(), "Airflow 地址");
    if (airflow.isEmpty()) {
      return Optional.empty();
    }
    formError.set("");
    long id = editing.get() == null ? Profile.NEW : editing.get().id();
    return Optional.of(
        new Profile(
            id, profileName, engine.get(), airflow.get(), airflowUser.get(), remember.get()));
  }

  private Optional<URI> url(String text, String label) {
    String value = text == null ? "" : text.strip();
    try {
      URI uri = new URI(value);
      String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
      if ((scheme.equals("http") || scheme.equals("https"))
          && uri.getHost() != null
          && uri.getRawQuery() == null
          && uri.getRawFragment() == null) {
        return Optional.of(uri);
      }
    } catch (URISyntaxException malformed) {
      // reported below
    }
    formError.set(label + "应是 http:// 或 https:// 开头的地址，例如 http://127.0.0.1:8088。");
    return Optional.empty();
  }

  private Profile.Secrets secrets() {
    return new Profile.Secrets(
        Optional.ofNullable(engineToken.get()).map(String::strip),
        Optional.ofNullable(airflowPassword.get()));
  }

  public CompletableFuture<Profile> save() {
    Optional<Profile> profile = fromForm();
    if (profile.isEmpty()) {
      return CompletableFuture.failedFuture(new IllegalArgumentException(formError.get()));
    }
    busy.set(true);
    return service
        .save(profile.get(), secrets())
        .whenComplete(
            (saved, failure) ->
                ui.execute(
                    () -> {
                      busy.set(false);
                      if (failure != null) {
                        formError.set("没有保存：" + rootMessage(failure));
                        return;
                      }
                      creating = false;
                      editing.set(saved);
                      service.list().thenAccept(list -> ui.execute(() -> profiles.setAll(list)));
                    }));
  }

  public CompletableFuture<Void> delete() {
    Profile profile = editing.get();
    if (profile == null) {
      newProfile();
      return CompletableFuture.completedFuture(null);
    }
    return service
        .delete(profile)
        .thenRun(
            () ->
                ui.execute(
                    () -> {
                      editing.set(null);
                      load();
                    }));
  }

  public CompletableFuture<TestResult> test() {
    Optional<Profile> profile = fromForm();
    if (profile.isEmpty()) {
      return CompletableFuture.failedFuture(new IllegalArgumentException(formError.get()));
    }
    busy.set(true);
    testResult.set(null);
    long tested = formGeneration;
    return service
        .test(profile.get(), secrets())
        .whenComplete(
            (result, failure) ->
                ui.execute(
                    () -> {
                      busy.set(false);
                      if (tested == formGeneration) {
                        testResult.set(result); // still the profile that was tested
                      }
                    }));
  }

  /**
   * Connects with what the form says: the form is saved first, so edits that were not saved yet (a
   * pasted token, a new address) are the ones the connection uses.
   */
  public CompletableFuture<Void> activate() {
    return save().thenCompose(service::activate);
  }

  /** Deletes the cached runs and the activity record; settings and profiles stay. */
  public CompletableFuture<Void> clearCache() {
    return recentRuns
        .clear()
        .thenCompose(ignored -> activity.clear())
        .thenRun(() -> ui.execute(this::updateDataSize));
  }

  public void updateDataSize() {
    CompletableFuture.supplyAsync(() -> size(paths.home()), Thread::startVirtualThread)
        .thenAccept(bytes -> ui.execute(() -> dataSize.set(Formats.bytes(bytes))));
  }

  private static long size(Path directory) {
    try (Stream<Path> files = Files.walk(directory)) {
      return files
          .filter(Files::isRegularFile)
          .mapToLong(
              file -> {
                try {
                  return Files.size(file);
                } catch (IOException gone) {
                  return 0;
                }
              })
          .sum();
    } catch (IOException | UncheckedIOException unreadable) {
      return 0;
    }
  }

  private static String rootMessage(Throwable failure) {
    Throwable cause = failure;
    while (cause.getCause() != null) {
      cause = cause.getCause();
    }
    return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
  }

  public ObservableList<Profile> profiles() {
    return profiles;
  }

  public ReadOnlyObjectProperty<Profile> editing() {
    return editing;
  }

  public ReadOnlyLongProperty activeId() {
    return activeId;
  }

  public StringProperty name() {
    return name;
  }

  public StringProperty engineUrl() {
    return engineUrl;
  }

  public StringProperty engineToken() {
    return engineToken;
  }

  public StringProperty airflowUrl() {
    return airflowUrl;
  }

  public StringProperty airflowUser() {
    return airflowUser;
  }

  public StringProperty airflowPassword() {
    return airflowPassword;
  }

  public BooleanProperty remember() {
    return remember;
  }

  public ReadOnlyStringProperty formError() {
    return formError;
  }

  public ReadOnlyBooleanProperty busy() {
    return busy;
  }

  public ReadOnlyObjectProperty<TestResult> testResult() {
    return testResult;
  }

  public ObjectProperty<Preferences.Theme> theme() {
    return theme;
  }

  public BooleanProperty reducedMotion() {
    return reducedMotion;
  }

  public ObjectProperty<Preferences.Refresh> refresh() {
    return refresh;
  }

  public IntegerProperty runTimeout() {
    return runTimeout;
  }

  public ReadOnlyStringProperty dataSize() {
    return dataSize;
  }

  public Path dataLocation() {
    return paths.home();
  }

  public String vaultDescription() {
    return vaultDescription;
  }
}
