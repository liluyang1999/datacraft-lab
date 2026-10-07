package com.example.datacraft.spark

import org.apache.spark.sql.{DataFrame, SparkSession}

/** Reusable, format-agnostic Spark read/write helpers shared by all data jobs. */
object DataFrames {

  def read(
      spark: SparkSession,
      format: String,
      path: String,
      options: Map[String, String] = Map.empty,
      schema: Option[String] = None
  ): DataFrame = {
    val reader = spark.read.format(format).options(options)
    schema.foreach(reader.schema)
    reader.load(path)
  }

  /**
   * Fails when a file-based read matched no data file. Spark's file listing skips names starting
   * with `_` or `.`, so such a file, a directory holding only such files and an empty directory all
   * read as zero rows once a schema is given, and an overwrite would then replace good output with
   * an empty dataset. A header-only file is a data file: it reads as zero rows and passes.
   */
  def requireDataFiles(dataFrame: DataFrame, parameter: String): DataFrame = {
    require(
      dataFrame.inputFiles.nonEmpty,
      s"$parameter contains no data files (Spark skips names starting with _ or .)"
    )
    dataFrame
  }

  def write(
      dataFrame: DataFrame,
      format: String,
      path: String,
      mode: String = "overwrite",
      options: Map[String, String] = Map.empty
  ): Unit =
    dataFrame.write.format(format).mode(mode).options(options).save(path)
}
