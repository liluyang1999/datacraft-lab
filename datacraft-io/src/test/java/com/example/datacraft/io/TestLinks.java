package com.example.datacraft.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;

/** Creates the filesystem links that the storage and file-helper tests need. */
final class TestLinks {

  private TestLinks() {}

  /**
   * Windows creates symbolic links only with Developer Mode or elevation. That missing precondition
   * aborts the test there; on every other OS a failure still fails, so Linux CI always runs it.
   */
  static Path createSymbolicLinkOrAbortOnWindows(Path link, Path target) throws IOException {
    try {
      return Files.createSymbolicLink(link, target);
    } catch (FileSystemException | UnsupportedOperationException exception) {
      if (!isWindows()) {
        throw exception;
      }
      return Assumptions.abort(
          "Symbolic links need Developer Mode or elevation on Windows: " + exception);
    }
  }

  /**
   * Creates an NTFS directory junction, which Windows allows without privilege. Callers delete it
   * with {@link Files#deleteIfExists} before the {@code @TempDir} cleanup, which would otherwise
   * walk into the junction target.
   */
  static Path createJunction(Path link, Path target) throws IOException, InterruptedException {
    Process process =
        new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
            .redirectErrorStream(true)
            .start();
    // mklink prints one short line, far below the pipe buffer, so waiting before reading cannot
    // block the process, and the timeout still applies to a hung mklink.
    if (!process.waitFor(30, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      fail("mklink /J did not finish for " + link);
    }
    byte[] output = process.getInputStream().readAllBytes();
    Charset console =
        Charset.forName(System.getProperty("native.encoding", "UTF-8"), Charset.defaultCharset());
    assertEquals(0, process.exitValue(), () -> "mklink /J failed: " + new String(output, console));
    return link;
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").startsWith("Windows");
  }
}
