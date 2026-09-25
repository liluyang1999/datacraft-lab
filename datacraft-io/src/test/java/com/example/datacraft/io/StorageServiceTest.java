package com.example.datacraft.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class StorageServiceTest {

  @TempDir Path tempDir;

  @Test
  void refusesSymlinkTraversalOutsideTheStorageRoot() throws Exception {
    Path outside = Files.createDirectory(tempDir.resolve("outside"));
    Files.writeString(outside.resolve("secret.txt"), "preserve");
    Path root = Files.createDirectory(tempDir.resolve("root"));
    TestLinks.createSymbolicLinkOrAbortOnWindows(root.resolve("escape"), outside);
    StorageService storage = LocalStorageService.at(root);
    assertThrows(
        IllegalArgumentException.class, () -> storage.readUtf8(Path.of("escape/secret.txt")));
    assertThrows(
        IllegalArgumentException.class, () -> storage.writeUtf8(Path.of("escape/new.txt"), "bad"));
    assertFalse(Files.exists(outside.resolve("new.txt")));
  }

  @Test
  void listingsOmitSymbolicLinksThatOtherOperationsRefuse() throws Exception {
    Path outside = Files.createDirectory(tempDir.resolve("outside"));
    Path secret = Files.writeString(outside.resolve("secret.txt"), "preserve");
    Path root = Files.createDirectory(tempDir.resolve("root"));
    StorageService storage = LocalStorageService.at(root);
    storage.writeUtf8(Path.of("data/a.txt"), "a");
    TestLinks.createSymbolicLinkOrAbortOnWindows(root.resolve("data/link.txt"), secret);

    assertEquals(List.of(Path.of("data/a.txt")), storage.listRegularFiles(Path.of("data")));
    assertEquals(List.of(Path.of("data/a.txt")), storage.listRegularFilesRecursively(Path.of("")));
    Path link = Path.of("data/link.txt");
    assertThrows(IllegalArgumentException.class, () -> storage.exists(link));
    assertThrows(IllegalArgumentException.class, () -> storage.size(link));
    assertThrows(IllegalArgumentException.class, () -> storage.sha256Hex(link));
    assertThrows(IllegalArgumentException.class, () -> storage.copy(link, Path.of("copy.txt")));
    assertFalse(Files.exists(root.resolve("copy.txt")));
    assertEquals("preserve", Files.readString(secret));
  }

  @Test
  void refusesTheRootItselfAsAFileOperand() {
    StorageService storage = LocalStorageService.at(tempDir);
    storage.writeUtf8(Path.of("f.txt"), "keep");

    for (Path rootAlias : List.of(Path.of(""), Path.of("."), Path.of("a/.."))) {
      assertThrows(IllegalArgumentException.class, () -> storage.delete(rootAlias));
      assertThrows(IllegalArgumentException.class, () -> storage.move(rootAlias, Path.of("x")));
      assertThrows(IllegalArgumentException.class, () -> storage.copy(rootAlias, Path.of("x")));
      assertThrows(IllegalArgumentException.class, () -> storage.move(Path.of("f.txt"), rootAlias));
      assertThrows(IllegalArgumentException.class, () -> storage.copy(Path.of("f.txt"), rootAlias));
      assertThrows(IllegalArgumentException.class, () -> storage.writeUtf8(rootAlias, "x"));
      assertThrows(
          IllegalArgumentException.class, () -> storage.writeBytes(rootAlias, new byte[1]));
      assertThrows(IllegalArgumentException.class, () -> storage.readUtf8(rootAlias));
      assertThrows(IllegalArgumentException.class, () -> storage.readBytes(rootAlias));
      assertThrows(IllegalArgumentException.class, () -> storage.size(rootAlias));
      assertThrows(IllegalArgumentException.class, () -> storage.sha256Hex(rootAlias));
    }

    assertTrue(Files.isDirectory(tempDir));
    assertFalse(Files.exists(tempDir.resolve("x")));
    assertTrue(storage.exists(Path.of("")));
    assertEquals(List.of(Path.of("f.txt")), storage.listRegularFiles(Path.of("")));
    assertEquals(List.of(Path.of("f.txt")), storage.listRegularFilesRecursively(Path.of(".")));
    assertEquals("keep", storage.readUtf8(Path.of("f.txt")));
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void refusesJunctionTraversalOutsideTheStorageRoot() throws Exception {
    Path outside = Files.createDirectory(tempDir.resolve("outside"));
    Files.writeString(outside.resolve("secret.txt"), "preserve");
    Path root = Files.createDirectory(tempDir.resolve("root"));
    Path junction = TestLinks.createJunction(root.resolve("escape"), outside);
    try {
      StorageService storage = LocalStorageService.at(root);
      Path secret = Path.of("escape/secret.txt");

      assertThrows(IllegalArgumentException.class, () -> storage.readUtf8(secret));
      assertThrows(IllegalArgumentException.class, () -> storage.delete(secret));
      assertThrows(
          IllegalArgumentException.class,
          () -> storage.writeUtf8(Path.of("escape/new.txt"), "bad"));
      assertThrows(
          IllegalArgumentException.class, () -> storage.listRegularFiles(Path.of("escape")));
      assertThrows(
          IllegalArgumentException.class,
          () -> storage.listRegularFilesRecursively(Path.of("escape")));
      assertEquals(List.of(), storage.listRegularFilesRecursively(Path.of("")));
      assertFalse(Files.exists(outside.resolve("new.txt")));
      assertEquals("preserve", Files.readString(outside.resolve("secret.txt")));
    } finally {
      Files.deleteIfExists(junction);
    }
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void recursiveListingDoesNotFollowJunctionLoops() throws Exception {
    Path root = Files.createDirectory(tempDir.resolve("root"));
    Files.writeString(root.resolve("a.txt"), "a");
    Path loop = TestLinks.createJunction(root.resolve("loop"), root);
    try {
      StorageService storage = LocalStorageService.at(root);

      assertEquals(List.of(Path.of("a.txt")), storage.listRegularFilesRecursively(Path.of("")));
      // A junction whose real location stays inside the root is not an escape.
      assertEquals("a", storage.readUtf8(Path.of("loop/a.txt")));
    } finally {
      Files.deleteIfExists(loop);
    }
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void recursiveListingSkipsJunctionsWhoseTargetIsGone() throws Exception {
    Path gone = Files.createDirectory(tempDir.resolve("gone"));
    Path root = Files.createDirectory(tempDir.resolve("root"));
    Files.writeString(root.resolve("a.txt"), "a");
    Files.createDirectory(root.resolve("z"));
    Files.writeString(root.resolve("z/b.txt"), "b");
    Path dangling = TestLinks.createJunction(root.resolve("dangling"), gone);
    Files.delete(gone);
    try {
      StorageService storage = LocalStorageService.at(root);

      assertEquals(
          List.of(Path.of("a.txt"), Path.of("z/b.txt")),
          storage.listRegularFilesRecursively(Path.of("")));
      assertEquals(List.of(Path.of("a.txt")), storage.listRegularFiles(Path.of("")));
    } finally {
      Files.deleteIfExists(dangling);
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
