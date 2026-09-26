package com.example.datacraft.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.datacraft.common.DataCraftException;
import com.example.datacraft.common.Lifecycle;
import com.example.datacraft.engine.DataJob;
import com.example.datacraft.engine.JobExecutionRequest;
import com.example.datacraft.engine.JobExecutionResult;
import com.example.datacraft.engine.JobStatus;
import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DataRootConfinementTest {

  private static final String OUTSIDE = "input must be inside the configured data root";

  @TempDir Path tempDir;

  private Path root;
  private Path outside;

  @BeforeEach
  void createRootAndOutsideFile() throws IOException {
    root = Files.createDirectory(tempDir.resolve("root"));
    Files.createDirectory(root.resolve("sub"));
    Files.writeString(root.resolve("sub/in.csv"), "id\n1\n");
    outside = Files.writeString(tempDir.resolve("outside.csv"), "id\n1\n2\n");
  }

  @Test
  void readsAFileStrictlyInsideTheDataRoot() {
    for (DataJob job : jobs(Optional.of(root))) {
      JobExecutionResult direct = run(job, root.resolve("sub/in.csv").toString());
      JobExecutionResult dotted = run(job, root.resolve("sub/../sub/./in.csv").toString());

      assertEquals(JobStatus.SUCCEEDED, direct.status(), job.name());
      assertEquals(direct.metrics().get("sha256"), dotted.metrics().get("sha256"), job.name());
    }
  }

  @Test
  void rejectsAnInputOutsideTheDataRoot() {
    for (DataJob job : jobs(Optional.of(root))) {
      assertRejected(job, outside.toString(), OUTSIDE);
    }
  }

  @Test
  void rejectsADotDotEscapeFromTheDataRoot() {
    for (DataJob job : jobs(Optional.of(root))) {
      assertRejected(job, root.resolve("sub/../../outside.csv").toString(), OUTSIDE);
    }
  }

  @Test
  void rejectsTheRootItselfAndASiblingThatSharesItsPrefix() throws IOException {
    Path sibling = Files.createDirectory(tempDir.resolve("rootsibling"));
    Files.writeString(sibling.resolve("in.csv"), "id\n1\n");

    for (DataJob job : jobs(Optional.of(root))) {
      assertRejected(job, root.toString(), OUTSIDE);
      assertRejected(job, sibling.resolve("in.csv").toString(), OUTSIDE);
    }
  }

  @Test
  @Tag("posix-only")
  void rejectsASymbolicLinkInsideTheRootThatPointsOutside() throws IOException {
    Path outsideDir = Files.createDirectory(tempDir.resolve("outside-dir"));
    Files.writeString(outsideDir.resolve("secret.csv"), "id\n1\n");
    createSymbolicLinkOrAbortOnWindows(root.resolve("escape"), outsideDir);
    createSymbolicLinkOrAbortOnWindows(root.resolve("link.csv"), outsideDir.resolve("secret.csv"));

    for (DataJob job : jobs(Optional.of(root))) {
      for (Path link : List.of(root.resolve("escape/secret.csv"), root.resolve("link.csv"))) {
        IllegalArgumentException error =
            assertThrows(IllegalArgumentException.class, () -> run(job, link.toString()));
        // The LocalStorageService guard fires, not the lexical inside-the-root check.
        assertTrue(
            error.getMessage().startsWith("Storage path must not"),
            job.name() + ": " + error.getMessage());
      }
    }
  }

  @Test
  @Tag("posix-only")
  void readsThroughADataRootThatIsItselfASymbolicLink() throws IOException {
    Path link = tempDir.resolve("root-link");
    createSymbolicLinkOrAbortOnWindows(link, root);

    for (DataJob job : jobs(Optional.of(link))) {
      assertEquals(
          JobStatus.SUCCEEDED,
          run(job, link.resolve("sub/in.csv").toString()).status(),
          job.name());
      assertRejected(job, outside.toString(), OUTSIDE);
    }
  }

  @Test
  void reportsMissingFilesAndDirectoriesInsideTheRoot() {
    Path missing = root.resolve("sub/missing.csv");

    for (DataJob job : jobs(Optional.of(root))) {
      assertRejected(job, missing.toString(), "Input file does not exist: " + missing);
      assertRejected(
          job,
          root.resolve("sub").toString(),
          "Input is not a regular file: " + root.resolve("sub"));
    }
  }

  @Test
  void failsWithoutCreatingAMissingDataRoot() {
    Path missingRoot = tempDir.resolve("not-created");

    for (DataJob job : jobs(Optional.of(missingRoot))) {
      DataCraftException error =
          assertThrows(
              DataCraftException.class, () -> run(job, missingRoot.resolve("in.csv").toString()));
      assertEquals("DATACRAFT_DATA_ROOT must name an existing directory", error.getMessage());
    }
    assertFalse(Files.exists(missingRoot));
  }

  @Test
  void rejectsARelativeOrNonDirectoryDataRoot() {
    for (DataJob job : jobs(Optional.of(Path.of("relative-root")))) {
      DataCraftException error =
          assertThrows(DataCraftException.class, () -> run(job, outside.toString()));
      assertEquals("DATACRAFT_DATA_ROOT must be an absolute path", error.getMessage());
    }
    for (DataJob job : jobs(Optional.of(outside))) {
      DataCraftException error =
          assertThrows(DataCraftException.class, () -> run(job, outside.toString()));
      assertEquals("DATACRAFT_DATA_ROOT must name an existing directory", error.getMessage());
    }
  }

  @Test
  void readsAnyPathWhenNoDataRootIsConfigured() {
    for (DataJob job : jobs(Optional.empty())) {
      assertEquals(JobStatus.SUCCEEDED, run(job, outside.toString()).status(), job.name());
    }
  }

  @Test
  void interpretsTheEnvironmentValueTrimmedWithBlankMeaningUnconfined() {
    DataJob unset = new FileChecksumJob(InputFiles.fromEnvironmentValue(null));
    DataJob blank = new FileChecksumJob(InputFiles.fromEnvironmentValue(" \t "));
    DataJob padded = new FileChecksumJob(InputFiles.fromEnvironmentValue("  " + root + "  "));

    assertEquals(JobStatus.SUCCEEDED, run(unset, outside.toString()).status());
    assertEquals(JobStatus.SUCCEEDED, run(blank, outside.toString()).status());
    assertEquals(JobStatus.SUCCEEDED, run(padded, root.resolve("sub/in.csv").toString()).status());
    assertRejected(padded, outside.toString(), OUTSIDE);
  }

  @Test
  void rejectsANullDataRootOptional() {
    assertThrows(IllegalArgumentException.class, () -> new CsvProfileJob(null));
    assertThrows(IllegalArgumentException.class, () -> new FileChecksumJob((Optional<Path>) null));
  }

  private static List<DataJob> jobs(Optional<Path> dataRoot) {
    return List.of(new CsvProfileJob(dataRoot), new FileChecksumJob(dataRoot));
  }

  private static JobExecutionResult run(DataJob job, String input) {
    return job.run(JobExecutionRequest.of(job.name(), Lifecycle.DEV, Map.of("input", input)));
  }

  private static void assertRejected(DataJob job, String input, String message) {
    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> run(job, input), job.name());
    assertEquals(message, error.getMessage(), job.name());
  }

  /**
   * Windows creates symbolic links only with Developer Mode or elevation. That missing precondition
   * aborts the test there; on every other OS a failure still fails, so Linux CI always runs it.
   */
  private static void createSymbolicLinkOrAbortOnWindows(Path link, Path target)
      throws IOException {
    try {
      Files.createSymbolicLink(link, target);
    } catch (FileSystemException | UnsupportedOperationException exception) {
      if (!System.getProperty("os.name", "").startsWith("Windows")) {
        throw exception;
      }
      Assumptions.abort("Symbolic links need Developer Mode or elevation on Windows: " + exception);
    }
  }
}
