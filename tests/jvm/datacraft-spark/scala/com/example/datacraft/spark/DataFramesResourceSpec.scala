package com.example.datacraft.spark

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FSDataInputStream, FSInputStream, Path, RawLocalFileSystem}
import org.apache.hadoop.io.compress.{CompressionInputStream, DefaultCodec}
import org.scalatest.funsuite.AnyFunSuite

import java.io.{IOException, InputStream}
import java.net.URI
import java.util.UUID

class DataFramesResourceSpec extends AnyFunSuite {

  private def withInput(
      extension: String
  )(body: (Path, Configuration, ProbeFileSystem) => Unit): Unit = {
    val configuration = new Configuration(false)
    configuration.setClass(
      "fs.probe.impl",
      classOf[ProbeFileSystem],
      classOf[org.apache.hadoop.fs.FileSystem]
    )
    configuration.set("io.compression.codecs", classOf[FailingProbeCodec].getName)
    val path       = new Path(s"probe://${UUID.randomUUID()}/input$extension")
    val fileSystem = path.getFileSystem(configuration).asInstanceOf[ProbeFileSystem]
    try body(path, configuration, fileSystem)
    finally fileSystem.close()
  }

  test("a decompressor constructor failure still closes its raw input") {
    withInput(".fail") { (path, configuration, fileSystem) =>
      val failure = intercept[IOException](DataFrames.startsWithUtf8Mark(path, configuration))
      assert(failure.getMessage == "decompressor initialization failed")
      assert(fileSystem.input.closed, "the raw input was leaked before the read try/finally")
    }
  }

  test("cleanup failure is suppressed under a decompressor constructor failure") {
    withInput(".fail") { (path, configuration, fileSystem) =>
      fileSystem.input.failClose = true
      val failure = intercept[IOException](DataFrames.startsWithUtf8Mark(path, configuration))
      assert(failure.getMessage == "decompressor initialization failed")
      assert(failure.getSuppressed.toSeq.map(_.getMessage) == Seq("input close failed"))
      assert(fileSystem.input.closed)
    }
  }

  test("a read failure remains primary when closing the raw input also fails") {
    withInput(".csv") { (path, configuration, fileSystem) =>
      fileSystem.input.failRead = true
      fileSystem.input.failClose = true
      val failure = intercept[IOException](DataFrames.startsWithUtf8Mark(path, configuration))
      assert(failure.getMessage == "input read failed")
      assert(failure.getSuppressed.toSeq.map(_.getMessage) == Seq("input close failed"))
      assert(fileSystem.input.closed)
    }
  }

  test("the BOM probe closes successful input and detects its bytes") {
    withInput(".csv") { (path, configuration, fileSystem) =>
      assert(DataFrames.startsWithUtf8Mark(path, configuration))
      assert(fileSystem.input.closed)
    }
  }
}

/** Hadoop creates this fixture reflectively; each unique authority owns its own input. */
class ProbeFileSystem extends RawLocalFileSystem {
  val input = new ProbeInput

  override def getUri: URI = URI.create("probe:///")

  override def open(path: Path, bufferSize: Int): FSDataInputStream = new FSDataInputStream(input)
}

class ProbeInput extends FSInputStream {
  var closed           = false
  var failRead         = false
  var failClose        = false
  private var position = 0
  private val bytes    = Array(0xef, 0xbb, 0xbf)

  override def read(): Int = {
    if (failRead) throw new IOException("input read failed")
    if (position == bytes.length) -1
    else {
      val value = bytes(position)
      position += 1
      value
    }
  }

  override def seek(target: Long): Unit               = position = Math.toIntExact(target)
  override def getPos: Long                           = position.toLong
  override def seekToNewSource(target: Long): Boolean = false

  override def close(): Unit = if (!closed) {
    closed = true
    if (failClose) throw new IOException("input close failed")
  }
}

class FailingProbeCodec extends DefaultCodec {
  override def getDefaultExtension: String = ".fail"

  override def createInputStream(input: InputStream): CompressionInputStream =
    throw new IOException("decompressor initialization failed")
}
