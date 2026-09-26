package com.example.datacraft.spark

import com.example.datacraft.engine.{DataJob, JobRegistry, ParameterKeys}
import org.apache.spark.sql.SparkSession
import org.apache.hadoop.fs.Path
import org.apache.spark.storage.StorageLevel

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

/**
 * Converts a CSV dataset to Parquet. Parameters: input, output, mode, header, delimiter, schema,
 * inferSchema, multiLine, escape (contract: design/data-contracts.md). The input must be a literal
 * file or directory path, because glob matches cannot be overlap-checked. When `dataRoot` is set,
 * by default from the `DATACRAFT_DATA_ROOT` environment variable, input and output must lie
 * strictly beneath it.
 */
final class CsvToParquetJob(dataRoot: Option[String] = DataRoot.fromEnvironment())
    extends AbstractSparkDataJob {

  override def name(): String        = "csv-to-parquet"
  override def description(): String = "Converts a CSV dataset to Parquet."

  override protected def validate(parameters: Map[String, String]): Unit = {
    settings(parameters)
    ()
  }

  override protected def runSpark(
      spark: SparkSession,
      parameters: Map[String, String]
  ): Map[String, String] = {
    val (input, output, mode, readOptions) = settings(parameters)
    val hadoopConf                         = spark.sparkContext.hadoopConfiguration
    dataRoot.foreach { root =>
      DataRoot.requireInside(hadoopConf, root, ParameterKeys.INPUT, input)
      DataRoot.requireInside(hadoopConf, root, ParameterKeys.OUTPUT, output)
    }
    PathOverlap.requireSeparate(hadoopConf, input, output)
    val outputPath   = new Path(output)
    val outputExists = outputPath.getFileSystem(hadoopConf).exists(outputPath)
    if (outputExists && mode == "ignore") {
      return Map("rows" -> "0", "input" -> input, "output" -> output, "skipped" -> "true")
    }
    require(
      !(outputExists && Set("error", "errorifexists").contains(mode)),
      s"Output already exists: $output"
    )
    // Materialize and validate every column before overwrite; count and write share this snapshot.
    val dataFrame = DataFrames
      .read(spark, "csv", input, readOptions, CsvReadOptions.schema(parameters))
      .persist(StorageLevel.MEMORY_AND_DISK)
    try {
      val rows = dataFrame.count()
      DataFrames.write(dataFrame, "parquet", output, mode)
      Map("rows" -> rows.toString, "input" -> input, "output" -> output, "skipped" -> "false")
    } finally dataFrame.unpersist(blocking = true)
  }

  /**
   * Parses and checks every parameter; [[validate]] and [[runSpark]] share it so they cannot drift.
   * Returns the input, output, write mode and CSV reader options.
   */
  private def settings(
      parameters: Map[String, String]
  ): (String, String, String, Map[String, String]) = {
    val input  = requireParameter(parameters, ParameterKeys.INPUT)
    val output = requireParameter(parameters, ParameterKeys.OUTPUT)
    val mode   = parameters
      .getOrElse(ParameterKeys.WRITE_MODE, "overwrite")
      .trim
      .toLowerCase(java.util.Locale.ROOT)
    require(
      Set("overwrite", "append", "ignore", "error", "errorifexists").contains(mode),
      "Invalid write mode"
    )
    val readOptions = CsvReadOptions(parameters, inferSchema = true)
    // Spark expands these characters as a glob (SparkHadoopUtil.isGlobPath); Path normalises
    // Windows separators first.
    require(
      !new Path(input).toString.exists("{}[]*?\\".contains(_)),
      "input must be a literal file or directory path; glob patterns cannot be overlap-checked"
    )
    (input, output, mode, readOptions)
  }

  override protected def summary(metrics: Map[String, String]): String = {
    val output = metrics.getOrElse("output", "")
    if (metrics.get("skipped").contains("true")) s"Skipped: $output already exists (mode=ignore)"
    else s"Wrote ${metrics.getOrElse("rows", "0")} rows to $output"
  }
}

/**
 * Counts rows in a dataset. Parameters: input, inputFormat (default parquet), optional expectedRows
 * (fails on mismatch), schema, and for inputFormat=csv the shared CSV options (contract:
 * design/data-contracts.md). CSV and JSON are read in FAILFAST mode and counted over complete
 * records; other formats use Spark's reader defaults.
 */
final class RowCountJob extends AbstractSparkDataJob {

  override def name(): String        = "row-count"
  override def description(): String = "Counts rows in a dataset."

  override protected def validate(parameters: Map[String, String]): Unit = {
    settings(parameters)
    ()
  }

  override protected def runSpark(
      spark: SparkSession,
      parameters: Map[String, String]
  ): Map[String, String] = {
    val (input, format, expected, options) = settings(parameters)
    val data = DataFrames.read(spark, format, input, options, CsvReadOptions.schema(parameters))
    // count() can prune every column and bypass FAILFAST validation. Read complete rows here.
    val rows =
      if (RowCountJob.CompleteRecordFormats.contains(format.toLowerCase(java.util.Locale.ROOT)))
        data.rdd
          .mapPartitions(iterator =>
            Iterator.single(iterator.foldLeft(0L)((count, _) => Math.addExact(count, 1L)))
          )
          .fold(0L)((left, right) => Math.addExact(left, right))
      else data.count()
    expected.foreach(value => require(value == rows, s"Expected $value rows but found $rows"))
    Map("rows" -> rows.toString, "input" -> input, "inputFormat" -> format)
  }

  /**
   * Parses and checks every parameter; [[validate]] and [[runSpark]] share it so they cannot drift.
   * Returns the input, the trimmed format, the expected row count and the reader options.
   */
  private def settings(
      parameters: Map[String, String]
  ): (String, String, Option[Long], Map[String, String]) = {
    val input  = requireParameter(parameters, ParameterKeys.INPUT)
    val format = parameters.getOrElse(ParameterKeys.INPUT_FORMAT, "parquet").trim
    require(format.nonEmpty, "inputFormat must not be blank")
    val expected = CsvReadOptions.long(parameters, ParameterKeys.EXPECTED_ROWS, min = 0L)
    val options  = format.toLowerCase(java.util.Locale.ROOT) match {
      case "csv"  => CsvReadOptions(parameters, inferSchema = false)
      case "json" => Map("mode" -> "FAILFAST")
      case _      => Map.empty[String, String]
    }
    (input, format, expected, options)
  }

  override protected def summary(metrics: Map[String, String]): String =
    s"${metrics.getOrElse("rows", "0")} rows in ${metrics.getOrElse("input", "")}"
}

private object RowCountJob {

  /** Text formats whose records are parsed, so a pruned count() could skip their validation. */
  private val CompleteRecordFormats = Set("csv", "json")
}

object SparkJobs {

  def all: List[DataJob] = List(new SparkVersionJob, new CsvToParquetJob, new RowCountJob)

  /** Registers every Spark data job into the registry and returns it for chaining. */
  def register(registry: JobRegistry): JobRegistry = {
    all.foreach(registry.register)
    registry
  }
}
