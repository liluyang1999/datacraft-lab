package com.example.datacraft.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageServiceTest {

  @TempDir Path tempDir;

  @Test
  void localStorageServiceAnchorsOperationsUnderRootDirectory() {
    StorageService storage = LocalStorageService.at(tempDir);

    storage.writeUtf8(Path.of("bronze/input.txt"), "datacraft");
    storage.copy(Path.of("bronze/input.txt"), Path.of("silver/copy.txt"));

    assertEquals("datacraft", storage.readUtf8(Path.of("silver/copy.txt")));
    assertEquals(
        "daa501f37955ee127679730f5a68588e36ed357b34448ed6e244a80a2bf2da4b",
        storage.sha256Hex(Path.of("silver/copy.txt")));
    assertEquals(
        List.of(Path.of("bronze/input.txt"), Path.of("silver/copy.txt")),
        storage.listRegularFilesRecursively(Path.of("")));
    assertTrue(Files.exists(tempDir.resolve("silver/copy.txt")));
  }

  @Test
  void localStorageServiceSupportsMoveExistenceSizeAndDelete() {
    StorageService storage = LocalStorageService.at(tempDir);
    storage.writeUtf8(Path.of("a/in.txt"), "datacraft");

    assertEquals(9L, storage.size(Path.of("a/in.txt")));
    assertTrue(storage.exists(Path.of("a/in.txt")));

    storage.move(Path.of("a/in.txt"), Path.of("b/out.txt"));

    assertFalse(storage.exists(Path.of("a/in.txt")));
    assertTrue(storage.exists(Path.of("b/out.txt")));
    assertTrue(storage.delete(Path.of("b/out.txt")));
    assertFalse(storage.exists(Path.of("b/out.txt")));
  }

  @Test
  void localStorageServiceRejectsAbsoluteAndEscapingPaths() {
    StorageService storage = LocalStorageService.at(tempDir);

    assertThrows(
        IllegalArgumentException.class,
        () -> storage.writeUtf8(tempDir.resolve("absolute.txt"), "outside"));
    assertThrows(IllegalArgumentException.class, () -> storage.readUtf8(Path.of("../outside.txt")));
  }
}
