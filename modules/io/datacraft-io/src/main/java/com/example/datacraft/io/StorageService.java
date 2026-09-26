package com.example.datacraft.io;

import java.nio.file.Path;
import java.util.List;

public interface StorageService {

  String readUtf8(Path path);

  void writeUtf8(Path path, String content);

  byte[] readBytes(Path path);

  void writeBytes(Path path, byte[] content);

  void copy(Path source, Path target);

  void move(Path source, Path target);

  boolean exists(Path path);

  boolean delete(Path path);

  long size(Path path);

  String sha256Hex(Path path);

  List<Path> listRegularFiles(Path directory);

  List<Path> listRegularFilesRecursively(Path directory);
}
