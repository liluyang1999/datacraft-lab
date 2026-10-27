package com.example.datacraft.desktop.store;

import com.example.datacraft.desktop.app.AppPaths;
import java.util.Locale;

/**
 * Encrypts the secrets of a connection profile (the engine API token, the Airflow password) before
 * they reach the database, and decrypts them for use. On Windows the data is protected with DPAPI
 * for the current user; elsewhere with AES-GCM and a key file only the current user can read.
 */
public interface SecretVault {

  byte[] seal(byte[] plain);

  byte[] open(byte[] sealed);

  /** A short name for the settings screen, e.g. "Windows DPAPI". */
  String description();

  static SecretVault forPlatform(AppPaths paths) {
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    return os.startsWith("windows") ? new DpapiVault() : new FileKeyVault(paths.vaultKey());
  }

  /** Thrown when a secret cannot be sealed or opened, e.g. after the key file was replaced. */
  final class VaultException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public VaultException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
