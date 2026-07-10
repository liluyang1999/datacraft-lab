package com.example.datacraft.spark

import com.example.datacraft.engine.{DataJob, JobExecutionRequest, JobExecutionResult}
import org.apache.spark.sql.SparkSession

import java.time.Instant
import scala.jdk.CollectionConverters._

/**
 * Adapts a Spark computation to the engine-wide [[com.example.datacraft.engine.DataJob]] contract
 * so Spark jobs are invoked uniformly through the CLI, the HTTP API, and Airflow. Subclasses
 * implement the pure computation in [[runSpark]]; this base owns session lifecycle, parameter
 * derivation, and result/metrics assembly. Failures propagate and are converted to a failed result
 * by the engine.
 */
abstract class AbstractSparkDataJob extends DataJob {

  /** Executes the computation and returns metrics to attach to the successful result. */
  protected def runSpark(spark: SparkSession, parameters: Map[String, String]): Map[String, String]

  /** Human-readable summary line; defaults to the job name. */
  protected def summary(metrics: Map[String, String]): String = name()

  override def run(request: JobExecutionRequest): JobExecutionResult = {
    val startedAt  = request.startedAt()
    val parameters = request.parameters().asScala.toMap
    val config     = SparkRuntimeConfig.fromParameters(name(), parameters)
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
  }

  protected def requireParameter(parameters: Map[String, String], key: String): String =
    parameters.get(key).map(_.trim).filter(_.nonEmpty) match {
      case Some(value) => value
      case None        => throw new IllegalArgumentException(s"Missing required parameter: $key")
    }
}
