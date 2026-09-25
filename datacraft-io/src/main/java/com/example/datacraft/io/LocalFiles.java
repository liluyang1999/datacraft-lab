package com.example.datacraft.io;

import com.example.datacraft.common.DataCraftException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

public final class LocalFiles {

  private LocalFiles() {}

  public static void ensureDirectory(Path directory) {
    try {
      Files.createDirectories(directory);
    } catch (IOException exception) {
      throw new DataCraftException("Failed to create directory: " + directory, exception);
    }
  }

  public static String readUtf8String(Path file) {
    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new DataCraftException("Failed to read UTF-8 file: " + file, exception);
    }
  }

  public static void writeUtf8String(Path file, String content) {
    ensureParentDirectory(file);
    try {
      Files.writeString(file, content, StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new DataCraftException("Failed to write UTF-8 file: " + file, exception);
    }
  }

  public static byte[] readBytes(Path file) {
    try {
      return Files.readAllBytes(file);
    } catch (IOException exception) {
      throw new DataCraftException("Failed to read binary file: " + file, exception);
    }
  }

  public static void writeBytes(Path file, byte[] content) {
    ensureParentDirectory(file);
    try {
      Files.write(file, content);
    } catch (IOException exception) {
      throw new DataCraftException("Failed to write binary file: " + file, exception);
    }
  }

  public static List<Path> listRegularFiles(Path directory) {
    try (Stream<Path> paths = Files.list(directory)) {
      return paths.filter(Files::isRegularFile).sorted(Comparator.naturalOrder()).toList();
    } catch (IOException exception) {
      throw new DataCraftException(
          "Failed to list regular files in directory: " + directory, exception);
    }
  }

  public static List<Path> listRegularFilesRecursively(Path directory) {
    try (Stream<Path> paths = Files.walk(directory)) {
      return paths.filter(Files::isRegularFile).sorted(Comparator.naturalOrder()).toList();
    } catch (IOException exception) {
      throw new DataCraftException(
          "Failed to recursively list regular files in directory: " + directory, exception);
    }
  }

  public static void copy(Path source, Path target) {
    ensureParentDirectory(target);
    try {
      Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException exception) {
      throw new DataCraftException(
          "Failed to copy file from " + source + " to " + target, exception);
    }
  }

  public static String sha256Hex(Path file) {
    try (InputStream input = Files.newInputStream(file)) {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] buffer = new byte[64 * 1024];
      int size;
      while ((size = input.read(buffer)) != -1) {
        digest.update(buffer, 0, size);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (IOException exception) {
      throw new DataCraftException("Failed to hash file: " + file, exception);
    } catch (NoSuchAlgorithmException exception) {
      throw new DataCraftException("SHA-256 digest algorithm is unavailable.", exception);
    }
  }

  public static List<String> readUtf8Lines(Path file) {
    try {
      return Files.readAllLines(file, StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new DataCraftException("Failed to read UTF-8 lines from file: " + file, exception);
    }
  }

  /** Writes lines joined by a single {@code \n}, each terminated by {@code \n}, for determinism. */
  public static void writeUtf8Lines(Path file, List<String> lines) {
    StringBuilder builder = new StringBuilder();
    for (String line : lines) {
      builder.append(line).append('\n');
    }
    writeUtf8String(file, builder.toString());
  }

  public static void appendUtf8String(Path file, String content) {
    ensureParentDirectory(file);
    try {
      Files.writeString(
          file,
          content,
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } catch (IOException exception) {
      throw new DataCraftException("Failed to append UTF-8 content to file: " + file, exception);
    }
  }

  public static boolean exists(Path path) {
    return Files.exists(path);
  }

  public static long size(Path file) {
    try {
      return Files.size(file);
    } catch (IOException exception) {
      throw new DataCraftException("Failed to read size of file: " + file, exception);
    }
  }

  public static void move(Path source, Path target) {
    ensureParentDirectory(target);
    try {
      Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException exception) {
      throw new DataCraftException(
          "Failed to move file from " + source + " to " + target, exception);
    }
  }

  /**
   * Deletes a single file or empty directory if it exists.
   *
   * @return {@code true} when an entry was deleted, {@code false} when nothing existed.
   */
  public static boolean delete(Path path) {
    try {
      return Files.deleteIfExists(path);
    } catch (IOException exception) {
      throw new DataCraftException("Failed to delete path: " + path, exception);
    }
  }

  /**
   * Recursively deletes a file or directory tree; a no-op when the path does not exist. Links
   * (symbolic links and Windows junctions, including dangling ones), including the start path, are
   * removed without deleting their targets. Any other Windows reparse-point directory is removed
   * only when it is empty; otherwise the call fails without descending into it.
   */
  public static void deleteRecursively(Path path) {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    try {
      Files.walkFileTree(
          path,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                throws IOException {
              // A Windows junction or mount point reports isDirectory() and isOther(), never
              // isSymbolicLink(): remove the link itself and never descend into its target.
              if (attributes.isOther()) {
                Files.delete(directory);
                return FileVisitResult.SKIP_SUBTREE;
              }
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                throws IOException {
              // Symbolic links arrive here unfollowed, so only the link is removed.
              Files.delete(file);
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException failure)
                throws IOException {
              // A junction whose target is gone cannot be opened as a directory, so the walker
              // reports it here instead of in preVisitDirectory; the link itself is removable.
              if (!isReparsePoint(file)) {
                throw failure;
              }
              Files.delete(file);
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure)
                throws IOException {
              if (failure != null) {
                throw failure;
              }
              Files.delete(directory);
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException exception) {
      throw new DataCraftException("Failed to recursively delete path: " + path, exception);
    }
  }

  /**
   * Whether the entry itself, read without following links, reports {@link
   * BasicFileAttributes#isOther()}: on Windows a reparse point such as a junction or mount point,
   * on POSIX a special file. Returns {@code false} when the attributes cannot be read, so that
   * callers report their original failure.
   */
  static boolean isReparsePoint(Path path) {
    try {
      return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
          .isOther();
    } catch (IOException exception) {
      return false;
    }
  }

  private static void ensureParentDirectory(Path file) {
    Path parent = file.toAbsolutePath().getParent();
    if (parent != null) {
      ensureDirectory(parent);
    }
  }
}
