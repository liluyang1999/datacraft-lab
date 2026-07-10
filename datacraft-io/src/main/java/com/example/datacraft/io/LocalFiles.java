package com.example.datacraft.io;

import com.example.datacraft.common.DataCraftException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
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
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(readBytes(file)));
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

  /** Recursively deletes a file or directory tree; a no-op when the path does not exist. */
  public static void deleteRecursively(Path path) {
    if (!Files.exists(path)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(path)) {
      paths
          .sorted(Comparator.reverseOrder())
          .forEach(
              entry -> {
                try {
                  Files.delete(entry);
                } catch (IOException exception) {
                  throw new DataCraftException("Failed to delete path: " + entry, exception);
                }
              });
    } catch (IOException exception) {
      throw new DataCraftException("Failed to recursively delete path: " + path, exception);
    }
  }

  private static void ensureParentDirectory(Path file) {
    Path parent = file.toAbsolutePath().getParent();
    if (parent != null) {
      ensureDirectory(parent);
    }
  }
}
