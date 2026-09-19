package com.example.datacraft.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SftpConfigTest {

  @Test
  void validatesTheMillisecondTimeoutRangeAndRedactsPasswords() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SftpConfig(
                "host", 22, "user", "sensitive-test-value", null, true, Duration.ofNanos(1)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SftpConfig(
                "host", 22, "user", "sensitive-test-value", null, true, Duration.ofDays(100)));
    assertFalse(
        SftpConfig.passwordAuth("host", "user", "sensitive-test-value")
            .toString()
            .contains("sensitive-test-value"));
  }

  @Test
  void buildsPasswordConfigurationWithSafeDefaults() {
    SftpConfig config = SftpConfig.passwordAuth("example.com", "data-user", "secret");

    assertEquals("example.com", config.host());
    assertEquals(22, config.port());
    assertEquals("data-user", config.username());
    assertEquals("secret", config.password());
    assertFalse(config.hasPrivateKey());
    assertEquals(Duration.ofSeconds(30), config.timeout());
  }

  @Test
  void rejectsInvalidHostPortAndUsername() {
    assertThrows(
        IllegalArgumentException.class, () -> SftpConfig.passwordAuth("", "user", "secret"));
    assertThrows(
        IllegalArgumentException.class, () -> SftpConfig.passwordAuth("example.com", "", "secret"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SftpConfig("example.com", 0, "user", "secret", null, true, Duration.ofSeconds(1)));
  }

  @Test
  void buildsConfigurationFromPropertyMap() {
    SftpConfig config =
        SftpConfig.fromProperties(
            Map.of(
                "host", "sftp.example.com",
                "port", "2222",
                "username", "data-user",
                "password", "secret",
                "strictHostKeyChecking", "false",
                "timeoutSeconds", "45"));

    assertEquals("sftp.example.com", config.host());
    assertEquals(2222, config.port());
    assertEquals("data-user", config.username());
    assertFalse(config.strictHostKeyChecking());
    assertEquals(Duration.ofSeconds(45), config.timeout());
  }

  @Test
  void appliesSafeDefaultsForMissingOptionalProperties() {
    SftpConfig config =
        SftpConfig.fromProperties(Map.of("host", "host", "username", "user", "password", "secret"));

    assertEquals(22, config.port());
    assertTrue(config.strictHostKeyChecking());
    assertEquals(Duration.ofSeconds(30), config.timeout());
  }
}
