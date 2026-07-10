package com.example.datacraft.io;

import java.nio.file.Path;
import java.util.List;

public final class LocalStorageService implements StorageService {

  private final Path root;

  private LocalStorageService(Path root) {
    this.root = root.toAbsolutePath().normalize();
    LocalFiles.ensureDirectory(this.root);
  }

  public static LocalStorageService at(Path root) {
    return new LocalStorageService(root);
  }

  @Override
  public String readUtf8(Path path) {
    return LocalFiles.readUtf8String(resolve(path));
  }

  @Override
  public void writeUtf8(Path path, String content) {
    LocalFiles.writeUtf8String(resolve(path), content);
  }

  @Override
  public byte[] readBytes(Path path) {
    return LocalFiles.readBytes(resolve(path));
  }

  @Override
  public void writeBytes(Path path, byte[] content) {
    LocalFiles.writeBytes(resolve(path), content);
  }

  @Override
  public void copy(Path source, Path target) {
    LocalFiles.copy(resolve(source), resolve(target));
  }

  @Override
  public void move(Path source, Path target) {
    LocalFiles.move(resolve(source), resolve(target));
  }

  @Override
  public boolean exists(Path path) {
    return LocalFiles.exists(resolve(path));
  }

  @Override
  public boolean delete(Path path) {
    return LocalFiles.delete(resolve(path));
  }

  @Override
  public long size(Path path) {
    return LocalFiles.size(resolve(path));
  }

  @Override
  public String sha256Hex(Path path) {
    return LocalFiles.sha256Hex(resolve(path));
  }

  @Override
  public List<Path> listRegularFiles(Path directory) {
    return LocalFiles.listRegularFiles(resolve(directory)).stream().map(root::relativize).toList();
  }

  @Override
  public List<Path> listRegularFilesRecursively(Path directory) {
    return LocalFiles.listRegularFilesRecursively(resolve(directory)).stream()
        .map(root::relativize)
        .toList();
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
    return resolved;
  }
}
