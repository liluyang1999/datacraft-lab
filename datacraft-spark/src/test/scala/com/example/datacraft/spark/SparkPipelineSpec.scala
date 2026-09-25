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
import scala.util.Using

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
   * Spark's Netty transport cannot start without working NIO selectors. On Windows the selector
   * wakeup pipe is an AF_UNIX socket, which some profile TEMP trees cannot host; the build points
   * `jdk.net.unixdomain.tmpdir` at the module target directory to avoid that. Any host that still
   * cannot open a selector cancels instead of reporting a project defect. CI fails on a cancel.
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

  /**
   * Hadoop's local file system needs `winutils.exe` (HADOOP_HOME or hadoop.home.dir) to write files
   * on Windows. Writing tests cancel on a Windows host without it; other hosts always run them.
   */
  private def requireLocalHadoopWrites(): Unit =
    if (
      System.getProperty("os.name", "").startsWith("Windows") &&
      sys.env.get("HADOOP_HOME").forall(_.isBlank) &&
      sys.props.get("hadoop.home.dir").forall(_.isBlank)
    )
      cancel(
        "Hadoop needs winutils (HADOOP_HOME or hadoop.home.dir) to write local files on Windows. " +
          "Environment limitation, not a project defect - this test runs on Linux and in CI."
      )

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
      Using.resource(Files.walk(path)) { paths =>
        paths.sorted(Comparator.reverseOrder[Path]()).forEach(entry => Files.deleteIfExists(entry))
      }
    }

  test("CSV options preserve identifiers decimals and multiline records") {
    requireNioSelectors()
    requireLocalHadoopWrites()
    val workDir = Files.createTempDirectory("datacraft-spark-precision")
    try {
      val input  = workDir.resolve("input.csv")
      val output = workDir.resolve("result.parquet")
      Files.writeString(
        input,
        "id;amount;note\n001;9007199254740993.1234;\"first\nsecond\"\n002;0.0001;ok\n"
      )
      val engine    = new JobExecutionEngine(SparkJobs.register(new JobRegistry()))
      val converted = execute(
        engine,
        "csv-to-parquet",
        Map(
          "input"     -> input.toString,
          "output"    -> output.toString,
          "delimiter" -> ";",
          "schema"    -> "id STRING, amount DECIMAL(22,4), note STRING"
        )
      )
      assert(converted.status() == JobStatus.SUCCEEDED, converted.message())
      assert(converted.metrics().get("rows") == "2")
      SparkSessions.withSession(
        SparkRuntimeConfig.local("inspect-precision").copy(master = "local[1]")
      ) { spark =>
        val rows = spark.read.parquet(output.toString).orderBy("id").collect()
        assert(rows(0).getString(0) == "001")
        assert(rows(0).getDecimal(1).toPlainString == "9007199254740993.1234")
        assert(rows(0).getString(2) == "first\nsecond")
      }
      val counted = execute(
        engine,
        "row-count",
        Map(
          "input"        -> input.toString,
          "inputFormat"  -> "csv",
          "delimiter"    -> ";",
          "expectedRows" -> "2"
        )
      )
      assert(counted.status() == JobStatus.SUCCEEDED, counted.message())
      val mismatch =
        execute(engine, "row-count", Map("input" -> output.toString, "expectedRows" -> "3"))
      assert(mismatch.status() == JobStatus.FAILED)
      assert(mismatch.message().contains("Expected 3 rows but found 2"))
    } finally deleteRecursively(workDir)
  }

  test("malformed CSV fails before overwriting previously valid output") {
    requireNioSelectors()
    val workDir = Files.createTempDirectory("datacraft-spark-invalid")
    try {
      val input  = workDir.resolve("bad.csv")
      val output = workDir.resolve("existing")
      Files.createDirectory(output)
      Files.writeString(output.resolve("sentinel"), "keep")
      Files.writeString(input, "id,amount\n1,not-a-number\n")
      val engine = new JobExecutionEngine(SparkJobs.register(new JobRegistry()))
      val result = execute(
        engine,
        "csv-to-parquet",
        Map(
          "input"  -> input.toString,
          "output" -> output.toString,
          "schema" -> "id INT, amount DECIMAL(10,2)"
        )
      )
      assert(result.status() == JobStatus.FAILED)
      assert(Files.readString(output.resolve("sentinel")) == "keep")
    } finally deleteRecursively(workDir)
  }

  test("conversion rejects overlapping paths without destroying the source") {
    requireNioSelectors()
    val workDir = Files.createTempDirectory("datacraft-spark-overlap")
    try {
      val input = workDir.resolve("input.csv")
      Files.writeString(input, "id\n1\n")
      val engine = new JobExecutionEngine(SparkJobs.register(new JobRegistry()))
      for (output <- Seq(input.toString, workDir.toString)) {
        val result =
          execute(engine, "csv-to-parquet", Map("input" -> input.toString, "output" -> output))
        assert(result.status() == JobStatus.FAILED)
        assert(Files.readString(input) == "id\n1\n")
      }
    } finally deleteRecursively(workDir)
  }

  test("spark converts csv to parquet and counts the result through the engine") {
    requireNioSelectors()
    requireLocalHadoopWrites()
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

  test("ignore preserves existing data and append reports only newly written rows") {
    requireNioSelectors()
    requireLocalHadoopWrites()
    val workDir = Files.createTempDirectory("datacraft-spark-modes")
    try {
      val input  = workDir.resolve("input.csv")
      val output = workDir.resolve("output.parquet")
      Files.writeString(input, "id\n1\n2\n")
      val engine = new JobExecutionEngine(SparkJobs.register(new JobRegistry()))
      val params = Map("input" -> input.toString, "output" -> output.toString)
      assert(execute(engine, "csv-to-parquet", params).status() == JobStatus.SUCCEEDED)
      val ignored = execute(
        engine,
        "csv-to-parquet",
        params ++ Map("mode" -> "ignore", "input" -> workDir.resolve("missing.csv").toString)
      )
      assert(ignored.status() == JobStatus.SUCCEEDED)
      assert(ignored.metrics().get("rows") == "0")
      assert(ignored.metrics().get("skipped") == "true")
      assert(
        execute(engine, "csv-to-parquet", params + ("mode" -> "errorifexists"))
          .status() == JobStatus.FAILED
      )
      val appended = execute(engine, "csv-to-parquet", params + ("mode" -> "append"))
      assert(appended.status() == JobStatus.SUCCEEDED)
      assert(appended.metrics().get("rows") == "2")
      assert(
        execute(engine, "row-count", Map("input" -> output.toString, "expectedRows" -> "4"))
          .status() == JobStatus.SUCCEEDED
      )
    } finally deleteRecursively(workDir)
  }

  test("managed jobs do not stop an externally owned Spark session") {
    requireNioSelectors()
    val session =
      SparkSessions.create(SparkRuntimeConfig.local("external-owner").copy(master = "local[1]"))
    try {
      val engine = new JobExecutionEngine(SparkJobs.register(new JobRegistry()))
      assert(
        execute(engine, "spark-version", Map.empty[String, String]).status() == JobStatus.FAILED
      )
      assert(session.range(3).count() == 3L)
    } finally session.stop()
  }
}
