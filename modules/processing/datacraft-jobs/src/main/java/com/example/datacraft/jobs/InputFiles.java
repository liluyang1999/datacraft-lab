package com.example.datacraft.jobs;

import com.example.datacraft.common.DataCraftException;
import com.example.datacraft.io.LocalFiles;
import com.example.datacraft.io.LocalStorageService;
import com.example.datacraft.io.StorageService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Opens the {@code input} file of a JVM job.
 *
 * <p>When a data root is configured (by default from the {@value #DATA_ROOT_ENV} environment
 * variable, the same confinement the Spark jobs and the deployment use), the input must resolve
 * strictly inside it. The absolute, normalized input is then relativized against the root and read
 * only through a {@link LocalStorageService}, so its guards against {@code ..} escapes and symbolic
 * links apply to every access. Without a data root the path is read directly with {@link
 * LocalFiles}.
 */
final class InputFiles {

  /** Environment variable naming the absolute directory that confines job paths. */
  static final String DATA_ROOT_ENV = "DATACRAFT_DATA_ROOT";

  /** The configured root as given; it is validated when a job runs, not when jobs are built. */
  private final Optional<String> dataRoot;

  private InputFiles(Optional<String> dataRoot) {
    this.dataRoot = dataRoot;
  }

  /** Confines inputs under {@value #DATA_ROOT_ENV} when it is set and not blank. */
  static InputFiles fromEnvironment() {
    return fromEnvironmentValue(System.getenv(DATA_ROOT_ENV));
  }

  /** Interprets a raw {@value #DATA_ROOT_ENV} value: trimmed, and blank means unconfined. */
  static InputFiles fromEnvironmentValue(String value) {
    return new InputFiles(Optional.ofNullable(value).map(String::trim).filter(v -> !v.isEmpty()));
  }

  /** Confines inputs under {@code dataRoot} when present; empty reads paths unconfined. */
  static InputFiles confinedTo(Optional<Path> dataRoot) {
    if (dataRoot == null) {
      throw new IllegalArgumentException("Data root must not be null; use Optional.empty().");
    }
    return new InputFiles(dataRoot.map(Path::toString));
  }

  /**
   * Validates that {@code input} names an existing regular file, inside the data root when one is
   * configured, and returns a handle whose reads go through the same guarded access path.
   */
  InputFile open(String input) {
    Path path;
    try {
      path = Path.of(input);
    } catch (InvalidPathException exception) {
      throw new IllegalArgumentException(
          "input is not a valid path: " + exception.getMessage(), exception);
    }
    if (dataRoot.isEmpty()) {
      requireRegularFile(path, input);
      return new LocalInput(path);
    }

    Path root = requireDataRoot(dataRoot.get());
    Path absolute = path.toAbsolutePath().normalize();
    if (!absolute.startsWith(root) || absolute.equals(root)) {
      throw new IllegalArgumentException("input must be inside the configured data root");
    }
    Path relative = root.relativize(absolute);
    // The configured root is trusted and may itself be a link, as the Spark jobs accept; links
    // below it are still refused by the storage guards.
    Path storageRoot = realDataRoot(root);
    StorageService storage = LocalStorageService.at(storageRoot);
    // exists() runs the storage guards first, so the type check below never follows a link.
    if (!storage.exists(relative)) {
      throw new IllegalArgumentException("Input file does not exist: " + input);
    }
    if (!Files.isRegularFile(storageRoot.resolve(relative))) {
      throw new IllegalArgumentException("Input is not a regular file: " + input);
    }
    return new StoredInput(storage, relative);
  }

  private static Path requireDataRoot(String configured) {
    Path root;
    try {
      root = Path.of(configured);
    } catch (InvalidPathException exception) {
      throw new DataCraftException(DATA_ROOT_ENV + " is not a valid path", exception);
    }
    if (!root.isAbsolute()) {
      throw new DataCraftException(DATA_ROOT_ENV + " must be an absolute path");
    }
    root = root.normalize();
    // LocalStorageService creates a missing root; a read-only job must not.
    if (!Files.isDirectory(root)) {
      throw new DataCraftException(DATA_ROOT_ENV + " must name an existing directory");
    }
    return root;
  }

  private static Path realDataRoot(Path root) {
    try {
      return root.toRealPath();
    } catch (IOException exception) {
      throw new DataCraftException(DATA_ROOT_ENV + " cannot be resolved", exception);
    }
  }

  private static void requireRegularFile(Path path, String input) {
    if (!Files.exists(path)) {
      throw new IllegalArgumentException("Input file does not exist: " + input);
    }
    if (!Files.isRegularFile(path)) {
      throw new IllegalArgumentException("Input is not a regular file: " + input);
    }
  }

  /** A validated input file. Source files must stay unchanged while a job reads them. */
  interface InputFile {

    long size();

    byte[] readBytes();

    String sha256Hex();
  }

  private record LocalInput(Path path) implements InputFile {

    @Override
    public long size() {
      return LocalFiles.size(path);
    }

    @Override
    public byte[] readBytes() {
      return LocalFiles.readBytes(path);
    }

    @Override
    public String sha256Hex() {
      return LocalFiles.sha256Hex(path);
    }
  }

  private record StoredInput(StorageService storage, Path relative) implements InputFile {

    @Override
    public long size() {
      return storage.size(relative);
    }

    @Override
    public byte[] readBytes() {
      return storage.readBytes(relative);
    }

    @Override
    public String sha256Hex() {
      return storage.sha256Hex(relative);
    }
  }
}
