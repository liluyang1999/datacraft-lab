package com.example.datacraft.desktop.service;

import com.example.datacraft.desktop.api.AirflowClient;
import com.example.datacraft.desktop.api.EngineClient;
import com.example.datacraft.desktop.api.HttpTransport;
import com.example.datacraft.desktop.store.Profile;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The active connection: one profile, its secrets and the two clients built from them. Secrets of a
 * profile that does not remember them live only here, for this run of the app.
 */
public final class Session {

  /** The clients of the active profile. */
  public record Connection(Profile profile, EngineClient engine, AirflowClient airflow) {}

  private final HttpTransport transport;
  private final Clock clock;
  private final List<Consumer<Optional<Connection>>> listeners = new CopyOnWriteArrayList<>();
  private volatile Connection current;

  public Session(HttpTransport transport, Clock clock) {
    this.transport = transport;
    this.clock = clock;
  }

  public Optional<Connection> current() {
    return Optional.ofNullable(current);
  }

  /** Makes the profile active with these secrets and tells every listener. */
  public void activate(Profile profile, Profile.Secrets secrets) {
    current =
        new Connection(
            profile,
            new EngineClient(transport, profile.engineUrl(), secrets.engineToken()),
            new AirflowClient(
                transport,
                profile.airflowUrl(),
                profile.airflowUser(),
                secrets.airflowPassword(),
                clock));
    listeners.forEach(listener -> listener.accept(Optional.of(current)));
  }

  /** No active profile, e.g. after the active one was deleted. */
  public void clear() {
    current = null;
    listeners.forEach(listener -> listener.accept(Optional.empty()));
  }

  public void subscribe(Consumer<Optional<Connection>> listener) {
    listeners.add(listener);
  }
}
