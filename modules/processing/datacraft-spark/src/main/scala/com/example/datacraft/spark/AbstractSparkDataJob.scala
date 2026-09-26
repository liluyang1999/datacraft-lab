package com.example.datacraft.spark

import com.example.datacraft.engine.{
  DataJob,
  JobExecutionRequest,
  JobExecutionResult,
  ParameterKeys
}
import org.apache.spark.sql.SparkSession

import java.time.Instant
import scala.jdk.CollectionConverters._

/**
 * Adapts a Spark computation to the engine-wide [[com.example.datacraft.engine.DataJob]] contract
 * so Spark jobs are invoked uniformly through the CLI, the HTTP API, and Airflow. Subclasses
 * implement the pure computation in [[runSpark]]; this base owns session lifecycle, parameter
 * derivation, and result/metrics assembly.
 *
 * Parameters are checked before a SparkSession starts: a key that differs from a known parameter
 * name only by case is rejected, the runtime configuration is derived, and [[validate]] runs.
 * Checks that need Spark's Hadoop configuration run in [[runSpark]], after the session starts.
 *
 * When the Spark runtime itself cannot be loaded (a plain `java -jar` launch), a missing Spark or
 * Hadoop class makes the job return a FAILED result that names the class and points to
 * spark-submit. Every other failure propagates unchanged to the engine. That includes a missing
 * class when Spark itself can be loaded, such as a file-system connector built for another Hadoop.
 */
abstract class AbstractSparkDataJob extends DataJob {

  /** Executes the computation and returns metrics to attach to the successful result. */
  protected def runSpark(spark: SparkSession, parameters: Map[String, String]): Map[String, String]

  /**
   * Rejects malformed parameters before a SparkSession starts: missing required keys and invalid
   * values or combinations. Implementations reuse the parsing that [[runSpark]] relies on. Checks
   * that need Spark's Hadoop configuration, such as path confinement, path overlap or an existing
   * output, belong in [[runSpark]] and therefore run after the session starts.
   */
  protected def validate(parameters: Map[String, String]): Unit = ()

  /** Human-readable summary line; defaults to the job name. */
  protected def summary(metrics: Map[String, String]): String = name()

  /**
   * The class loader that must provide the Spark runtime; tests replace it to simulate its loss.
   */
  private[spark] def runtimeClassLoader: ClassLoader = classOf[AbstractSparkDataJob].getClassLoader

  override def run(request: JobExecutionRequest): JobExecutionResult = {
    val startedAt = request.startedAt()
    try {
      val parameters = request.parameters().asScala.toMap
      AbstractSparkDataJob.rejectCaseVariantKeys(parameters)
      val config = SparkRuntimeConfig.fromParameters(name(), parameters)
      validate(parameters)
      SparkSessions.withSession(config) { spark =>
        val metrics = runSpark(spark, parameters)
        JobExecutionResult.succeeded(
          name(),
          summary(metrics),
          metrics.asJava,
          startedAt,
          Instant.now()
        )
      }
    } catch {
      case missing: NoClassDefFoundError
          if AbstractSparkDataJob.isSparkRuntimeClass(missing) &&
            !AbstractSparkDataJob.sparkRuntimeLoads(runtimeClassLoader) =>
        JobExecutionResult.failure(
          name(),
          s"Spark runtime is not on the classpath (missing ${missing.getMessage}); " +
            "launch Spark jobs with spark-submit",
          startedAt,
          Instant.now()
        )
    }
  }

  protected def requireParameter(parameters: Map[String, String], key: String): String =
    parameters.get(key).map(_.trim).filter(_.nonEmpty) match {
      case Some(value) => value
      case None        => throw new IllegalArgumentException(s"Missing required parameter: $key")
    }
}

private object AbstractSparkDataJob {

  /** Parameter names the Spark jobs read; spelling them in another case is always a mistake. */
  private val KnownKeys: Seq[String] = Seq(
    ParameterKeys.INPUT,
    ParameterKeys.OUTPUT,
    ParameterKeys.INPUT_FORMAT,
    ParameterKeys.WRITE_MODE,
    ParameterKeys.HEADER,
    ParameterKeys.DELIMITER,
    ParameterKeys.SCHEMA,
    ParameterKeys.INFER_SCHEMA,
    ParameterKeys.MULTI_LINE,
    ParameterKeys.CSV_ESCAPE,
    ParameterKeys.EXPECTED_ROWS,
    ParameterKeys.SPARK_MASTER,
    ParameterKeys.SPARK_APP_NAME,
    ParameterKeys.SPARK_SHUFFLE_PARTITIONS,
    ParameterKeys.SPARK_WAREHOUSE_DIR,
    ParameterKeys.SPARK_ENABLE_HIVE
  )

  /** The class whose absence identifies a launch without the Spark runtime. */
  private val SparkEntryClass = "org.apache.spark.sql.SparkSession"

  /**
   * Fails on a key that matches a known name only when case is ignored, such as `inferschema`,
   * which would otherwise fall back to the default silently. Other unknown keys still pass, so one
   * `--config` file can serve several jobs.
   */
  private def rejectCaseVariantKeys(parameters: Map[String, String]): Unit =
    parameters.keys.toSeq.sorted.foreach { key =>
      KnownKeys.find(known => known != key && known.equalsIgnoreCase(key)).foreach { known =>
        throw new IllegalArgumentException(s"Unknown parameter $key; did you mean $known?")
      }
    }

  /** Spark and its Hadoop client are provided by spark-submit and absent from a plain JVM. */
  private def isSparkRuntimeClass(missing: NoClassDefFoundError): Boolean =
    Option(missing.getMessage).exists(message =>
      message.startsWith("org/apache/spark/") || message.startsWith("org/apache/hadoop/")
    )

  /**
   * True unless `loader` cannot find Spark at all. Spark classes that are present but fail to link
   * are a broken installation, not a missing runtime, so the original error stays visible.
   */
  private def sparkRuntimeLoads(loader: ClassLoader): Boolean =
    try {
      Class.forName(SparkEntryClass, false, loader)
      true
    } catch {
      case _: ClassNotFoundException => false
      case _: LinkageError           => true
    }
}
