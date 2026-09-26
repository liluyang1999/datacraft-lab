package com.example.datacraft.api;

public record EngineHttpServerConfig(String host, int port, int backlog) {

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
  }

  public static EngineHttpServerConfig localEphemeral() {
    return new EngineHttpServerConfig("127.0.0.1", 0, 0);
  }

  public static EngineHttpServerConfig local(int port) {
    return new EngineHttpServerConfig("127.0.0.1", port, 0);
  }

  /** Binds all interfaces (0.0.0.0); required when serving from inside a container. */
  public static EngineHttpServerConfig onAllInterfaces(int port) {
    return new EngineHttpServerConfig("0.0.0.0", port, 0);
  }

  /** Binds the given host explicitly. */
  public static EngineHttpServerConfig of(String host, int port) {
    return new EngineHttpServerConfig(host, port, 0);
  }
}
