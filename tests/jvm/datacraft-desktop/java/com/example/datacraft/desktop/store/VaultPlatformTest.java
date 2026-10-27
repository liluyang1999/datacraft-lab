package com.example.datacraft.desktop.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.example.datacraft.desktop.app.AppPaths;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The vault each platform uses; tagged so each OS runs its own test and none is skipped. */
class VaultPlatformTest {

  @TempDir Path home;

  @Test
  @Tag("windows-only")
  void windowsSealsWithDpapiForTheCurrentUser() {
    SecretVault vault = SecretVault.forPlatform(new AppPaths(home));
    assertInstanceOf(DpapiVault.class, vault);
    byte[] plain = "令牌 token-ä".getBytes(StandardCharsets.UTF_8);
    byte[] sealed = vault.seal(plain);
    assertFalse(new String(sealed, StandardCharsets.ISO_8859_1).contains("token"));
    assertEquals("令牌 token-ä", new String(vault.open(sealed), StandardCharsets.UTF_8));
    assertFalse(Files.exists(home.resolve("vault.key")), "DPAPI needs no key file");
  }

  @Test
  @Tag("posix-only")
  void otherSystemsSealWithAKeyFileOnlyTheUserCanRead() throws Exception {
    SecretVault vault = SecretVault.forPlatform(new AppPaths(home));
    assertInstanceOf(FileKeyVault.class, vault);
    byte[] sealed = vault.seal("secret".getBytes(StandardCharsets.UTF_8));
    assertEquals("secret", new String(vault.open(sealed), StandardCharsets.UTF_8));
    assertEquals(
        "rw-------",
        PosixFilePermissions.toString(Files.getPosixFilePermissions(home.resolve("vault.key"))));
  }
}
