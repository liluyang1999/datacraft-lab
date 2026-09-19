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

  def write(
      dataFrame: DataFrame,
      format: String,
      path: String,
      mode: String = "overwrite",
      options: Map[String, String] = Map.empty
  ): Unit =
    dataFrame.write.format(format).mode(mode).options(options).save(path)
}
