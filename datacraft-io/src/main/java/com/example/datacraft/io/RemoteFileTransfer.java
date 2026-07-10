package com.example.datacraft.io;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;

/**
 * Port for remote file transfer back-ends (SFTP today, potentially cloud object stores later).
 * Application and job code should depend on this interface rather than a concrete client so the
 * transport can be swapped and faked in tests.
 */
public interface RemoteFileTransfer extends AutoCloseable {

  /** Downloads a remote file to a local path, creating parent directories as needed. */
  void download(String remotePath, Path localPath);

  /** Streams a remote file into the supplied output stream; the caller owns the stream. */
  void download(String remotePath, OutputStream target);

  /** Uploads a local file to a remote path. */
  void upload(Path localPath, String remotePath);

  /** Streams the supplied input into a remote file; the caller owns the stream. */
  void upload(InputStream source, String remotePath);

  /** Returns {@code true} when the remote path exists. */
  boolean exists(String remotePath);

  /** Returns the byte size of a remote file. */
  long size(String remotePath);

  /** Lists the regular (non-directory) entry names of a remote directory, sorted. */
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
