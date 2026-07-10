package com.example.datacraft.io;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
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
}
