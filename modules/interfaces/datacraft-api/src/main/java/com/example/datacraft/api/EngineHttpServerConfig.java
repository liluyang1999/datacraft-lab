package com.example.datacraft.api;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Where the HTTP API listens and what it demands of callers.
 *
 * @param host interface to bind
 * @param port port to bind; 0 lets the operating system pick one
 * @param backlog accept backlog; 0 uses the system default
 * @param maxConcurrentRuns job runs in progress at one time; a further run request gets 503
 * @param apiToken bearer token every request under {@code /jobs} must present; empty leaves the API
 *     open, which is only acceptable on loopback
 */
public record EngineHttpServerConfig(
    String host, int port, int backlog, int maxConcurrentRuns, Optional<String> apiToken) {

  /** Job runs in progress at one time unless configured otherwise. */
  public static final int DEFAULT_MAX_CONCURRENT_RUNS = 4;

  /** Shortest token accepted: 32 characters, e.g. 16 random bytes in hexadecimal. */
  public static final int MIN_TOKEN_LENGTH = 32;

  /** Longest token accepted, which also bounds the Authorization header the server compares. */
  public static final int MAX_TOKEN_LENGTH = 512;

  /** The b64token syntax of RFC 6750, so the token travels unquoted in an Authorization header. */
  private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9._~+/-]+=*");

  public EngineHttpServerConfig {
    if (host == null || host.isBlank()) {
      throw new IllegalArgumentException("HTTP host must not be blank.");
    }
    if (port < 0 || port > 65535) {
      throw new IllegalArgumentException("HTTP port must be between 0 and 65535.");
    }
    if (backlog < 0) {
      throw new IllegalArgumentException("HTTP backlog must not be negative.");
    }
    if (maxConcurrentRuns < 1) {
      throw new IllegalArgumentException("HTTP maxConcurrentRuns must be at least 1.");
    }
    if (apiToken == null) {
      // A null here must never mean "no authentication".
      throw new IllegalArgumentException("API token must be an Optional, not null.");
    }
    if (apiToken.isPresent() && !isValidToken(apiToken.get())) {
      // Names the rule, never the rejected value.
      throw new IllegalArgumentException(
          "API token must be "
              + MIN_TOKEN_LENGTH
              + " to "
              + MAX_TOKEN_LENGTH
              + " characters from A-Z a-z 0-9 - . _ ~ + / with optional trailing =.");
    }
  }

  /** Loopback on a port the operating system picks; read it back from the started server. */
  public static EngineHttpServerConfig localEphemeral() {
    return of("127.0.0.1", 0);
  }

  /**
   * Binds the given host explicitly, with the system's default backlog, the default run limit and
   * no token.
   */
  public static EngineHttpServerConfig of(String host, int port) {
    return new EngineHttpServerConfig(host, port, 0, DEFAULT_MAX_CONCURRENT_RUNS, Optional.empty());
  }

  /**
   * The same configuration requiring {@code token} as the bearer token.
   *
   * @throws IllegalArgumentException when the token is null, too short or long, or not a b64token
   */
  public EngineHttpServerConfig withApiToken(String token) {
    // A null token fails the syntax check instead of quietly meaning "no authentication".
    return new EngineHttpServerConfig(
        host, port, backlog, maxConcurrentRuns, Optional.of(token == null ? "" : token));
  }

  /** The same configuration running at most {@code limit} jobs at one time. */
  public EngineHttpServerConfig withMaxConcurrentRuns(int limit) {
    return new EngineHttpServerConfig(host, port, backlog, limit, apiToken);
  }

  /** Never prints the token: configurations end up in logs and exception messages. */
  @Override
  public String toString() {
    return "EngineHttpServerConfig[host="
        + host
        + ", port="
        + port
        + ", backlog="
        + backlog
        + ", maxConcurrentRuns="
        + maxConcurrentRuns
        + ", apiToken="
        + (apiToken.isPresent() ? "<redacted>" : "<none>")
        + "]";
  }

  private static boolean isValidToken(String token) {
    return token != null
        && token.length() >= MIN_TOKEN_LENGTH
        && token.length() <= MAX_TOKEN_LENGTH
        && TOKEN.matcher(token).matches();
  }
}
