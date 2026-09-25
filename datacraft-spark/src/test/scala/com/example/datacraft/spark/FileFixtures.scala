package com.example.datacraft.spark

import org.scalatest.Assertions

import java.nio.file.{FileSystemException, Files, Path}
import java.util.Comparator
import scala.util.Using

/** File-system helpers shared by the Spark specs. */
object FileFixtures {

  /** Deletes a directory tree without following symbolic links. */
  def deleteRecursively(path: Path): Unit =
    if (Files.exists(path)) {
      Using.resource(Files.walk(path)) { paths =>
        paths.sorted(Comparator.reverseOrder[Path]()).forEach(entry => Files.deleteIfExists(entry))
      }
    }

  /**
   * Windows creates symbolic links only with Developer Mode or elevation. That missing precondition
   * cancels the test there; on every other OS a failure still fails, so Linux CI always runs it.
   */
  def createSymbolicLinkOrCancelOnWindows(link: Path, target: Path): Path =
    try Files.createSymbolicLink(link, target)
    catch {
      case failure @ (_: FileSystemException | _: UnsupportedOperationException) =>
        if (!System.getProperty("os.name", "").startsWith("Windows")) throw failure
        Assertions.cancel(s"Symbolic links need Developer Mode or elevation on Windows: $failure")
    }
}
