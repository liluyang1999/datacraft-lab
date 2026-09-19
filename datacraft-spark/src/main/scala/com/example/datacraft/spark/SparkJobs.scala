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

/** Converts a CSV dataset to Parquet. Parameters: input, output, header, delimiter, mode. */
final class CsvToParquetJob extends AbstractSparkDataJob {

  override def name(): String        = "csv-to-parquet"
  override def description(): String = "Converts a CSV dataset to Parquet."

  override protected def runSpark(
      spark: SparkSession,
      parameters: Map[String, String]
  ): Map[String, String] = {
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
    requireSeparatePaths(spark, input, output)
    val outputPath   = new Path(output)
    val outputExists =
      outputPath.getFileSystem(spark.sparkContext.hadoopConfiguration).exists(outputPath)
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

  private def requireSeparatePaths(spark: SparkSession, input: String, output: String): Unit = {
    def qualified(value: String): java.net.URI = {
      val path = new Path(value)
      val fs   = path.getFileSystem(spark.sparkContext.hadoopConfiguration)
      val uri  = fs.makeQualified(path).toUri.normalize()
      if (uri.getScheme == "file") {
        val local    = java.nio.file.Paths.get(uri).toAbsolutePath.normalize()
        var ancestor = local
        while (ancestor != null && !java.nio.file.Files.exists(ancestor))
          ancestor = ancestor.getParent
        if (ancestor != null)
          ancestor.toRealPath().resolve(ancestor.relativize(local)).normalize().toUri
        else uri
      } else uri
    }
    val source                                  = qualified(input)
    val target                                  = qualified(output)
    def overlaps(a: String, b: String): Boolean = a == b || a.startsWith(b.stripSuffix("/") + "/")
    val sameFileSystem                          =
      source.getScheme == target.getScheme && source.getAuthority == target.getAuthority
    require(
      !sameFileSystem || !(overlaps(source.getPath, target.getPath) || overlaps(
        target.getPath,
        source.getPath
      )),
      "Input and output paths must not overlap"
    )
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
    val input    = requireParameter(parameters, ParameterKeys.INPUT)
    val format   = parameters.getOrElse(ParameterKeys.INPUT_FORMAT, "parquet")
    val expected = parameters.get(ParameterKeys.EXPECTED_ROWS).map(_.trim.toLong)
    require(expected.forall(_ >= 0L), "expectedRows must be nonnegative")
    val csv     = format.equalsIgnoreCase("csv")
    val options =
      if (csv) CsvReadOptions(parameters, inferSchema = false) else Map.empty[String, String]
    val data = DataFrames.read(spark, format, input, options, CsvReadOptions.schema(parameters))
    // count() can prune every CSV column and bypass FAILFAST validation. Read complete rows here.
    val rows =
      if (csv)
        data.rdd
          .mapPartitions(iterator =>
            Iterator.single(iterator.foldLeft(0L)((count, _) => Math.addExact(count, 1L)))
          )
          .fold(0L)((left, right) => Math.addExact(left, right))
      else data.count()
    expected.foreach(value => require(value == rows, s"Expected $value rows but found $rows"))
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
