package com.example.datacraft.desktop.app;

import com.example.datacraft.desktop.api.HttpTransport;
import com.example.datacraft.desktop.service.ActivityLog;
import com.example.datacraft.desktop.service.ConnectionMonitor;
import com.example.datacraft.desktop.service.JobCatalog;
import com.example.datacraft.desktop.service.JobService;
import com.example.datacraft.desktop.service.PipelineService;
import com.example.datacraft.desktop.service.ProfileService;
import com.example.datacraft.desktop.service.Session;
import com.example.datacraft.desktop.service.Ticker;
import com.example.datacraft.desktop.store.ActivityRepository;
import com.example.datacraft.desktop.store.LocalStore;
import com.example.datacraft.desktop.store.PresetRepository;
import com.example.datacraft.desktop.store.ProfileRepository;
import com.example.datacraft.desktop.store.RecentRunRepository;
import com.example.datacraft.desktop.store.SecretVault;
import com.example.datacraft.desktop.store.SettingsRepository;
import java.time.Clock;
import java.time.Duration;

/**
 * The composition root: creates every service once and hands them to the views. Nothing here runs
 * on the FX thread except the getters; {@link #open} reads the settings before the window opens.
 */
public final class AppContext implements AutoCloseable {

  private final AppPaths paths;
  private final LocalStore store;
  private final SecretVault vault;
  private final Preferences preferences;
  private final HttpTransport transport;
  private final Ticker ticker;
  private final Clock clock;
  private final ActivityLog activity;
  private final Session session;
  private final ConnectionMonitor monitor;
  private final ProfileService profiles;
  private final PresetRepository presets;
  private final RecentRunRepository recentRuns;
  private final JobService jobs;
  private final PipelineService pipelines;
  private final JobCatalog catalog;

  private AppContext(AppPaths paths, LocalStore store) {
    this.paths = paths;
    this.store = store;
    this.clock = Clock.systemDefaultZone();
    this.vault = SecretVault.forPlatform(paths);
    this.preferences = Preferences.load(new SettingsRepository(store));
    this.transport = new HttpTransport();
    this.ticker = Ticker.system();
    this.activity = new ActivityLog(new ActivityRepository(store), clock);
    this.session = new Session(transport, clock);
    this.monitor =
        new ConnectionMonitor(
            session, ticker, clock, activity, () -> preferences.refresh().health());
    this.profiles =
        new ProfileService(
            new ProfileRepository(store, vault), session, preferences, transport, clock, activity);
    this.presets = new PresetRepository(store);
    this.recentRuns = new RecentRunRepository(store);
    this.jobs =
        new JobService(
            session,
            recentRuns,
            activity,
            clock,
            () -> Duration.ofMinutes(preferences.runTimeoutMinutes()));
    this.pipelines = new PipelineService(session, activity);
    this.catalog = JobCatalog.bundled(transport.json());
  }

  /** Creates {@code ~/.datacraft} when needed, opens and migrates the database, reads settings. */
  public static AppContext open(AppPaths paths) {
    paths.ensureCreated();
    LocalStore store = LocalStore.open(paths.database());
    try {
      return new AppContext(paths, store);
    } catch (RuntimeException failure) {
      store.close();
      throw failure;
    }
  }

  public AppPaths paths() {
    return paths;
  }

  public LocalStore store() {
    return store;
  }

  public SecretVault vault() {
    return vault;
  }

  public Preferences preferences() {
    return preferences;
  }

  public Clock clock() {
    return clock;
  }

  public Ticker ticker() {
    return ticker;
  }

  public ActivityLog activity() {
    return activity;
  }

  public Session session() {
    return session;
  }

  public ConnectionMonitor monitor() {
    return monitor;
  }

  public ProfileService profiles() {
    return profiles;
  }

  public PresetRepository presets() {
    return presets;
  }

  public RecentRunRepository recentRuns() {
    return recentRuns;
  }

  public JobService jobs() {
    return jobs;
  }

  public PipelineService pipelines() {
    return pipelines;
  }

  public JobCatalog catalog() {
    return catalog;
  }

  /** The shared JSON mapper (thread-safe once configured). */
  public com.fasterxml.jackson.databind.ObjectMapper json() {
    return transport.json();
  }

  @Override
  public void close() {
    monitor.pause();
    ticker.close();
    transport.close();
    store.close();
  }
}
