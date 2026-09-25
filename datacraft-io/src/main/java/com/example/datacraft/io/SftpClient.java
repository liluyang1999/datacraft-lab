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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Vector;

/**
 * SFTP-backed {@link RemoteFileTransfer} built on the mwiede JSch fork. Remote paths are escaped
 * before they reach JSch, so they are always literal.
 */
public final class SftpClient implements RemoteFileTransfer {

  private static final int S_IFMT = 0170000;
  private static final int S_IFREG = 0100000;

  private final Session session;
  private final ChannelSftp channel;

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
        session.setPassword(config.password());
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
    try {
      channel.get(literal(remotePath), target);
    } catch (SftpException exception) {
      throw new DataCraftException("Failed to download SFTP file: " + remotePath, exception);
    }
  }

  @Override
  public void upload(Path localPath, String remotePath) {
    // Streaming also bypasses the wildcard expansion JSch applies to a local path argument.
    try (InputStream source = Files.newInputStream(localPath)) {
      channel.put(source, literal(remotePath));
    } catch (SftpException | IOException exception) {
      throw new DataCraftException("Failed to upload SFTP file: " + localPath, exception);
    }
  }

  @Override
  public void upload(InputStream source, String remotePath) {
    try {
      channel.put(source, literal(remotePath));
    } catch (SftpException exception) {
      throw new DataCraftException("Failed to upload SFTP file to: " + remotePath, exception);
    }
  }

  @Override
  public boolean exists(String remotePath) {
    try {
      channel.stat(literal(remotePath));
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
      return channel.stat(literal(remotePath)).getSize();
    } catch (SftpException exception) {
      throw new DataCraftException("Failed to read size of SFTP path: " + remotePath, exception);
    }
  }

  @Override
  public List<String> list(String remoteDirectory) {
    Vector<ChannelSftp.LsEntry> entries;
    try {
      entries = channel.ls(literal(remoteDirectory));
    } catch (SftpException exception) {
      throw new DataCraftException("Failed to list SFTP directory: " + remoteDirectory, exception);
    }
    List<String> names = new ArrayList<>();
    for (ChannelSftp.LsEntry entry : entries) {
      String name = entry.getFilename();
      if (name.equals(".") || name.equals("..")) {
        continue;
      }
      SftpATTRS attributes = entry.getAttrs();
      if (attributes.isLink()) {
        // Directory listings report the link itself; follow it to classify its target.
        String link =
            remoteDirectory.isEmpty() || remoteDirectory.endsWith("/")
                ? remoteDirectory + name
                : remoteDirectory + "/" + name;
        try {
          attributes = channel.stat(literal(link));
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
      if (!exists(path)) {
        try {
          // JSch sends mkdir paths verbatim (no wildcard or escape handling), so no literal().
          channel.mkdir(path);
        } catch (SftpException exception) {
          throw new DataCraftException("Failed to create SFTP directory: " + path, exception);
        }
      }
    }
  }

  @Override
  public void delete(String remotePath) {
    try {
      channel.rm(literal(remotePath));
    } catch (SftpException exception) {
      throw new DataCraftException("Failed to delete SFTP file: " + remotePath, exception);
    }
  }

  @Override
  public void rename(String from, String to) {
    try {
      channel.rename(literal(from), literal(to));
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

  private static Properties sessionConfig(SftpConfig config) {
    Properties properties = new Properties();
    properties.setProperty("StrictHostKeyChecking", config.strictHostKeyChecking() ? "yes" : "no");
    return properties;
  }
}
