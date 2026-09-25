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
import com.example.datacraft.spark.FileFixtures.deleteRecursively
import org.scalatest.funsuite.AnyFunSuite

import java.nio.channels.Selector
import java.nio.charset.StandardCharsets
import java.nio.file.Files
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

  private val GlobRejection = "input must be a literal file or directory path"

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

  /**
   * The Spark jobs with an explicit data root, so an ambient `DATACRAFT_DATA_ROOT` cannot confine
   * the temporary paths these tests use.
   */
  private def newEngine(dataRoot: Option[String] = None): JobExecutionEngine = {
    val registry = new JobRegistry()
    List(new SparkVersionJob, new CsvToParquetJob(dataRoot), new RowCountJob)
      .foreach(registry.register)
    new JobExecutionEngine(registry)
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
      val engine    = newEngine()
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
      val engine = newEngine()
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
      val engine = newEngine()
      for (output <- Seq(input.toString, workDir.toString)) {
        val result =
          execute(engine, "csv-to-parquet", Map("input" -> input.toString, "output" -> output))
        assert(result.status() == JobStatus.FAILED)
        assert(
          result.message().contains("Input and output paths must not overlap"),
          result.message()
        )
        assert(Files.readString(input) == "id\n1\n")
      }
    } finally deleteRecursively(workDir)
  }

  test("conversion rejects output nested inside an input directory") {
    requireNioSelectors()
    val workDir = Files.createTempDirectory("datacraft-spark-descendant")
    try {
      val inputDir = Files.createDirectory(workDir.resolve("indir"))
      Files.writeString(inputDir.resolve("part.csv"), "id\n1\n")
      val engine = newEngine()
      val result = execute(
        engine,
        "csv-to-parquet",
        Map("input" -> inputDir.toString, "output" -> inputDir.resolve("sub").toString)
      )
      assert(result.status() == JobStatus.FAILED)
      assert(result.message().contains("Input and output paths must not overlap"), result.message())
      assert(Files.readString(inputDir.resolve("part.csv")) == "id\n1\n")
      assert(!Files.exists(inputDir.resolve("sub")))
    } finally deleteRecursively(workDir)
  }

  test("conversion rejects a symbolic link alias of the input directory") {
    requireNioSelectors()
    val workDir = Files.createTempDirectory("datacraft-spark-alias")
    try {
      val input = workDir.resolve("input.csv")
      Files.writeString(input, "id\n1\n")
      val alias = workDir.resolve("alias")
      FileFixtures.createSymbolicLinkOrCancelOnWindows(alias, workDir)
      val engine = newEngine()
      val result =
        execute(
          engine,
          "csv-to-parquet",
          Map("input" -> input.toString, "output" -> alias.toString)
        )
      assert(result.status() == JobStatus.FAILED)
      assert(result.message().contains("Input and output paths must not overlap"), result.message())
      assert(Files.readString(input) == "id\n1\n")
    } finally deleteRecursively(workDir)
  }

  test("conversion rejects glob inputs that could match its own output") {
    requireNioSelectors()
    val workDir = Files.createTempDirectory("datacraft-spark-pattern-output")
    try {
      Files.writeString(workDir.resolve("input.csv"), "id\n1\n")
      val output = Files.createDirectory(workDir.resolve("out.parquet"))
      Files.writeString(output.resolve("sentinel"), "keep")
      val engine = newEngine()
      val result = execute(
        engine,
        "csv-to-parquet",
        Map("input" -> s"$workDir/*", "output" -> output.toString)
      )
      assert(result.status() == JobStatus.FAILED)
      assert(result.message().contains(GlobRejection), result.message())
      assert(Files.readString(output.resolve("sentinel")) == "keep")
    } finally deleteRecursively(workDir)
  }

  test("a glob input selecting files inside the output directory cannot delete the source") {
    requireNioSelectors()
    val workDir = Files.createTempDirectory("datacraft-spark-pattern-source")
    try {
      val batch = Files.createDirectory(workDir.resolve("batch1"))
      Files.writeString(batch.resolve("raw.csv"), "id\n1\n")
      val engine = newEngine()
      val result = execute(
        engine,
        "csv-to-parquet",
        Map("input" -> s"$workDir/*/raw.csv", "output" -> batch.toString)
      )
      assert(result.status() == JobStatus.FAILED)
      assert(result.message().contains(GlobRejection), result.message())
      assert(Files.readString(batch.resolve("raw.csv")) == "id\n1\n")
    } finally deleteRecursively(workDir)
  }

  test("row-count validates complete CSV records instead of pruned columns") {
    requireNioSelectors()
    val workDir = Files.createTempDirectory("datacraft-spark-count-csv")
    try {
      val engine = newEngine()
      for (
        (content, schema) <- Seq(
          "id,amount\n1,x\n" -> "id INT, amount DECIMAL(10,2)",
          "id\n1\nabc\n"     -> "id INT"
        )
      ) {
        val input  = Files.writeString(Files.createTempFile(workDir, "bad", ".csv"), content)
        val result = execute(
          engine,
          "row-count",
          Map("input" -> input.toString, "inputFormat" -> "csv", "schema" -> schema)
        )
        assert(result.status() == JobStatus.FAILED, s"$content counted as ${result.metrics()}")
      }
    } finally deleteRecursively(workDir)
  }

  test("row-count validates complete JSON records") {
    requireNioSelectors()
    val workDir = Files.createTempDirectory("datacraft-spark-count-json")
    try {
      val engine    = newEngine()
      val truncated = Files.writeString(
        workDir.resolve("truncated.json"),
        "{\"id\":1,\"name\":\"a\"}\n{\"id\":2,\"name\":\"b\"}\n{\"id\":3,\"na\n"
      )
      val truncatedResult = execute(
        engine,
        "row-count",
        Map("input" -> truncated.toString, "inputFormat" -> "json", "expectedRows" -> "3")
      )
      assert(truncatedResult.status() == JobStatus.FAILED, truncatedResult.metrics())
      val mistyped = Files.writeString(
        workDir.resolve("mistyped.json"),
        "{\"id\":1,\"name\":\"a\"}\n{\"id\":\"oops\",\"name\":\"b\"}\n"
      )
      val mistypedResult = execute(
        engine,
        "row-count",
        Map(
          "input"        -> mistyped.toString,
          "inputFormat"  -> " JSON ",
          "schema"       -> "id INT, name STRING",
          "expectedRows" -> "2"
        )
      )
      assert(mistypedResult.status() == JobStatus.FAILED, mistypedResult.metrics())
      val valid = Files.writeString(
        workDir.resolve("valid.json"),
        "{\"id\":1,\"name\":\"a\"}\n{\"id\":2,\"name\":\"b\"}\n"
      )
      val validResult = execute(
        engine,
        "row-count",
        Map("input" -> valid.toString, "inputFormat" -> "json", "expectedRows" -> "2")
      )
      assert(validResult.status() == JobStatus.SUCCEEDED, validResult.message())
      assert(validResult.metrics().get("rows") == "2")
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

      val engine = newEngine()

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
    val engine = newEngine()

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
      val engine = newEngine()
      val params = Map("input" -> input.toString, "output" -> output.toString)
      val first  = execute(engine, "csv-to-parquet", params)
      assert(first.status() == JobStatus.SUCCEEDED, first.message())
      assert(first.message() == s"Wrote 2 rows to $output")
      val ignored = execute(
        engine,
        "csv-to-parquet",
        params ++ Map("mode" -> "ignore", "input" -> workDir.resolve("missing.csv").toString)
      )
      assert(ignored.status() == JobStatus.SUCCEEDED)
      assert(ignored.metrics().get("rows") == "0")
      assert(ignored.metrics().get("skipped") == "true")
      assert(ignored.message() == s"Skipped: $output already exists (mode=ignore)")
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

  test("a configured data root rejects conversions that leave it before touching data") {
    requireNioSelectors()
    val workDir = Files.createTempDirectory("datacraft-spark-root-reject")
    try {
      val root = Files.createDirectory(workDir.resolve("root"))
      val kept = Files.createDirectories(root.resolve("keep")).resolve("part.parquet")
      Files.writeString(kept, "keep")
      val inside  = Files.writeString(root.resolve("in.csv"), "id\n1\n")
      val outside = Files.createDirectory(workDir.resolve("outside"))
      Files.writeString(outside.resolve("in.csv"), "id\n1\n")
      val engine = newEngine(Some(root.toString))

      val intoRoot = execute(
        engine,
        "csv-to-parquet",
        Map("input" -> outside.resolve("in.csv").toString, "output" -> root.toString)
      )
      assert(intoRoot.status() == JobStatus.FAILED)
      assert(
        intoRoot.message().contains("input must be inside the configured data root"),
        intoRoot.message()
      )
      assert(Files.readString(kept) == "keep")

      val leaving = execute(
        engine,
        "csv-to-parquet",
        Map("input" -> inside.toString, "output" -> outside.resolve("o.parquet").toString)
      )
      assert(leaving.status() == JobStatus.FAILED)
      assert(
        leaving.message().contains("output must be inside the configured data root"),
        leaving.message()
      )
      assert(!Files.exists(outside.resolve("o.parquet")))

      val overRoot =
        execute(
          engine,
          "csv-to-parquet",
          Map("input" -> inside.toString, "output" -> root.toString)
        )
      assert(overRoot.status() == JobStatus.FAILED)
      assert(
        overRoot.message().contains("output must be inside the configured data root"),
        overRoot.message()
      )
      assert(Files.readString(kept) == "keep")
    } finally deleteRecursively(workDir)
  }

  test("a configured data root admits conversions inside it and no root leaves paths unconfined") {
    requireNioSelectors()
    requireLocalHadoopWrites()
    val workDir = Files.createTempDirectory("datacraft-spark-root-accept")
    try {
      val root    = Files.createDirectory(workDir.resolve("root"))
      val inside  = Files.writeString(root.resolve("in.csv"), "id\n1\n")
      val outside = Files.createDirectory(workDir.resolve("outside"))
      val within  = execute(
        newEngine(Some(root.toString)),
        "csv-to-parquet",
        Map("input" -> inside.toString, "output" -> root.resolve("out.parquet").toString)
      )
      assert(within.status() == JobStatus.SUCCEEDED, within.message())
      assert(within.metrics().get("rows") == "1")
      val unconfined = execute(
        newEngine(None),
        "csv-to-parquet",
        Map("input" -> inside.toString, "output" -> outside.resolve("out.parquet").toString)
      )
      assert(unconfined.status() == JobStatus.SUCCEEDED, unconfined.message())
      assert(unconfined.metrics().get("rows") == "1")
    } finally deleteRecursively(workDir)
  }

  test("managed jobs do not stop an externally owned Spark session") {
    requireNioSelectors()
    val session =
      SparkSessions.create(SparkRuntimeConfig.local("external-owner").copy(master = "local[1]"))
    try {
      val engine = newEngine()
      assert(
        execute(engine, "spark-version", Map.empty[String, String]).status() == JobStatus.FAILED
      )
      assert(session.range(3).count() == 3L)
    } finally session.stop()
  }
}
