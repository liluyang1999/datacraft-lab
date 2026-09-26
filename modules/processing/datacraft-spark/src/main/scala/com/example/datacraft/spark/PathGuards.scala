package com.example.datacraft.spark

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}

import java.net.URI
import java.nio.file.{Files, Paths}

/**
 * A job path qualified by its own Hadoop FileSystem. Local (`file:`) paths are also resolved
 * through the real path of their nearest existing ancestor, so relative, `..` and symbolic-link
 * spellings of one location compare equal.
 */
private[spark] final class QualifiedPath private (
    private val fileSystem: FileSystem,
    private val qualified: Path
) {

  /** The normalised path component without a trailing separator; the file-system root is "". */
  val path: String = {
    val uri      = qualified.toUri.normalize()
    val resolved =
      if (uri.getScheme == "file") QualifiedPath.resolveLocal(uri) else uri
    resolved.getPath.stripSuffix("/")
  }

  /**
   * True when both paths live on one FileSystem. Hadoop's checkPath, reached through the public
   * makeQualified, compares scheme and authority after canonicalising host case and the default
   * port, and throws "Wrong FS" for any other file system.
   */
  def sameFileSystem(other: QualifiedPath): Boolean =
    try {
      fileSystem.makeQualified(other.qualified)
      other.fileSystem.makeQualified(qualified)
      true
    } catch { case _: IllegalArgumentException => false }

  /** True when this path equals `other` or lies beneath it on the same FileSystem. */
  def isWithin(other: QualifiedPath): Boolean =
    sameFileSystem(other) && (path == other.path || path.startsWith(other.path + "/"))
}

private[spark] object QualifiedPath {

  def apply(conf: Configuration, value: String): QualifiedPath = {
    val path       = new Path(value)
    val fileSystem = path.getFileSystem(conf)
    new QualifiedPath(fileSystem, fileSystem.makeQualified(path))
  }

  private def resolveLocal(uri: URI): URI = {
    val local    = Paths.get(uri).toAbsolutePath.normalize()
    var ancestor = local
    while (ancestor != null && !Files.exists(ancestor))
      ancestor = ancestor.getParent
    if (ancestor != null)
      ancestor.toRealPath().resolve(ancestor.relativize(local)).normalize().toUri
    else uri
  }
}

/**
 * Rejects equal, ancestor and descendant input/output pairs: overwrite deletes the output first.
 */
private[spark] object PathOverlap {

  def requireSeparate(conf: Configuration, input: String, output: String): Unit = {
    val source = QualifiedPath(conf, input)
    val target = QualifiedPath(conf, output)
    require(
      !(source.isWithin(target) || target.isWithin(source)),
      "Input and output paths must not overlap"
    )
  }
}

/**
 * Confines job paths strictly beneath a configured data root. Deployments set the root through
 * [[DataRoot.EnvironmentVariable]]; when it is unset, paths are not confined.
 */
private[spark] object DataRoot {

  val EnvironmentVariable = "DATACRAFT_DATA_ROOT"

  /** The trimmed, non-blank root from `environment`, by default the process environment. */
  def fromEnvironment(environment: collection.Map[String, String] = sys.env): Option[String] =
    environment.get(EnvironmentVariable).map(_.trim).filter(_.nonEmpty)

  /**
   * Requires `value` to lie strictly beneath `root` on the same FileSystem; the root is excluded.
   */
  def requireInside(conf: Configuration, root: String, label: String, value: String): Unit = {
    require(new Path(root).isAbsolute, s"$EnvironmentVariable must be an absolute path")
    val base   = QualifiedPath(conf, root)
    val target = QualifiedPath(conf, value)
    require(
      target.isWithin(base) && target.path != base.path,
      s"$label must be inside the configured data root"
    )
  }
}
