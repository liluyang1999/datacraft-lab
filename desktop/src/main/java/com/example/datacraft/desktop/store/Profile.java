package com.example.datacraft.desktop.store;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;

/**
 * A connection profile: where the engine API and Airflow are, and who signs in. The secrets are not
 * part of this record; {@link ProfileRepository#secrets} opens them when a client needs them.
 */
public record Profile(
    long id,
    String name,
    URI engineUrl,
    URI airflowUrl,
    String airflowUser,
    boolean rememberSecrets) {

  public static final long NEW = 0;

  public Profile {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(engineUrl, "engineUrl");
    Objects.requireNonNull(airflowUrl, "airflowUrl");
    airflowUser = airflowUser == null ? "" : airflowUser.strip();
  }

  /** The Compose defaults: both services published on loopback. */
  public static Profile localDefaults() {
    return new Profile(
        NEW,
        "本机部署",
        URI.create("http://127.0.0.1:8088"),
        URI.create("http://127.0.0.1:8080"),
        "admin",
        true);
  }

  public Profile withId(long newId) {
    return new Profile(newId, name, engineUrl, airflowUrl, airflowUser, rememberSecrets);
  }

  /** The two secrets of a profile; either may be absent. Never printed. */
  public record Secrets(Optional<String> engineToken, Optional<String> airflowPassword) {

    public static final Secrets NONE = new Secrets(Optional.empty(), Optional.empty());

    public Secrets {
      engineToken = engineToken.filter(value -> !value.isBlank());
      airflowPassword = airflowPassword.filter(value -> !value.isEmpty());
    }

    @Override
    public String toString() {
      return "Secrets[engineToken="
          + (engineToken.isPresent() ? "<redacted>" : "none")
          + ", airflowPassword="
          + (airflowPassword.isPresent() ? "<redacted>" : "none")
          + "]";
    }
  }
}
