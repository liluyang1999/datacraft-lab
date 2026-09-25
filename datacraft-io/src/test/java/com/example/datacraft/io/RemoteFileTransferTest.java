package com.example.datacraft.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.datacraft.common.DataCraftException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the default, atomic {@link RemoteFileTransfer#download(String, Path)}. */
class RemoteFileTransferTest {

  @TempDir Path directory;

  @Test
  void failedDownloadKeepsTheExistingFileAndLeavesNoPartialFile() throws IOException {
    Path target = Files.writeString(directory.resolve("daily.csv"), "good\n");

    assertThrows(
        DataCraftException.class,
        () -> new FakeTransfer(new byte[1_000], true).download("/r/daily.csv", target));

    assertEquals("good\n", Files.readString(target));
    assertEquals(List.of("daily.csv"), entries());
  }

  @Test
  void failedDownloadOfANewFileLeavesNothingBehind() throws IOException {
    Path target = directory.resolve("nested").resolve("daily.csv");

    assertThrows(
        DataCraftException.class,
        () -> new FakeTransfer(new byte[1_000], true).download("/r/daily.csv", target));

    assertFalse(Files.exists(target));
    try (Stream<Path> nested = Files.list(target.getParent())) {
      assertEquals(0, nested.count());
    }
  }

  @Test
  void successfulDownloadReplacesTheExistingFile() throws IOException {
    Path target = Files.writeString(directory.resolve("daily.csv"), "good\n");

    new FakeTransfer("new\n".getBytes(StandardCharsets.UTF_8), false)
        .download("/r/daily.csv", target);

    assertEquals("new\n", Files.readString(target));
    assertEquals(List.of("daily.csv"), entries());
  }

  @Test
  void directoryTargetIsRejected() throws IOException {
    Path target = Files.createDirectory(directory.resolve("daily.csv"));

    assertThrows(
        DataCraftException.class,
        () -> new FakeTransfer(new byte[1], false).download("/r/daily.csv", target));

    assertTrue(Files.isDirectory(target));
    try (Stream<Path> children = Files.list(target)) {
      assertEquals(0, children.count());
    }
    assertEquals(List.of("daily.csv"), entries());
  }

  private List<String> entries() {
    try (Stream<Path> paths = Files.list(directory)) {
      return paths.map(path -> path.getFileName().toString()).sorted().toList();
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  /** Implements only the streaming download; the file overload under test is the default. */
  private static final class FakeTransfer implements RemoteFileTransfer {

    private final byte[] content;
    private final boolean failAfterWrite;

    FakeTransfer(byte[] content, boolean failAfterWrite) {
      this.content = content;
      this.failAfterWrite = failAfterWrite;
    }

    @Override
    public void download(String remotePath, OutputStream target) {
      try {
        target.write(content);
      } catch (IOException exception) {
        throw new UncheckedIOException(exception);
      }
      if (failAfterWrite) {
        throw new DataCraftException("simulated transfer failure: " + remotePath);
      }
    }

    @Override
    public void upload(Path localPath, String remotePath) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void upload(InputStream source, String remotePath) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean exists(String remotePath) {
      throw new UnsupportedOperationException();
    }

    @Override
    public long size(String remotePath) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<String> list(String remoteDirectory) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void makeDirectories(String remoteDirectory) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void delete(String remotePath) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void rename(String from, String to) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void close() {}
  }
}
