package com.example.datacraft.io;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

public record SftpConfig(
    String host,
    int port,
    String username,
    String password,
    Path privateKey,
    boolean strictHostKeyChecking,
    Duration timeout) {

  private static final int DEFAULT_PORT = 22;
  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

  public SftpConfig {
    if (host == null || host.isBlank()) {
      throw new IllegalArgumentException("SFTP host must not be blank.");
    }
    if (port < 1 || port > 65535) {
      throw new IllegalArgumentException("SFTP port must be between 1 and 65535.");
    }
    if (username == null || username.isBlank()) {
      throw new IllegalArgumentException("SFTP username must not be blank.");
    }
    if ((password == null || password.isBlank()) && privateKey == null) {
      throw new IllegalArgumentException("SFTP password or private key must be provided.");
    }
    if (timeout == null
        || timeout.compareTo(Duration.ofMillis(1)) < 0
        || timeout.compareTo(Duration.ofMillis(Integer.MAX_VALUE)) > 0) {
      throw new IllegalArgumentException(
          "SFTP timeout must be between 1 and 2147483647 milliseconds.");
    }
  }

  public static SftpConfig passwordAuth(String host, String username, String password) {
    return new SftpConfig(host, DEFAULT_PORT, username, password, null, true, DEFAULT_TIMEOUT);
  }

  public static SftpConfig privateKeyAuth(String host, String username, Path privateKey) {
    return new SftpConfig(host, DEFAULT_PORT, username, null, privateKey, true, DEFAULT_TIMEOUT);
  }

  /**
   * Builds a configuration from a flat property map. Recognised keys: {@code host}, {@code port},
   * {@code username}, {@code password}, {@code privateKey}, {@code strictHostKeyChecking}, {@code
   * timeoutSeconds}. Intended to be fed from {@code DataCraftConfig.withPrefix("sftp.").asMap()}.
   */
  public static SftpConfig fromProperties(Map<String, String> properties) {
    if (properties == null) {
      throw new IllegalArgumentException("SFTP properties must not be null.");
    }
    String privateKeyPath = properties.get("privateKey");
    Path privateKey =
        (privateKeyPath == null || privateKeyPath.isBlank())
            ? null
            : Path.of(privateKeyPath.trim());
    boolean strict =
        !"false".equalsIgnoreCase(properties.getOrDefault("strictHostKeyChecking", "true").trim());
    return new SftpConfig(
        trimmed(properties.get("host")),
        parseInt(properties.get("port"), DEFAULT_PORT),
        trimmed(properties.get("username")),
        properties.get("password"),
        privateKey,
        strict,
        Duration.ofSeconds(
            parseLong(properties.get("timeoutSeconds"), DEFAULT_TIMEOUT.toSeconds())));
  }

  public boolean hasPrivateKey() {
    return privateKey != null;
  }

  @Override
  public String toString() {
    return "SftpConfig[host="
        + host
        + ", port="
        + port
        + ", username="
        + username
        + ", password=<redacted>, hasPrivateKey="
        + hasPrivateKey()
        + ", strictHostKeyChecking="
        + strictHostKeyChecking
        + ", timeout="
        + timeout
        + "]";
  }

  /** Config values arrive verbatim; host and user names never carry meaningful outer spaces. */
  private static String trimmed(String value) {
    return value == null ? null : value.trim();
  }

  private static int parseInt(String value, int defaultValue) {
    if (value == null || value.isBlank()) {
      return defaultValue;
    }
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("SFTP numeric property is not an integer: " + value);
    }
  }

  private static long parseLong(String value, long defaultValue) {
    if (value == null || value.isBlank()) {
      return defaultValue;
    }
    try {
      return Long.parseLong(value.trim());
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("SFTP numeric property is not a long: " + value);
    }
  }
}
