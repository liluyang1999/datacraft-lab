package com.example.datacraft.spark

import com.example.datacraft.spark.FileFixtures.{
  createSymbolicLinkOrCancelOnWindows,
  deleteRecursively
}
import org.apache.hadoop.conf.Configuration
import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.{Files, Path, Paths}

/**
 * Path guards without a SparkSession or a NameNode: Hadoop's DistributedFileSystem initialises and
 * qualifies paths without any RPC, so the HDFS cases run on every host.
 */
class PathGuardsSpec extends AnyFunSuite {

  private val OverlapMessage = "Input and output paths must not overlap"

  private def hdfsConf: Configuration = {
    val conf = new Configuration(false)
    conf.set("fs.defaultFS", "hdfs://localhost:8020")
    conf.setBoolean("fs.hdfs.impl.disable.cache", true)
    conf
  }

  private def localConf: Configuration = new Configuration(false)

  private def assertOverlapRejected(conf: Configuration, input: String, output: String): Unit = {
    val error =
      intercept[IllegalArgumentException](PathOverlap.requireSeparate(conf, input, output))
    assert(error.getMessage.contains(OverlapMessage), s"$input -> $output")
  }

  private def assertOutsideRoot(conf: Configuration, root: String, value: String): Unit = {
    val error =
      intercept[IllegalArgumentException](DataRoot.requireInside(conf, root, "output", value))
    assert(error.getMessage.contains("output must be inside the configured data root"), value)
  }

  /** A work directory under the module build directory, so relative spellings stay on one drive. */
  private def withWorkDir(body: Path => Unit): Unit = {
    val workDir = Files
      .createTempDirectory(Files.createDirectories(Paths.get("target")), "path-guards")
      .toAbsolutePath
    try body(workDir)
    finally deleteRecursively(workDir)
  }

  test("HDFS spellings of one location overlap after authority canonicalisation") {
    for (
      (input, output) <- Seq(
        "hdfs://localhost:8020/a" -> "hdfs://localhost/a",
        "/a"                      -> "hdfs://localhost/a/b",
        "hdfs://localhost/a/b"    -> "/a",
        "hdfs://LOCALHOST:8020/a" -> "hdfs://localhost:8020/a",
        "hdfs://localhost/a/../b" -> "/b"
      )
    ) assertOverlapRejected(hdfsConf, input, output)
  }

  test("other file systems, other ports and sibling prefixes do not overlap") {
    for (
      (input, output) <- Seq(
        "hdfs://localhost/a"      -> "file:/a",
        "hdfs://localhost:8020/a" -> "hdfs://localhost:8020/ab",
        "hdfs://localhost:8020/a" -> "hdfs://localhost:9000/a",
        "/a/b"                    -> "/a/c"
      )
    ) PathOverlap.requireSeparate(hdfsConf, input, output)
  }

  test("local equal, parent, descendant, dot-dot, relative and URI spellings overlap") {
    withWorkDir { workDir =>
      val input    = Files.writeString(workDir.resolve("input.csv"), "id\n1\n")
      val relative = Paths.get("").toAbsolutePath.relativize(input).toString
      for (
        (source, target) <- Seq(
          input.toString                      -> input.toString,
          input.toString                      -> workDir.toString,
          workDir.toString                    -> workDir.resolve("sub").toString,
          input.toString                      -> s"$workDir/missing/../input.csv",
          relative                            -> input.toString,
          input.toUri.toString                -> input.toString,
          workDir.resolve("missing").toString -> workDir.resolve("missing/child").toString
        )
      ) assertOverlapRejected(localConf, source, target)
      PathOverlap.requireSeparate(localConf, input.toString, s"$input.parquet")
      PathOverlap.requireSeparate(localConf, input.toString, workDir.resolve("out").toString)
    }
  }

  test("a local symbolic link alias overlaps its target", PosixOnly) {
    withWorkDir { workDir =>
      val input = Files.writeString(workDir.resolve("input.csv"), "id\n1\n")
      val alias = createSymbolicLinkOrCancelOnWindows(workDir.resolve("alias"), workDir)
      assertOverlapRejected(localConf, input.toString, alias.toString)
      assertOverlapRejected(localConf, input.toString, alias.resolve("input.csv").toString)
    }
  }

  test("a data root admits only paths strictly beneath it") {
    withWorkDir { workDir =>
      val root = Files.createDirectory(workDir.resolve("root"))
      Files.createDirectories(root.resolve("keep"))
      val outside = Files.createDirectory(workDir.resolve("outside"))
      for (
        value <- Seq(root.resolve("out.parquet"), root.resolve("sub/in.csv"), root.resolve("keep"))
      )
        DataRoot.requireInside(localConf, root.toString, "output", value.toString)
      DataRoot.requireInside(localConf, s"$root/", "output", root.resolve("keep").toString)
      DataRoot.requireInside(localConf, root.toString, "output", root.toUri.toString + "x")
      for (
        value <- Seq(
          root.toString,
          s"$root/",
          s"$root/keep/..",
          outside.resolve("x.parquet").toString,
          s"$root/../outside/y",
          outside.resolve("in.csv").toUri.toString,
          workDir.resolve("rootsibling/x").toString,
          s"${root}sibling"
        )
      ) assertOutsideRoot(localConf, root.toString, value)
    }
  }

  test("a data root rejects a symbolic link that leaves it", PosixOnly) {
    withWorkDir { workDir =>
      val root    = Files.createDirectory(workDir.resolve("root"))
      val outside = Files.createDirectory(workDir.resolve("outside"))
      val escape  = createSymbolicLinkOrCancelOnWindows(root.resolve("escape"), outside)
      assertOutsideRoot(localConf, root.toString, escape.resolve("x.parquet").toString)
    }
  }

  test("a data root canonicalises HDFS authorities and rejects other file systems") {
    DataRoot.requireInside(hdfsConf, "hdfs://localhost/data", "output", "/data/in.csv")
    DataRoot.requireInside(hdfsConf, "/data", "output", "hdfs://LOCALHOST:8020/data/out")
    for (
      value <- Seq(
        "hdfs://localhost/data",
        "file:/data/out",
        "hdfs://localhost:9000/data/out",
        "/data/../out"
      )
    ) assertOutsideRoot(hdfsConf, "/data", value)
  }

  test("a relative data root is rejected") {
    val error = intercept[IllegalArgumentException](
      DataRoot.requireInside(localConf, "data", "input", "/data/in.csv")
    )
    assert(error.getMessage.contains("DATACRAFT_DATA_ROOT must be an absolute path"))
  }

  test("the data root comes from DATACRAFT_DATA_ROOT, trimmed, and blank means unconfined") {
    assert(DataRoot.fromEnvironment(Map("DATACRAFT_DATA_ROOT" -> " /data ")).contains("/data"))
    for (environment <- Seq(Map.empty[String, String], Map("DATACRAFT_DATA_ROOT" -> "  ")))
      assert(DataRoot.fromEnvironment(environment).isEmpty)
  }
}
