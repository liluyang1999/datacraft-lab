package com.example.datacraft.io;

import com.example.datacraft.common.DataCraftException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;

/**
 * Port for remote file transfer back-ends (SFTP today, potentially cloud object stores later).
 * Application and job code should depend on this interface rather than a concrete client so the
 * transport can be swapped and faked in tests.
 *
 * <p>Every remote path is literal: no wildcard or escape character is interpreted, so an operation
 * addresses exactly the named entry.
 */
public interface RemoteFileTransfer extends AutoCloseable {

  /**
   * Downloads a remote file to a local file path, creating parent directories as needed. The data
   * is streamed into a dot-prefixed sibling file ({@code .<name>.<uuid>.part}, hidden on POSIX)
   * that is atomically moved onto the target only after the transfer completes, so a failure leaves
   * an existing target unchanged and no partial file behind. Because the target is replaced by a
   * new file, it gets default permissions and ownership rather than keeping those of the file it
   * replaces, and hard links to the old file keep the old content. A target that is an existing
   * directory is rejected.
   */
  default void download(String remotePath, Path localPath) {
    Path target = localPath.toAbsolutePath();
    if (Files.isDirectory(target)) {
      throw new DataCraftException("Download target is a directory: " + target);
    }
    LocalFiles.ensureDirectory(target.getParent());
    Path partial =
        target.resolveSibling("." + target.getFileName() + "." + UUID.randomUUID() + ".part");
    try {
      // CREATE_NEW keeps the default (umask) permissions a plain new file would get.
      try (OutputStream output =
          Files.newOutputStream(partial, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
        download(remotePath, output);
      }
      Files.move(
          partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException failure) {
      deletePartialDownload(partial, failure);
      throw new DataCraftException(
          "Failed to store download of " + remotePath + " at " + target, failure);
    } catch (RuntimeException | Error failure) {
      deletePartialDownload(partial, failure);
      throw failure;
    }
  }

  private static void deletePartialDownload(Path partial, Throwable failure) {
    try {
      Files.deleteIfExists(partial);
    } catch (IOException cleanupFailure) {
      failure.addSuppressed(cleanupFailure);
    }
  }

  /** Streams a remote file into the supplied output stream; the caller owns the stream. */
  void download(String remotePath, OutputStream target);

  /** Uploads a local file to a remote file path (not into a remote directory). */
  void upload(Path localPath, String remotePath);

  /** Streams the supplied input into a remote file; the caller owns the stream. */
  void upload(InputStream source, String remotePath);

  /** Returns {@code true} when the remote path exists. */
  boolean exists(String remotePath);

  /** Returns the byte size of a remote file. */
  long size(String remotePath);

  /**
   * Lists the names of the regular files in a remote directory, sorted. Symbolic links are
   * followed; directories, special files and dangling links are excluded. Entries whose type the
   * server does not report are kept.
   */
  List<String> list(String remoteDirectory);

  /** Recursively creates a remote directory path. */
  void makeDirectories(String remoteDirectory);

  /** Deletes a remote file. */
  void delete(String remotePath);

  /** Renames/moves a remote entry. */
  void rename(String from, String to);

  @Override
  void close();
}
