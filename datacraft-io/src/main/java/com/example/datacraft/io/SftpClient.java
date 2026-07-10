package com.example.datacraft.io;

import com.example.datacraft.common.DataCraftException;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Vector;

/** SFTP-backed {@link RemoteFileTransfer} built on the mwiede JSch fork. */
public final class SftpClient implements RemoteFileTransfer {

  private final Session session;
  private final ChannelSftp channel;

  private SftpClient(Session session, ChannelSftp channel) {
    this.session = session;
    this.channel = channel;
  }

  public static SftpClient connect(SftpConfig config) {
    try {
      JSch jsch = new JSch();
      if (config.hasPrivateKey()) {
        jsch.addIdentity(config.privateKey().toString());
      }

      Session session = jsch.getSession(config.username(), config.host(), config.port());
      if (config.password() != null && !config.password().isBlank()) {
        session.setPassword(config.password());
      }
      session.setConfig(sessionConfig(config));
      session.connect(Math.toIntExact(config.timeout().toMillis()));

      ChannelSftp channel = (ChannelSftp) session.openChannel("sftp");
      channel.connect(Math.toIntExact(config.timeout().toMillis()));
      return new SftpClient(session, channel);
    } catch (JSchException exception) {
      throw new DataCraftException("Failed to connect to SFTP host: " + config.host(), exception);
    }
  }

  @Override
  public void download(String remotePath, Path localPath) {
    LocalFiles.ensureDirectory(localPath.toAbsolutePath().getParent());
    try {
      channel.get(remotePath, localPath.toString());
    } catch (SftpException exception) {
      throw new DataCraftException("Failed to download SFTP file: " + remotePath, exception);
    }
  }

  @Override
  public void download(String remotePath, OutputStream target) {
    try {
      channel.get(remotePath, target);
    } catch (SftpException exception) {
      throw new DataCraftException("Failed to download SFTP file: " + remotePath, exception);
    }
  }

  @Override
  public void upload(Path localPath, String remotePath) {
    try {
      channel.put(localPath.toString(), remotePath);
    } catch (SftpException exception) {
      throw new DataCraftException("Failed to upload SFTP file: " + localPath, exception);
    }
  }

  @Override
  public void upload(InputStream source, String remotePath) {
    try {
      channel.put(source, remotePath);
    } catch (SftpException exception) {
      throw new DataCraftException("Failed to upload SFTP file to: " + remotePath, exception);
    }
  }

  @Override
  public boolean exists(String remotePath) {
    try {
      channel.stat(remotePath);
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
      return channel.stat(remotePath).getSize();
    } catch (SftpException exception) {
      throw new DataCraftException("Failed to read size of SFTP path: " + remotePath, exception);
    }
  }

  @Override
  public List<String> list(String remoteDirectory) {
    try {
      Vector<ChannelSftp.LsEntry> entries = channel.ls(remoteDirectory);
      List<String> names = new ArrayList<>();
      for (ChannelSftp.LsEntry entry : entries) {
        String name = entry.getFilename();
        if (name.equals(".") || name.equals("..") || entry.getAttrs().isDir()) {
          continue;
        }
        names.add(name);
      }
      names.sort(String::compareTo);
      return List.copyOf(names);
    } catch (SftpException exception) {
      throw new DataCraftException("Failed to list SFTP directory: " + remoteDirectory, exception);
    }
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
      channel.rm(remotePath);
    } catch (SftpException exception) {
      throw new DataCraftException("Failed to delete SFTP file: " + remotePath, exception);
    }
  }

  @Override
  public void rename(String from, String to) {
    try {
      channel.rename(from, to);
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

  private static Properties sessionConfig(SftpConfig config) {
    Properties properties = new Properties();
    properties.setProperty("StrictHostKeyChecking", config.strictHostKeyChecking() ? "yes" : "no");
    return properties;
  }
}
