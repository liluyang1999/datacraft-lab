package com.example.datacraft.spark

import com.example.datacraft.engine.{DataJob, JobRegistry, ParameterKeys}
import org.apache.spark.sql.SparkSession

/** Reports the running Spark runtime version; the lightweight Spark smoke test. */
final class SparkVersionJob extends AbstractSparkDataJob {

  override def name(): String        = "spark-version"
  override def description(): String = "Reports the running Spark runtime version."

  override protected def runSpark(
      spark: SparkSession,
      parameters: Map[String, String]
  ): Map[String, String] =
    Map("sparkVersion" -> spark.version)

  override protected def summary(metrics: Map[String, String]): String =
    s"Spark ${metrics.getOrElse("sparkVersion", "unknown")}"
}

/** Converts a CSV dataset to Parquet. Parameters: input, output, header, delimiter, mode. */
final class CsvToParquetJob extends AbstractSparkDataJob {

  override def name(): String        = "csv-to-parquet"
  override def description(): String = "Converts a CSV dataset to Parquet."

  override protected def runSpark(
      spark: SparkSession,
      parameters: Map[String, String]
  ): Map[String, String] = {
    val input       = requireParameter(parameters, ParameterKeys.INPUT)
    val output      = requireParameter(parameters, ParameterKeys.OUTPUT)
    val mode        = parameters.getOrElse(ParameterKeys.WRITE_MODE, "overwrite")
    val readOptions = Map(
      "header"      -> parameters.getOrElse(ParameterKeys.HEADER, "true"),
      "delimiter"   -> parameters.getOrElse(ParameterKeys.DELIMITER, ","),
      "inferSchema" -> "true"
    )
    val dataFrame = DataFrames.read(spark, "csv", input, readOptions)
    val rows      = dataFrame.count()
    DataFrames.write(dataFrame, "parquet", output, mode)
    Map("rows" -> rows.toString, "input" -> input, "output" -> output)
  }

  override protected def summary(metrics: Map[String, String]): String =
    s"Wrote ${metrics.getOrElse("rows", "0")} rows to ${metrics.getOrElse("output", "")}"
}

/** Counts rows in a dataset. Parameters: input, inputFormat (default parquet). */
final class RowCountJob extends AbstractSparkDataJob {

  override def name(): String        = "row-count"
  override def description(): String = "Counts rows in a dataset."

  override protected def runSpark(
      spark: SparkSession,
      parameters: Map[String, String]
  ): Map[String, String] = {
    val input   = requireParameter(parameters, ParameterKeys.INPUT)
    val format  = parameters.getOrElse(ParameterKeys.INPUT_FORMAT, "parquet")
    val options = Map(
      "header"      -> parameters.getOrElse(ParameterKeys.HEADER, "true"),
      "inferSchema" -> "true"
    )
    val rows = DataFrames.read(spark, format, input, options).count()
    Map("rows" -> rows.toString, "input" -> input, "inputFormat" -> format)
  }

  override protected def summary(metrics: Map[String, String]): String =
    s"${metrics.getOrElse("rows", "0")} rows in ${metrics.getOrElse("input", "")}"
}

object SparkJobs {

  def all: List[DataJob] = List(new SparkVersionJob, new CsvToParquetJob, new RowCountJob)

  /** Registers every Spark data job into the registry and returns it for chaining. */
  def register(registry: JobRegistry): JobRegistry = {
    all.foreach(registry.register)
    registry
  }
}
