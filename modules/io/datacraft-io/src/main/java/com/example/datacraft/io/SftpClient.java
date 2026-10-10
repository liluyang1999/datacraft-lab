package com.example.datacraft.io;

import com.example.datacraft.common.DataCraftException;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Vector;

/**
 * SFTP-backed {@link RemoteFileTransfer} built on the mwiede JSch fork. Remote paths are escaped
 * before they reach JSch, so they are always literal. One call at a time: a client is not safe for
 * use by several threads.
 */
public final class SftpClient implements RemoteFileTransfer {

  private static final int S_IFMT = 0170000;
  private static final int S_IFREG = 0100000;
  private static final char NUL = 0;

  private final Session session;
  private ChannelSftp channel;

  /** Set when a transfer failed: its channel may still deliver answers nobody waits for. */
  private boolean channelSuspect;

  private SftpClient(Session session, ChannelSftp channel) {
    this.session = session;
    this.channel = channel;
  }

  public static SftpClient connect(SftpConfig config) {
    return connect(config, Path.of(System.getProperty("user.home"), ".ssh", "known_hosts"));
  }

  /** Uses an explicit trusted-hosts file; strict verification stays enabled by default. */
  public static SftpClient connect(SftpConfig config, Path knownHosts) {
    Session session = null;
    ChannelSftp channel = null;
    boolean connected = false;
    try {
      JSch jsch = new JSch();
      if (config.strictHostKeyChecking()) {
        jsch.setKnownHosts(knownHosts.toString());
      }
      if (config.hasPrivateKey()) {
        jsch.addIdentity(config.privateKey().toString());
      }

      session = jsch.getSession(config.username(), config.host(), config.port());
      if (config.password() != null && !config.password().isBlank()) {
        // JSch copies the bytes (UTF-8, as its deprecated String overload did); wipe ours at once.
        byte[] password = config.password().getBytes(StandardCharsets.UTF_8);
        try {
          session.setPassword(password);
        } finally {
          Arrays.fill(password, (byte) 0);
        }
      }
      session.setConfig(sessionConfig(config));
      session.setTimeout(Math.toIntExact(config.timeout().toMillis()));
      session.connect(Math.toIntExact(config.timeout().toMillis()));

      channel = (ChannelSftp) session.openChannel("sftp");
      channel.connect(Math.toIntExact(config.timeout().toMillis()));
      connected = true;
      return new SftpClient(session, channel);
    } catch (JSchException exception) {
      throw new DataCraftException("Failed to connect to SFTP host: " + config.host(), exception);
    } finally {
      if (!connected) {
        if (channel != null) {
          channel.disconnect();
        }
        if (session != null) {
          session.disconnect();
        }
      }
    }
  }

  @Override
  public void download(String remotePath, OutputStream target) {
    ChannelSftp sftp = channel();
    try {
      sftp.get(literal(remotePath), target);
    } catch (SftpException exception) {
      channelSuspect = true;
      throw new DataCraftException("Failed to download SFTP file: " + remotePath, exception);
    }
  }

  @Override
  public void upload(Path localPath, String remotePath) {
    // Streaming also bypasses the wildcard expansion JSch applies to a local path argument.
    try (InputStream source = Files.newInputStream(localPath)) {
      put(source, remotePath);
    } catch (SftpException | IOException exception) {
      throw new DataCraftException("Failed to upload SFTP file: " + localPath, exception);
    }
  }

  @Override
  public void upload(InputStream source, String remotePath) {
    try {
      put(source, remotePath);
    } catch (SftpException exception) {
      throw new DataCraftException("Failed to upload SFTP file to: " + remotePath, exception);
    }
  }

  private void put(InputStream source, String remotePath) throws SftpException {
    ChannelSftp sftp = channel();
    try {
      sftp.put(source, literal(remotePath));
    } catch (SftpException exception) {
      channelSuspect = true;
      throw exception;
    }
  }

  @Override
  public boolean exists(String remotePath) {
    try {
      channel().stat(literal(remotePath));
      return true;
    } catch (SftpException exception) {
      if (exception.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
        return false;
      }
      throw new DataCraftException("Failed to stat SFTP path: " + remotePath, exception);
    }
  }

  @Override
  public long size(String remotePath) {
    try {
      return channel().stat(literal(remotePath)).getSize();
    } catch (SftpException exception) {
      throw new DataCraftException("Failed to read size of SFTP path: " + remotePath, exception);
    }
  }

  @Override
  public List<String> list(String remoteDirectory) {
    // An empty name is the directory the session starts in, which JSch cannot address as "".
    String directory = remoteDirectory.isEmpty() ? "." : remoteDirectory;
    ChannelSftp sftp = channel();
    Vector<ChannelSftp.LsEntry> entries;
    try {
      // Asked to list a file, JSch answers with that one file as if it were a directory entry.
      if (isKnownNotToBeADirectory(sftp.stat(literal(directory)))) {
        throw new DataCraftException("SFTP path is not a directory: " + remoteDirectory);
      }
      entries = sftp.ls(literal(directory));
    } catch (SftpException exception) {
      throw new DataCraftException("Failed to list SFTP directory: " + remoteDirectory, exception);
    }
    List<String> names = new ArrayList<>();
    for (ChannelSftp.LsEntry entry : entries) {
      String name = entry.getFilename();
      if (name.equals(".") || name.equals("..")) {
        continue;
      }
      if (!isEntryName(name)) {
        throw new DataCraftException(
            "SFTP server listed an entry whose name is not one path segment in: "
                + remoteDirectory);
      }
      SftpATTRS attributes = entry.getAttrs();
      if (attributes.isLink()) {
        // Directory listings report the link itself; follow it to classify its target.
        String link = directory.endsWith("/") ? directory + name : directory + "/" + name;
        try {
          attributes = sftp.stat(literal(link));
        } catch (SftpException exception) {
          if (exception.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
            continue; // dangling link
          }
          throw new DataCraftException("Failed to stat SFTP link: " + link, exception);
        }
      }
      if (isRegularOrUnknownType(attributes.getFlags(), attributes.getPermissions())) {
        names.add(name);
      }
    }
    names.sort(String::compareTo);
    return List.copyOf(names);
  }

  @Override
  public void makeDirectories(String remoteDirectory) {
    StringBuilder current = new StringBuilder(remoteDirectory.startsWith("/") ? "/" : "");
    for (String segment : remoteDirectory.split("/")) {
      if (segment.isEmpty()) {
        continue;
      }
      if (current.length() > 0 && current.charAt(current.length() - 1) != '/') {
        current.append('/');
      }
      current.append(segment);
      String path = current.toString();
      if (!directoryExists(path)) {
        try {
          // JSch sends mkdir paths verbatim (no wildcard or escape handling), so no literal().
          channel().mkdir(path);
        } catch (SftpException exception) {
          throw new DataCraftException("Failed to create SFTP directory: " + path, exception);
        }
      }
    }
  }

  /** False when nothing is at {@code path}; an entry that is not a directory is an error. */
  private boolean directoryExists(String path) {
    SftpATTRS attributes;
    try {
      attributes = channel().stat(literal(path));
    } catch (SftpException exception) {
      if (exception.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
        return false;
      }
      throw new DataCraftException("Failed to stat SFTP path: " + path, exception);
    }
    if (isKnownNotToBeADirectory(attributes)) {
      throw new DataCraftException("SFTP path exists and is not a directory: " + path);
    }
    return true;
  }

  @Override
  public void delete(String remotePath) {
    try {
      channel().rm(literal(remotePath));
    } catch (SftpException exception) {
      throw new DataCraftException("Failed to delete SFTP file: " + remotePath, exception);
    }
  }

  @Override
  public void rename(String from, String to) {
    try {
      channel().rename(literal(from), literal(to));
    } catch (SftpException exception) {
      throw new DataCraftException(
          "Failed to rename SFTP path from " + from + " to " + to, exception);
    }
  }

  @Override
  public void close() {
    if (channel.isConnected()) {
      channel.disconnect();
    }
    if (session.isConnected()) {
      session.disconnect();
    }
  }

  /**
   * The channel for the next request. JSch sends the requests of a transfer ahead of reading their
   * answers, so the channel of a transfer that failed may still deliver answers to requests nobody
   * waits for, and the next calls would take them for their own. A new channel on the same session
   * starts clean; the client never changes directory, so it addresses the same paths.
   */
  private ChannelSftp channel() {
    if (channelSuspect) {
      channel.disconnect();
      ChannelSftp fresh = null;
      try {
        fresh = (ChannelSftp) session.openChannel("sftp");
        fresh.connect(session.getTimeout());
      } catch (JSchException exception) {
        if (fresh != null) {
          fresh.disconnect();
        }
        throw new DataCraftException(
            "Failed to reopen the SFTP channel to host: " + session.getHost(), exception);
      }
      channel = fresh;
      channelSuspect = false;
    }
    return channel;
  }

  /**
   * Escapes the characters JSch treats as path syntax ({@code *} and {@code ?} as wildcards, the
   * backslash as the escape character) so that JSch addresses exactly the given path.
   */
  static String literal(String path) {
    StringBuilder escaped = new StringBuilder(path.length() + 8);
    for (int i = 0; i < path.length(); i++) {
      char c = path.charAt(i);
      if (c == '\\' || c == '*' || c == '?') {
        escaped.append('\\');
      }
      escaped.append(c);
    }
    return escaped.toString();
  }

  /**
   * Classifies SFTP attributes as a regular file ({@code S_IFREG}). Attributes without a
   * permissions field carry no file type; those entries are kept.
   */
  static boolean isRegularOrUnknownType(int flags, int permissions) {
    if ((flags & SftpATTRS.SSH_FILEXFER_ATTR_PERMISSIONS) == 0) {
      return true;
    }
    return (permissions & S_IFMT) == S_IFREG;
  }

  /** Whether the server reported the entry's type and it is something other than a directory. */
  private static boolean isKnownNotToBeADirectory(SftpATTRS attributes) {
    return (attributes.getFlags() & SftpATTRS.SSH_FILEXFER_ATTR_PERMISSIONS) != 0
        && !attributes.isDir();
  }

  /**
   * Whether a listed name is one path segment. A caller joins it to a directory, remote and often
   * local too, so a name with a separator in it would address something else. A backslash is an
   * ordinary character in a POSIX file name and is kept, although it separates paths on Windows.
   */
  static boolean isEntryName(String name) {
    return !name.isEmpty() && name.indexOf('/') < 0 && name.indexOf(NUL) < 0;
  }

  private static Properties sessionConfig(SftpConfig config) {
    Properties properties = new Properties();
    properties.setProperty("StrictHostKeyChecking", config.strictHostKeyChecking() ? "yes" : "no");
    return properties;
  }
}
