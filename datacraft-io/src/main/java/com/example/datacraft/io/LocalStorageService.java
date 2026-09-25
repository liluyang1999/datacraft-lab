package com.example.datacraft.io;

import com.example.datacraft.common.DataCraftException;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * {@link StorageService} anchored at a local root directory. Paths are relative to the root; the
 * root itself is valid only as a listing directory. A path is refused when it traverses a symbolic
 * link or when any existing component's real location is outside the root (for example through a
 * Windows junction). Listings omit symbolic links, and recursive listings do not descend into
 * reparse-point directories. This is an application guard, not an OS sandbox: hard links, Windows
 * reserved device names such as {@code NUL}, and concurrent filesystem changes are not detected.
 */
public final class LocalStorageService implements StorageService {

  private final Path root;
  private final Path realRoot;

  private LocalStorageService(Path root) {
    this.root = root.toAbsolutePath().normalize();
    LocalFiles.ensureDirectory(this.root);
    // The real path also expands Windows 8.3 short names such as LILUYA~1.
    this.realRoot = realPath(this.root);
  }

  public static LocalStorageService at(Path root) {
    return new LocalStorageService(root);
  }

  @Override
  public String readUtf8(Path path) {
    return LocalFiles.readUtf8String(resolveEntry(path));
  }

  @Override
  public void writeUtf8(Path path, String content) {
    LocalFiles.writeUtf8String(resolveEntry(path), content);
  }

  @Override
  public byte[] readBytes(Path path) {
    return LocalFiles.readBytes(resolveEntry(path));
  }

  @Override
  public void writeBytes(Path path, byte[] content) {
    LocalFiles.writeBytes(resolveEntry(path), content);
  }

  @Override
  public void copy(Path source, Path target) {
    LocalFiles.copy(resolveEntry(source), resolveEntry(target));
  }

  @Override
  public void move(Path source, Path target) {
    LocalFiles.move(resolveEntry(source), resolveEntry(target));
  }

  @Override
  public boolean exists(Path path) {
    return LocalFiles.exists(resolve(path));
  }

  @Override
  public boolean delete(Path path) {
    return LocalFiles.delete(resolveEntry(path));
  }

  @Override
  public long size(Path path) {
    return LocalFiles.size(resolveEntry(path));
  }

  @Override
  public String sha256Hex(Path path) {
    return LocalFiles.sha256Hex(resolveEntry(path));
  }

  @Override
  public List<Path> listRegularFiles(Path directory) {
    return LocalFiles.listRegularFiles(resolve(directory)).stream()
        .filter(file -> !Files.isSymbolicLink(file))
        .map(root::relativize)
        .toList();
  }

  @Override
  public List<Path> listRegularFilesRecursively(Path directory) {
    Path start = resolve(directory);
    List<Path> files = new ArrayList<>();
    try {
      Files.walkFileTree(
          start,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes)
                throws IOException {
              // isOther() marks a Windows reparse-point directory such as a junction; a POSIX
              // directory never reports it, and directory symlinks are never entered anyway.
              if (!dir.equals(start)
                  && (attributes.isOther() || !dir.toRealPath().startsWith(realRoot))) {
                return FileVisitResult.SKIP_SUBTREE;
              }
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
              // Attributes are read without following links, so symbolic links are omitted.
              if (attributes.isRegularFile()) {
                files.add(root.relativize(file));
              }
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException failure)
                throws IOException {
              // A junction whose target is gone cannot be opened, so the walker reports it here
              // instead of in preVisitDirectory; skip it like every other reparse point.
              if (file.equals(start) || !LocalFiles.isReparsePoint(file)) {
                throw failure;
              }
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException exception) {
      throw new DataCraftException(
          "Failed to recursively list regular files in directory: " + start, exception);
    }
    files.sort(Comparator.naturalOrder());
    return List.copyOf(files);
  }

  /** Resolves a path to a file operand; the root itself is not one. */
  private Path resolveEntry(Path path) {
    Path resolved = resolve(path);
    if (resolved.equals(root)) {
      throw new IllegalArgumentException("Storage path must name an entry under the root: " + path);
    }
    return resolved;
  }

  private Path resolve(Path path) {
    if (path == null) {
      throw new IllegalArgumentException("Storage path must not be null.");
    }
    if (path.isAbsolute()) {
      throw new IllegalArgumentException("Storage path must be relative: " + path);
    }

    Path resolved = root.resolve(path).normalize();
    if (!resolved.startsWith(root)) {
      throw new IllegalArgumentException("Storage path escapes root: " + path);
    }
    Path current = root;
    if (Files.isSymbolicLink(current)) {
      throw new IllegalArgumentException("Storage root must not be a symbolic link.");
    }
    for (Path segment : root.relativize(resolved)) {
      current = current.resolve(segment);
      if (Files.isSymbolicLink(current)) {
        throw new IllegalArgumentException(
            "Storage path must not traverse symbolic links: " + path);
      }
      if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
        break; // the remaining segments do not exist yet
      }
      if (!realPath(current).startsWith(realRoot)) {
        throw new IllegalArgumentException(
            "Storage path must not leave the root through a link or junction: " + path);
      }
    }
    return resolved;
  }

  private static Path realPath(Path path) {
    try {
      return path.toRealPath();
    } catch (IOException exception) {
      throw new DataCraftException("Failed to resolve the real path of: " + path, exception);
    }
  }
}
