package com.example.datacraft.common;

import java.util.Locale;

public enum Lifecycle {
  DEV("development"),
  PROD("production");

  private final String fullName;

  Lifecycle(String fullName) {
    this.fullName = fullName;
  }

  public String fullName() {
    return fullName;
  }

  public static Lifecycle fromName(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Lifecycle name must not be blank.");
    }

    String normalized = value.trim().toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
    return switch (normalized) {
      case "dev", "development" -> DEV;
      case "prod", "production" -> PROD;
      default -> throw new IllegalArgumentException("Unsupported lifecycle: " + value);
    };
  }
}
