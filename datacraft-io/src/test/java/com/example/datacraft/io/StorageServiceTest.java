package com.example.datacraft.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageServiceTest {

  @TempDir Path tempDir;

  @Test
  void refusesSymlinkTraversalOutsideTheStorageRoot() throws Exception {
    Path outside = Files.createDirectory(tempDir.resolve("outside"));
    Files.writeString(outside.resolve("secret.txt"), "preserve");
    Path root = Files.createDirectory(tempDir.resolve("root"));
    createSymbolicLinkOrAbortOnWindows(root.resolve("escape"), outside);
    StorageService storage = LocalStorageService.at(root);
    assertThrows(
        IllegalArgumentException.class, () -> storage.readUtf8(Path.of("escape/secret.txt")));
    assertThrows(
        IllegalArgumentException.class, () -> storage.writeUtf8(Path.of("escape/new.txt"), "bad"));
    assertFalse(Files.exists(outside.resolve("new.txt")));
  }

  /**
   * Windows creates symbolic links only with Developer Mode or elevation. That missing precondition
   * aborts the test there; on every other OS a failure still fails, so Linux CI always runs it.
   */
  static void createSymbolicLinkOrAbortOnWindows(Path link, Path target) throws IOException {
    try {
      Files.createSymbolicLink(link, target);
    } catch (FileSystemException | UnsupportedOperationException exception) {
      if (!System.getProperty("os.name", "").startsWith("Windows")) {
        throw exception;
      }
      Assumptions.abort("Symbolic links need Developer Mode or elevation on Windows: " + exception);
    }
  }

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
