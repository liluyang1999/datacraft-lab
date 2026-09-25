package com.example.datacraft.io;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.example.datacraft.common.DataCraftException;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.SftpATTRS;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.sftp.server.FileHandle;
import org.apache.sshd.sftp.server.SftpFileSystemAccessor;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.apache.sshd.sftp.server.SftpSubsystemProxy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives {@link SftpClient} against an in-process Apache MINA SSHD server on the loopback
 * interface, with strict host-key checking against a known_hosts file written from the server key.
 */
class SftpClientTest {

  /** Every socket uses this literal, so java.net.preferIPv6Addresses cannot split client/server. */
  private static final String HOST = "127.0.0.1";

  private static final String USER = "datacraft-test";
  private static final String PASSWORD = "test-only-password";
  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final KeyPair HOST_KEY = generateHostKey();

  @TempDir Path tempDir;

  private Path serverRoot;
  private SftpFileSystemAccessor fileSystemAccessor = SftpFileSystemAccessor.DEFAULT;
  private SshServer server;
  private final List<SftpClient> clients = new ArrayList<>();

  @BeforeEach
  void createServerRoot() throws IOException {
    serverRoot = Files.createDirectory(tempDir.resolve("server-root"));
  }

  @AfterEach
  void closeClientsAndStopServer() throws IOException {
    clients.forEach(SftpClient::close);
    if (server != null) {
      server.stop(true);
    }
  }

  @Test
  void literalEscapesTheCharactersJschInterprets() {
    assertEquals("in/a\\?c.csv", SftpClient.literal("in/a?c.csv"));
    assertEquals("x\\*", SftpClient.literal("x*"));
    assertEquals("a\\\\b", SftpClient.literal("a\\b"));
    assertEquals("/dir/plain.csv", SftpClient.literal("/dir/plain.csv"));
  }

  @Test
  void classifiesOnlyRegularFilesOrUnreportedTypesAsListable() {
    int allAttributes =
        SftpATTRS.SSH_FILEXFER_ATTR_SIZE
            | SftpATTRS.SSH_FILEXFER_ATTR_UIDGID
            | SftpATTRS.SSH_FILEXFER_ATTR_PERMISSIONS
            | SftpATTRS.SSH_FILEXFER_ATTR_ACMODTIME;

    assertTrue(SftpClient.isRegularOrUnknownType(allAttributes, 0100644));
    assertFalse(SftpClient.isRegularOrUnknownType(allAttributes, 040755));
    assertFalse(SftpClient.isRegularOrUnknownType(allAttributes, 0120777));
    assertFalse(SftpClient.isRegularOrUnknownType(allAttributes, 010644));
    assertFalse(SftpClient.isRegularOrUnknownType(allAttributes, 0140755));
    assertTrue(SftpClient.isRegularOrUnknownType(SftpATTRS.SSH_FILEXFER_ATTR_SIZE, 0));
  }

  @Test
  void timesOutAgainstAServerThatNeverSpeaks() throws Exception {
    try (ServerSocket silent = new ServerSocket(0, 1, InetAddress.getByName(HOST))) {
      SftpConfig config =
          new SftpConfig(
              HOST, silent.getLocalPort(), USER, PASSWORD, null, true, Duration.ofMillis(500));

      DataCraftException failure =
          assertTimeoutPreemptively(
              Duration.ofSeconds(5),
              () ->
                  assertThrows(
                      DataCraftException.class,
                      () -> SftpClient.connect(config, tempDir.resolve("known_hosts"))));

      assertTrue(hasCause(failure, SocketTimeoutException.class), () -> chain(failure));
      try (Socket accepted = silent.accept()) {
        accepted.setSoTimeout(5_000);
        InputStream input = accepted.getInputStream();
        while (input.read() != -1) {
          // Drain the client identification until the client closes its end.
        }
      }
    }
  }

  @Test
  void reportsARefusedConnection() throws Exception {
    int port;
    try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getByName(HOST))) {
      port = probe.getLocalPort();
    }

    DataCraftException failure =
        assertThrows(
            DataCraftException.class,
            () -> SftpClient.connect(config(port, PASSWORD), tempDir.resolve("known_hosts")));

    assertTrue(hasCause(failure, ConnectException.class), () -> chain(failure));
  }

  @Test
  void connectsWhenTheHostKeyMatchesAndClosesIdempotently() throws Exception {
    int port = startServer(true);

    SftpClient client = SftpClient.connect(config(port, PASSWORD), knownHosts(port, HOST_KEY));

    assertTrue(client.exists("/"));
    client.close();
    client.close();
    awaitNoActiveSessions();
  }

  @Test
  void refusesUnknownAndMismatchedHostKeys() throws Exception {
    int port = startServer(true);
    Path empty = Files.createFile(tempDir.resolve("empty_known_hosts"));
    Path mismatched = knownHosts(port, generateHostKey());

    for (Path trusted : List.of(empty, mismatched)) {
      DataCraftException failure =
          assertThrows(
              DataCraftException.class, () -> SftpClient.connect(config(port, PASSWORD), trusted));
      JSchException cause = assertInstanceOf(JSchException.class, failure.getCause());
      assertTrue(cause.getMessage().contains("HostKey"), cause::getMessage);
    }
    awaitNoActiveSessions();
  }

  @Test
  void rejectsAWrongPassword() throws Exception {
    int port = startServer(true);

    DataCraftException failure =
        assertThrows(
            DataCraftException.class,
            () -> SftpClient.connect(config(port, "wrong-password"), knownHosts(port, HOST_KEY)));

    assertInstanceOf(JSchException.class, failure.getCause());
    awaitNoActiveSessions();
  }

  @Test
  void closesTheSessionWhenTheSftpChannelCannotOpen() throws Exception {
    int port = startServer(false);

    assertThrows(
        DataCraftException.class,
        () -> SftpClient.connect(config(port, PASSWORD), knownHosts(port, HOST_KEY)));

    awaitNoActiveSessions();
  }

  @Test
  void roundTripsBytesThroughThePathAndStreamOverloads() throws Exception {
    SftpClient client = connectToSftpServer();
    byte[] payload = new byte[300_000];
    new Random(42).nextBytes(payload);
    Path local = Files.write(tempDir.resolve("local.bin"), payload);
    client.makeDirectories("in");

    client.upload(local, "in/from-path.bin");
    TrackingInputStream source = new TrackingInputStream(payload);
    client.upload(source, "in/from-stream.bin");

    assertFalse(source.closed);
    assertArrayEquals(payload, Files.readAllBytes(serverRoot.resolve("in/from-path.bin")));
    assertArrayEquals(payload, Files.readAllBytes(serverRoot.resolve("in/from-stream.bin")));

    Path downloaded = tempDir.resolve("missing").resolve("parent").resolve("down.bin");
    client.download("in/from-stream.bin", downloaded);
    TrackingOutputStream sink = new TrackingOutputStream();
    client.download("/in/from-path.bin", sink);

    assertArrayEquals(payload, Files.readAllBytes(downloaded));
    assertArrayEquals(payload, sink.toByteArray());
    assertFalse(sink.closed);
  }

  @Test
  void uploadTargetsAFilePathAndRejectsAnExistingRemoteDirectory() throws Exception {
    SftpClient client = connectToSftpServer();
    Path local = Files.writeString(tempDir.resolve("local.csv"), "data");
    Path remoteDirectory = Files.createDirectory(serverRoot.resolve("out"));

    assertThrows(DataCraftException.class, () -> client.upload(local, "out"));

    try (Stream<Path> entries = Files.list(remoteDirectory)) {
      assertEquals(0, entries.count());
    }
  }

  /**
   * The server reports {@code locked.csv} but refuses to open it, so the transfer fails only after
   * the local target has been opened. A download written in place would already have truncated it.
   */
  @Test
  void failedDownloadKeepsTheExistingLocalFile() throws Exception {
    fileSystemAccessor = refusingToOpen("locked.csv");
    SftpClient client = connectToSftpServer();
    Files.writeString(serverRoot.resolve("locked.csv"), "new\n");
    Path downloads = Files.createDirectory(tempDir.resolve("downloads"));
    Path local = Files.writeString(downloads.resolve("daily.csv"), "good\n");

    for (String remote : List.of("locked.csv", "missing.csv")) {
      assertThrows(DataCraftException.class, () -> client.download(remote, local));

      assertEquals("good\n", Files.readString(local), remote);
      try (Stream<Path> entries = Files.list(downloads)) {
        assertEquals(List.of(local), entries.toList(), remote);
      }
    }
  }

  @Test
  void reportsExistenceAndSize() throws Exception {
    SftpClient client = connectToSftpServer();
    Files.createDirectory(serverRoot.resolve("dir"));
    Files.writeString(serverRoot.resolve("dir/data.csv"), "12345");

    assertTrue(client.exists("dir"));
    assertTrue(client.exists("/dir/data.csv"));
    assertFalse(client.exists("dir/missing.csv"));
    assertEquals(5L, client.size("dir/data.csv"));
    assertThrows(DataCraftException.class, () -> client.size("dir/missing.csv"));
  }

  @Test
  void listsRegularFilesSortedByName() throws Exception {
    SftpClient client = connectToSftpServer();
    Path inbox = Files.createDirectory(serverRoot.resolve("inbox"));
    Files.writeString(inbox.resolve("b.csv"), "b");
    Files.writeString(inbox.resolve("a.csv"), "a");
    Files.createDirectory(inbox.resolve("d"));

    assertEquals(List.of("a.csv", "b.csv"), client.list("inbox"));
    assertEquals(List.of("a.csv", "b.csv"), client.list("/inbox/"));
  }

  @Test
  void makeDirectoriesIsIdempotentForAbsoluteAndRelativePaths() throws Exception {
    SftpClient client = connectToSftpServer();

    for (int attempt = 0; attempt < 2; attempt++) {
      client.makeDirectories("/x/y/z");
      client.makeDirectories("rel/p");
    }

    assertTrue(Files.isDirectory(serverRoot.resolve("x/y/z")));
    assertTrue(Files.isDirectory(serverRoot.resolve("rel/p")));
  }

  @Test
  void renamesAndDeletesFiles() throws Exception {
    SftpClient client = connectToSftpServer();
    Path moves = Files.createDirectory(serverRoot.resolve("mv"));
    Files.writeString(moves.resolve("a.csv"), "a");

    client.rename("mv/a.csv", "mv/b.csv");

    assertFalse(Files.exists(moves.resolve("a.csv")));
    assertEquals("a", Files.readString(moves.resolve("b.csv")));

    client.delete("mv/b.csv");

    assertFalse(Files.exists(moves.resolve("b.csv")));
    assertThrows(DataCraftException.class, () -> client.delete("mv/b.csv"));
  }

  /**
   * JSch would expand these names as patterns or consume the backslash as an escape and act on
   * other files. The server maps a backslash to a path separator, so {@code x\y.csv} addresses
   * {@code x/y.csv} and never {@code xy.csv}.
   */
  @Test
  void wildcardAndEscapeCharactersNeverSelectOtherFiles() throws Exception {
    SftpClient client = connectToSftpServer();
    Path in = Files.createDirectory(serverRoot.resolve("in"));
    Files.writeString(in.resolve("abc.csv"), "KEEP");
    Files.writeString(in.resolve("xy.csv"), "XY");

    assertThrows(DataCraftException.class, () -> client.delete("in/a?c.csv"));
    assertThrows(DataCraftException.class, () -> client.delete("in/x\\y.csv"));
    assertThrows(
        DataCraftException.class, () -> client.download("in/a?c.csv", new ByteArrayOutputStream()));
    assertThrows(DataCraftException.class, () -> client.size("in/ab*"));
    assertThrows(DataCraftException.class, () -> client.rename("in/a*", "in/renamed.csv"));
    assertFalse(client.exists("in/x\\y.csv"));

    assertEquals(List.of("abc.csv", "xy.csv"), client.list("in"));
    assertEquals("KEEP", Files.readString(in.resolve("abc.csv")));
    assertEquals("XY", Files.readString(in.resolve("xy.csv")));
  }

  /**
   * Needs a server filesystem that allows {@code ?} and {@code *} in names, which NTFS does not.
   */
  @Test
  @DisabledOnOs(OS.WINDOWS)
  void addressesNamesContainingWildcardCharactersLiterally() throws Exception {
    SftpClient client = connectToSftpServer();
    Path in = Files.createDirectory(serverRoot.resolve("in"));
    Files.writeString(in.resolve("a?c.csv"), "LITERAL");
    Files.writeString(in.resolve("abc.csv"), "OTHER");

    assertTrue(client.exists("in/a?c.csv"));
    assertEquals(7L, client.size("in/a?c.csv"));
    ByteArrayOutputStream sink = new ByteArrayOutputStream();
    client.download("in/a?c.csv", sink);
    assertEquals("LITERAL", sink.toString(StandardCharsets.UTF_8));
    client.delete("in/a?c.csv");
    assertEquals(List.of("abc.csv"), client.list("in"));

    Path out = Files.createDirectory(serverRoot.resolve("out"));
    Files.writeString(out.resolve("report1.csv"), "OLD");
    client.upload(bytes("NEW"), "out/report?.csv");
    assertEquals("OLD", Files.readString(out.resolve("report1.csv")));
    assertEquals("NEW", Files.readString(out.resolve("report?.csv")));
    client.rename("out/report?.csv", "out/renamed*.csv");
    assertEquals("NEW", Files.readString(out.resolve("renamed*.csv")));
    assertEquals(List.of("renamed*.csv", "report1.csv"), client.list("out"));

    assertFalse(client.exists("nomatch*"));
    assertThrows(DataCraftException.class, () -> client.delete("nomatch*"));

    client.makeDirectories("dir?x/sub");
    client.upload(bytes("F"), "dir?x/sub/f.csv");
    assertEquals(List.of("f.csv"), client.list("dir?x/sub"));
    assertEquals("F", Files.readString(serverRoot.resolve("dir?x/sub/f.csv")));
  }

  /** Windows needs Developer Mode for symbolic links, and CI runs on Linux. */
  @Test
  @DisabledOnOs(OS.WINDOWS)
  void listFollowsLinksToRegularFilesAndSkipsOtherLinks() throws Exception {
    SftpClient client = connectToSftpServer();
    Path links = Files.createDirectory(serverRoot.resolve("links"));
    Files.writeString(links.resolve("a.csv"), "a");
    Files.createDirectory(links.resolve("sub"));
    Files.createSymbolicLink(links.resolve("dirlink"), Path.of("sub"));
    Files.createSymbolicLink(links.resolve("filelink"), Path.of("a.csv"));
    Files.createSymbolicLink(links.resolve("dangling"), Path.of("missing.csv"));

    assertEquals(List.of("a.csv", "filelink"), client.list("links"));
  }

  private int startServer(boolean withSftp) throws IOException {
    server = SshServer.setUpDefaultServer();
    server.setHost(HOST);
    server.setPort(0);
    server.setKeyPairProvider(KeyPairProvider.wrap(HOST_KEY));
    server.setPasswordAuthenticator(
        (username, password, session) -> USER.equals(username) && PASSWORD.equals(password));
    if (withSftp) {
      SftpSubsystemFactory sftp = new SftpSubsystemFactory();
      sftp.setFileSystemAccessor(fileSystemAccessor);
      server.setSubsystemFactories(List.of(sftp));
    }
    server.setFileSystemFactory(new VirtualFileSystemFactory(serverRoot));
    server.start();
    return server.getPort();
  }

  private SftpClient connectToSftpServer() throws IOException {
    int port = startServer(true);
    SftpClient client = SftpClient.connect(config(port, PASSWORD), knownHosts(port, HOST_KEY));
    clients.add(client);
    return client;
  }

  private static SftpConfig config(int port, String password) {
    return new SftpConfig(HOST, port, USER, password, null, true, TIMEOUT);
  }

  private Path knownHosts(int port, KeyPair hostKey) throws IOException {
    PublicKey publicKey = hostKey.getPublic();
    return Files.writeString(
        Files.createTempFile(tempDir, "known_hosts", ""),
        "[" + HOST + "]:" + port + " " + PublicKeyEntry.toString(publicKey) + "\n");
  }

  private void awaitNoActiveSessions() throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (!server.getActiveSessions().isEmpty()) {
      if (System.nanoTime() > deadline) {
        fail("SSH sessions are still open: " + server.getActiveSessions());
      }
      Thread.sleep(20);
    }
  }

  /** Lets STAT see the named file but refuses to open it, as a permission change would. */
  private static SftpFileSystemAccessor refusingToOpen(String fileName) {
    return new SftpFileSystemAccessor() {
      @Override
      public SeekableByteChannel openFile(
          SftpSubsystemProxy subsystem,
          FileHandle fileHandle,
          Path file,
          String handle,
          Set<? extends OpenOption> options,
          FileAttribute<?>... attributes)
          throws IOException {
        if (file.getFileName().toString().equals(fileName)) {
          throw new AccessDeniedException(file.toString());
        }
        return SftpFileSystemAccessor.super.openFile(
            subsystem, fileHandle, file, handle, options, attributes);
      }
    };
  }

  private static KeyPair generateHostKey() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
      generator.initialize(new ECGenParameterSpec("secp256r1"));
      return generator.generateKeyPair();
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static InputStream bytes(String text) {
    return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
  }

  private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
    for (Throwable current = failure; current != null; current = current.getCause()) {
      if (type.isInstance(current)) {
        return true;
      }
    }
    return false;
  }

  private static String chain(Throwable failure) {
    StringBuilder text = new StringBuilder();
    for (Throwable current = failure; current != null; current = current.getCause()) {
      text.append(current).append(" <- ");
    }
    return text.toString();
  }

  private static final class TrackingInputStream extends ByteArrayInputStream {
    private boolean closed;

    TrackingInputStream(byte[] content) {
      super(content);
    }

    @Override
    public void close() throws IOException {
      closed = true;
      super.close();
    }
  }

  private static final class TrackingOutputStream extends ByteArrayOutputStream {
    private boolean closed;

    @Override
    public void close() throws IOException {
      closed = true;
      super.close();
    }
  }
}
