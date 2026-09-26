package com.example.datacraft.io;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class LocalFilesTest {

  @TempDir Path tempDir;

  @Test
  void writesAndReadsUtf8TextCreatingParentDirectories() {
    Path target = tempDir.resolve("nested").resolve("sample.txt");

    LocalFiles.writeUtf8String(target, "hello datacraft");

    assertTrue(Files.exists(target));
    assertEquals("hello datacraft", LocalFiles.readUtf8String(target));
  }

  @Test
  void writesAndReadsBytesCreatingParentDirectories() {
    Path target = tempDir.resolve("binary").resolve("sample.bin");
    byte[] payload = new byte[] {1, 2, 3, 5, 8};

    LocalFiles.writeBytes(target, payload);

    assertArrayEquals(payload, LocalFiles.readBytes(target));
  }

  @Test
  void listsRegularFilesInStableOrder() {
    LocalFiles.writeUtf8String(tempDir.resolve("b.txt"), "b");
    LocalFiles.writeUtf8String(tempDir.resolve("a.txt"), "a");
    LocalFiles.ensureDirectory(tempDir.resolve("subdir"));

    List<Path> files = LocalFiles.listRegularFiles(tempDir);

    assertEquals(List.of(tempDir.resolve("a.txt"), tempDir.resolve("b.txt")), files);
  }

  @Test
  void copiesFilesCreatingParentDirectoriesAndComputesSha256() {
    Path source = tempDir.resolve("source.txt");
    Path target = tempDir.resolve("copy").resolve("target.txt");
    LocalFiles.writeUtf8String(source, "datacraft");

    LocalFiles.copy(source, target);

    assertEquals("datacraft", LocalFiles.readUtf8String(target));
    assertEquals(
        "daa501f37955ee127679730f5a68588e36ed357b34448ed6e244a80a2bf2da4b",
        LocalFiles.sha256Hex(target));
  }

  @Test
  void listsRegularFilesRecursivelyInStableOrder() {
    LocalFiles.writeUtf8String(tempDir.resolve("b.txt"), "b");
    LocalFiles.writeUtf8String(tempDir.resolve("nested").resolve("a.txt"), "a");

    List<Path> files = LocalFiles.listRegularFilesRecursively(tempDir);

    assertEquals(
        List.of(tempDir.resolve("b.txt"), tempDir.resolve("nested").resolve("a.txt")), files);
  }

  @Test
  void movesDeletesAndReportsExistenceAndSize() {
    Path source = tempDir.resolve("src.txt");
    Path moved = tempDir.resolve("dir").resolve("moved.txt");
    LocalFiles.writeUtf8String(source, "datacraft");

    assertEquals(9L, LocalFiles.size(source));
    LocalFiles.move(source, moved);

    assertFalse(LocalFiles.exists(source));
    assertTrue(LocalFiles.exists(moved));
    assertEquals("datacraft", LocalFiles.readUtf8String(moved));
    assertTrue(LocalFiles.delete(moved));
    assertFalse(LocalFiles.delete(moved));
  }

  @Test
  void writesAppendsAndReadsLines() {
    Path file = tempDir.resolve("log.txt");

    LocalFiles.writeUtf8Lines(file, List.of("a", "b"));
    LocalFiles.appendUtf8String(file, "c\n");

    assertEquals(List.of("a", "b", "c"), LocalFiles.readUtf8Lines(file));
  }

  @Test
  void deletesDirectoryTreesRecursively() {
    LocalFiles.writeUtf8String(tempDir.resolve("tree").resolve("a.txt"), "a");
    LocalFiles.writeUtf8String(tempDir.resolve("tree").resolve("sub").resolve("b.txt"), "b");

    LocalFiles.deleteRecursively(tempDir.resolve("tree"));

    assertFalse(LocalFiles.exists(tempDir.resolve("tree")));
  }

  @Test
  @Tag("posix-only")
  void deleteRecursivelyRemovesSymbolicLinksWithoutFollowingThem() throws Exception {
    Path outside = tempDir.resolve("outside");
    LocalFiles.writeUtf8String(outside.resolve("keep.txt"), "keep");
    Path tree = tempDir.resolve("tree");
    LocalFiles.writeUtf8String(tree.resolve("a.txt"), "a");
    TestLinks.createSymbolicLinkOrAbortOnWindows(tree.resolve("dir-link"), outside);
    TestLinks.createSymbolicLinkOrAbortOnWindows(
        tree.resolve("file-link"), outside.resolve("keep.txt"));
    Path startLink =
        TestLinks.createSymbolicLinkOrAbortOnWindows(tempDir.resolve("start"), outside);

    LocalFiles.deleteRecursively(tree);
    LocalFiles.deleteRecursively(startLink);

    assertFalse(Files.exists(tree, LinkOption.NOFOLLOW_LINKS));
    assertFalse(Files.exists(startLink, LinkOption.NOFOLLOW_LINKS));
    assertEquals("keep", Files.readString(outside.resolve("keep.txt")));
  }

  @Test
  @Tag("windows-only")
  @EnabledOnOs(OS.WINDOWS)
  void deleteRecursivelyRemovesJunctionsWithoutDeletingTheirTargets() throws Exception {
    Path outside = tempDir.resolve("outside");
    LocalFiles.writeUtf8String(outside.resolve("keep.txt"), "keep");
    LocalFiles.writeUtf8String(outside.resolve("nested").resolve("deep.txt"), "deep");
    Path tree = tempDir.resolve("tree");
    LocalFiles.writeUtf8String(tree.resolve("a.txt"), "a");
    LocalFiles.ensureDirectory(tree.resolve("sub"));
    Path nested = TestLinks.createJunction(tree.resolve("sub").resolve("junction"), outside);
    Path start = TestLinks.createJunction(tempDir.resolve("start"), outside);
    try {
      LocalFiles.deleteRecursively(tree);
      LocalFiles.deleteRecursively(start);

      assertFalse(Files.exists(tree, LinkOption.NOFOLLOW_LINKS));
      assertFalse(Files.exists(start, LinkOption.NOFOLLOW_LINKS));
      assertEquals("keep", Files.readString(outside.resolve("keep.txt")));
      assertEquals("deep", Files.readString(outside.resolve("nested").resolve("deep.txt")));
    } finally {
      Files.deleteIfExists(nested);
      Files.deleteIfExists(start);
    }
  }

  @Test
  @Tag("windows-only")
  @EnabledOnOs(OS.WINDOWS)
  void deleteRecursivelyRemovesAJunctionToAnAncestor() throws Exception {
    Path tree = tempDir.resolve("tree");
    LocalFiles.writeUtf8String(tree.resolve("a.txt"), "a");
    Path loop = TestLinks.createJunction(tree.resolve("loop"), tree);
    try {
      LocalFiles.deleteRecursively(tree);

      assertFalse(Files.exists(tree, LinkOption.NOFOLLOW_LINKS));
    } finally {
      Files.deleteIfExists(loop);
    }
  }

  @Test
  @Tag("windows-only")
  @EnabledOnOs(OS.WINDOWS)
  void deleteRecursivelyRemovesJunctionsWhoseTargetIsGone() throws Exception {
    Path gone = Files.createDirectory(tempDir.resolve("gone"));
    Path tree = tempDir.resolve("tree");
    LocalFiles.writeUtf8String(tree.resolve("a.txt"), "a");
    LocalFiles.writeUtf8String(tree.resolve("z").resolve("b.txt"), "b");
    Path nested = TestLinks.createJunction(tree.resolve("dangling"), gone);
    Path start = TestLinks.createJunction(tempDir.resolve("start"), gone);
    Files.delete(gone);
    try {
      assertTrue(Files.exists(start, LinkOption.NOFOLLOW_LINKS));

      LocalFiles.deleteRecursively(tree);
      LocalFiles.deleteRecursively(start);

      assertFalse(Files.exists(tree, LinkOption.NOFOLLOW_LINKS));
      assertFalse(Files.exists(start, LinkOption.NOFOLLOW_LINKS));
    } finally {
      Files.deleteIfExists(nested);
      Files.deleteIfExists(start);
    }
  }
}
