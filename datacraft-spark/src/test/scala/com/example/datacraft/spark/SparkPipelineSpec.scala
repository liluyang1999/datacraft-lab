package com.example.datacraft.spark

import com.example.datacraft.common.Lifecycle
import com.example.datacraft.engine.{
  JobExecutionEngine,
  JobExecutionRequest,
  JobExecutionResult,
  JobRegistry,
  JobStatus,
  ParameterKeys
}
import org.scalatest.funsuite.AnyFunSuite

import java.nio.channels.Selector
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.Comparator
import scala.jdk.CollectionConverters._

/**
 * End-to-end check that Spark really starts on the running JDK and that [[AbstractSparkDataJob]]
 * produces genuine results through the engine. The other Spark specs only assert pure
 * configuration, so this is the suite that actually exercises the Spark runtime.
 */
class SparkPipelineSpec extends AnyFunSuite {

  private val sparkParameters = Map(
    ParameterKeys.SPARK_MASTER             -> "local[1]",
    ParameterKeys.SPARK_SHUFFLE_PARTITIONS -> "1"
  )

  /**
   * Spark's Netty transport cannot start without working NIO selectors. Some hosts (notably this
   * project's Windows workstation, where `Selector.open()` fails outright) cannot provide them, so
   * cancel there instead of reporting a project defect. On Linux and in CI the suite runs for real.
   */
  private def requireNioSelectors(): Unit =
    try {
      val selector = Selector.open()
      selector.close()
    } catch {
      case failure: Throwable =>
        cancel(
          "Spark needs NIO selectors, which this host's JVM cannot open " +
            s"(${failure.getClass.getSimpleName}: ${failure.getMessage}). " +
            "Environment limitation, not a project defect - this suite runs on Linux and in CI."
        )
    }

  private def execute(
      engine: JobExecutionEngine,
      jobName: String,
      parameters: Map[String, String]
  ): JobExecutionResult =
    engine.execute(
      JobExecutionRequest.of(jobName, Lifecycle.DEV, (sparkParameters ++ parameters).asJava)
    )

  private def deleteRecursively(path: Path): Unit =
    if (Files.exists(path)) {
      Files
        .walk(path)
        .sorted(Comparator.reverseOrder[Path]())
        .forEach(entry => Files.deleteIfExists(entry))
    }

  test("spark converts csv to parquet and counts the result through the engine") {
    requireNioSelectors()
    val workDir = Files.createTempDirectory("datacraft-spark-pipeline")
    try {
      val input  = workDir.resolve("input.csv")
      val output = workDir.resolve("output.parquet")
      Files.writeString(input, "id,name\n1,alice\n2,bob\n3,carol\n", StandardCharsets.UTF_8)

      val engine = new JobExecutionEngine(SparkJobs.register(new JobRegistry()))

      val converted = execute(
        engine,
        "csv-to-parquet",
        Map(ParameterKeys.INPUT -> input.toString, ParameterKeys.OUTPUT -> output.toString)
      )
      assert(converted.status() == JobStatus.SUCCEEDED, converted.message())
      assert(converted.metrics().get("rows") == "3")

      val counted = execute(
        engine,
        "row-count",
        Map(
          ParameterKeys.INPUT        -> output.toString,
          ParameterKeys.INPUT_FORMAT -> "parquet"
        )
      )
      assert(counted.status() == JobStatus.SUCCEEDED, counted.message())
      assert(counted.metrics().get("rows") == "3")
    } finally
      deleteRecursively(workDir)
  }

  test("spark-version job reports the running spark runtime") {
    requireNioSelectors()
    val engine = new JobExecutionEngine(SparkJobs.register(new JobRegistry()))

    val result = execute(engine, "spark-version", Map.empty[String, String])

    assert(result.status() == JobStatus.SUCCEEDED, result.message())
    assert(result.metrics().get("sparkVersion").startsWith("4."))
  }
}
