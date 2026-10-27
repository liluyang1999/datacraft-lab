package com.example.datacraft.desktop.store;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM with a random key in {@code vault.key}, created readable by the current user only.
 * Protects the secrets against other accounts on the machine, not against the user's own account,
 * which is the same boundary as a desktop keyring without a password prompt.
 */
final class FileKeyVault implements SecretVault {

  private static final byte VERSION = 1;
  private static final int IV_BYTES = 12;
  private static final int TAG_BITS = 128;

  private final Path keyFile;
  private final SecureRandom random = new SecureRandom();
  private SecretKeySpec key;

  FileKeyVault(Path keyFile) {
    this.keyFile = keyFile;
  }

  @Override
  public synchronized byte[] seal(byte[] plain) {
    try {
      byte[] iv = new byte[IV_BYTES];
      random.nextBytes(iv);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
      byte[] encrypted = cipher.doFinal(plain);
      return ByteBuffer.allocate(1 + IV_BYTES + encrypted.length)
          .put(VERSION)
          .put(iv)
          .put(encrypted)
          .array();
    } catch (GeneralSecurityException | IOException exception) {
      throw new SecretVault.VaultException("Cannot seal the secret", exception);
    }
  }

  @Override
  public synchronized byte[] open(byte[] sealed) {
    if (sealed.length < 1 + IV_BYTES || sealed[0] != VERSION) {
      throw new SecretVault.VaultException("The sealed secret has an unknown format", null);
    }
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, sealed, 1, IV_BYTES));
      return cipher.doFinal(sealed, 1 + IV_BYTES, sealed.length - 1 - IV_BYTES);
    } catch (GeneralSecurityException | IOException exception) {
      throw new SecretVault.VaultException(
          "Cannot open the secret; was vault.key replaced?", exception);
    }
  }

  @Override
  public String description() {
    return "AES-GCM key file";
  }

  private SecretKeySpec key() throws IOException {
    if (key == null) {
      if (!Files.exists(keyFile)) {
        create();
      }
      byte[] bytes = Files.readAllBytes(keyFile);
      if (bytes.length != 32) {
        throw new IOException(keyFile + " does not hold a 256-bit key");
      }
      key = new SecretKeySpec(bytes, "AES");
    }
    return key;
  }

  private void create() throws IOException {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    Files.createDirectories(keyFile.getParent());
    try {
      if (keyFile.getFileSystem().supportedFileAttributeViews().contains("posix")) {
        Files.createFile(
            keyFile,
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
      } else {
        Files.createFile(keyFile);
      }
      Files.write(keyFile, bytes, StandardOpenOption.TRUNCATE_EXISTING);
    } catch (FileAlreadyExistsException raced) {
      // Another instance created it first; read that one.
    }
  }
}
