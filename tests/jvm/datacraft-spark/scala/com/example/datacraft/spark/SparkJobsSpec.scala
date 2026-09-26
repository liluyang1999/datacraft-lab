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
import org.apache.spark.sql.SparkSession
import org.scalatest.funsuite.AnyFunSuite

import java.net.{URL, URLClassLoader}
import scala.jdk.CollectionConverters._

class SparkJobsSpec extends AnyFunSuite {

  /**
   * Runs a Spark job through the engine with an unusable master. Parameter validation must reject
   * the request before a SparkSession starts; otherwise the master error would win.
   */
  private def executeWithoutSpark(
      jobName: String,
      parameters: Map[String, String]
  ): JobExecutionResult =
    new JobExecutionEngine(SparkJobs.register(new JobRegistry())).execute(
      JobExecutionRequest.of(
        jobName,
        Lifecycle.DEV,
        (Map(ParameterKeys.SPARK_MASTER -> "not-a-master") ++ parameters).asJava
      )
    )

  private def assertFailsWith(result: JobExecutionResult, fragments: String*): Unit = {
    assert(result.status() == JobStatus.FAILED)
    fragments.foreach(fragment => assert(result.message().contains(fragment), result.message()))
  }

  test("engine converts checked exceptions from Scala jobs into failed results") {
    val registry = new com.example.datacraft.engine.JobRegistry()
    registry.register(new com.example.datacraft.engine.DataJob {
      override def name(): String        = "checked-failure"
      override def description(): String = "checked exception regression"
      override def run(
          request: com.example.datacraft.engine.JobExecutionRequest
      ): com.example.datacraft.engine.JobExecutionResult =
        throw new java.io.IOException("read failed")
    })
    val result = new com.example.datacraft.engine.JobExecutionEngine(registry).execute(
      com.example.datacraft.engine.JobExecutionRequest.of(
        "checked-failure",
        com.example.datacraft.common.Lifecycle.DEV,
        java.util.Map.of[String, String]()
      )
    )
    assert(result.status() == com.example.datacraft.engine.JobStatus.FAILED)
    assert(result.message() == "read failed")
  }

  test("registers all spark data jobs under stable names") {
    val registry = SparkJobs.register(new JobRegistry())

    assert(
      registry.jobNames().asScala.toList == List("csv-to-parquet", "row-count", "spark-version")
    )
  }

  test("every spark job exposes a non-blank description") {
    SparkJobs.all.foreach(job => assert(job.description().nonEmpty))
  }

  test("csv-to-parquet rejects an invalid write mode before starting Spark") {
    assertFailsWith(
      executeWithoutSpark(
        "csv-to-parquet",
        Map("input" -> "in.csv", "output" -> "out.parquet", "mode" -> "bogus")
      ),
      "Invalid write mode"
    )
  }

  test("csv-to-parquet rejects missing paths and invalid CSV options before starting Spark") {
    assertFailsWith(
      executeWithoutSpark("csv-to-parquet", Map("output" -> "out.parquet")),
      "Missing required parameter: input"
    )
    assertFailsWith(
      executeWithoutSpark("csv-to-parquet", Map("input" -> "in.csv")),
      "Missing required parameter: output"
    )
    assertFailsWith(
      executeWithoutSpark(
        "csv-to-parquet",
        Map("input" -> "in.csv", "output" -> "out.parquet", "header" -> "typo")
      ),
      "header must be true or false"
    )
  }

  test("csv-to-parquet rejects glob inputs before starting Spark") {
    for (input <- Seq("data/*", "data/{a,b}.csv", "data/f?.csv", "data/[ab].csv"))
      assertFailsWith(
        executeWithoutSpark("csv-to-parquet", Map("input" -> input, "output" -> "out.parquet")),
        "input must be a literal file or directory path; glob patterns cannot be overlap-checked"
      )
  }

  test("row-count rejects malformed expectedRows and a missing input before starting Spark") {
    for (value <- Seq("-1", "", " ", "1,000", "abc", "99999999999999999999"))
      assertFailsWith(
        executeWithoutSpark("row-count", Map("input" -> "in.parquet", "expectedRows" -> value)),
        "expectedRows"
      )
    assertFailsWith(
      executeWithoutSpark("row-count", Map("expectedRows" -> "1")),
      "Missing required parameter: input"
    )
    assertFailsWith(
      executeWithoutSpark(
        "row-count",
        Map("input" -> "in.csv", "inputFormat" -> " CSV ", "multiLine" -> "typo")
      ),
      "multiLine must be true or false"
    )
  }

  /** Sees only the platform classes, like the plain `java -jar` launch that lacks Spark. */
  private val loaderWithoutSpark =
    new URLClassLoader(Array.empty[URL], ClassLoader.getPlatformClassLoader)

  /** Finds Spark classes but cannot link them, like a broken Spark installation. */
  private val loaderWithBrokenSpark = new ClassLoader(ClassLoader.getPlatformClassLoader) {
    override def loadClass(name: String): Class[_] =
      throw new NoClassDefFoundError("org/apache/spark/internal/Logging")
  }

  /**
   * A Spark job whose validation throws `failure`; it must never reach a SparkSession. `runtime`
   * replaces the class loader that must provide Spark; None keeps the test classpath, which has it.
   */
  private def jobFailingValidation(
      failure: Throwable,
      runtime: Option[ClassLoader]
  ): AbstractSparkDataJob =
    new AbstractSparkDataJob {
      override def name(): String        = "failing-validation"
      override def description(): String = "throws from validate"
      override protected def validate(parameters: Map[String, String]): Unit = throw failure
      override protected def runSpark(
          spark: SparkSession,
          parameters: Map[String, String]
      ): Map[String, String] = fail("runSpark must not run")

      override private[spark] def runtimeClassLoader: ClassLoader =
        runtime.getOrElse(super.runtimeClassLoader)
    }

  private val emptyRequest =
    JobExecutionRequest.of("failing-validation", Lifecycle.DEV, java.util.Map.of[String, String]())

  test("without a Spark runtime a missing Spark or Hadoop class fails with a spark-submit hint") {
    for (missing <- Seq("org/apache/spark/sql/SparkSession", "org/apache/hadoop/fs/Path")) {
      val result = jobFailingValidation(new NoClassDefFoundError(missing), Some(loaderWithoutSpark))
        .run(emptyRequest)
      assert(result.status() == JobStatus.FAILED)
      assert(
        result.message() ==
          s"Spark runtime is not on the classpath (missing $missing); " +
          "launch Spark jobs with spark-submit"
      )
    }
  }

  test("a missing class under a loadable or broken Spark runtime propagates unchanged") {
    // For example, a Hadoop connector built for another Hadoop version under spark-submit.
    for (
      runtime <- Seq(None, Some(loaderWithBrokenSpark));
      name    <- Seq("org/apache/hadoop/fs/s3a/S3AFileSystem", "org/apache/spark/sql/hive/X")
    ) {
      val missing = new NoClassDefFoundError(name)
      assert(
        intercept[NoClassDefFoundError](
          jobFailingValidation(missing, runtime).run(emptyRequest)
        ) eq missing
      )
    }
  }

  test("other linkage errors are left to the engine") {
    val missing = new NoClassDefFoundError("com/example/Missing")
    for (runtime <- Seq(None, Some(loaderWithoutSpark)))
      assert(
        intercept[NoClassDefFoundError](
          jobFailingValidation(missing, runtime).run(emptyRequest)
        ) eq missing
      )
  }

  test("case variants of documented parameter names fail instead of falling back to defaults") {
    for (
      (variant, known) <- Seq(
        "inferschema"  -> "inferSchema",
        "Schema"       -> "schema",
        "expectedrows" -> "expectedRows",
        "SPARK.MASTER" -> "spark.master"
      )
    )
      assertFailsWith(
        executeWithoutSpark(
          "row-count",
          Map("input" -> "in.csv", "inputFormat" -> "csv", variant -> "1")
        ),
        s"Unknown parameter $variant",
        s"did you mean $known?"
      )
  }
}
