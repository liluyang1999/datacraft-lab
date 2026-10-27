package com.example.datacraft.desktop.service;

import com.example.datacraft.desktop.api.ApiError;
import com.example.datacraft.desktop.api.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** What the app knows about one backend right now. */
public record BackendStatus(
    State state, Optional<Duration> latency, String summary, Instant checkedAt) {

  /** The states the status dot shows. */
  public enum State {
    /** No answer yet since the profile became active. */
    CONNECTING,
    /** Reachable, and the credentials work. */
    UP,
    /** Reachable, but a component is unhealthy (an Airflow scheduler without heartbeat, say). */
    DEGRADED,
    /** Reachable, but the token, user name or password is wrong. */
    UNAUTHORIZED,
    /** Not reachable. */
    DOWN,
    /** The profile has no address for it. */
    NOT_CONFIGURED
  }

  public static BackendStatus connecting(Instant now) {
    return new BackendStatus(State.CONNECTING, Optional.empty(), "正在连接", now);
  }

  public static BackendStatus up(Duration latency, String summary, Instant now) {
    return new BackendStatus(State.UP, Optional.of(latency), summary, now);
  }

  /** The status a failed check leaves, with the reason the user can act on. */
  public static BackendStatus failed(String backend, Throwable failure, Instant now) {
    ApiException api = ApiException.of(failure).orElse(null);
    if (api == null) {
      return new BackendStatus(State.DOWN, Optional.empty(), backend + "检查失败：" + failure, now);
    }
    if (api.error() == ApiError.UNAUTHORIZED) {
      return new BackendStatus(
          State.UNAUTHORIZED, Optional.empty(), Messages.unauthorized(backend), now);
    }
    // A check that gets no answer in time is as good as no connection.
    State state =
        api.error() == ApiError.UNREACHABLE || api.error() == ApiError.TIMED_OUT
            ? State.DOWN
            : State.DEGRADED;
    return new BackendStatus(state, Optional.empty(), Messages.describe(backend, api), now);
  }

  public boolean usable() {
    return state == State.UP || state == State.DEGRADED;
  }
}
