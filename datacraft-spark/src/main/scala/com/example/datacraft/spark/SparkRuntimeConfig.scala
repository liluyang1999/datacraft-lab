package com.example.datacraft.spark

import com.example.datacraft.common.AppInfo
import com.example.datacraft.engine.ParameterKeys

final case class SparkRuntimeConfig(
    appName: String,
    master: String,
    warehouseDir: Option[String],
    shufflePartitions: Int,
    enableHiveSupport: Boolean
) {

  require(appName.trim.nonEmpty, "Spark appName must not be blank.")
  require(master.trim.nonEmpty, "Spark master must not be blank.")
  require(shufflePartitions > 0, "Spark shufflePartitions must be positive.")

  def sparkSettings: Map[String, String] = {
    val base = Map("spark.sql.shuffle.partitions" -> shufflePartitions.toString)
    warehouseDir.fold(base)(path => base + ("spark.sql.warehouse.dir" -> path))
  }
}

object SparkRuntimeConfig {

  val DefaultMaster: String         = "local[*]"
  val DefaultShufflePartitions: Int = 8

  def local(appName: String): SparkRuntimeConfig =
    SparkRuntimeConfig(
      appName = appName,
      master = DefaultMaster,
      warehouseDir = None,
      shufflePartitions = DefaultShufflePartitions,
      enableHiveSupport = false
    )

  /**
   * Derives a runtime configuration from job execution parameters, falling back to local defaults.
   * The application name defaults to {@code datacraft-lab-<jobName>} when not overridden.
   */
  def fromParameters(jobName: String, parameters: Map[String, String]): SparkRuntimeConfig =
    SparkRuntimeConfig(
      appName = parameters.getOrElse(
        ParameterKeys.SPARK_APP_NAME,
        s"${AppInfo.DEFAULT_APP_NAME}-$jobName"
      ),
      master = parameters.getOrElse(ParameterKeys.SPARK_MASTER, DefaultMaster),
      warehouseDir = parameters.get(ParameterKeys.SPARK_WAREHOUSE_DIR).filter(_.trim.nonEmpty),
      shufflePartitions = parameters
        .get(ParameterKeys.SPARK_SHUFFLE_PARTITIONS)
        .map(_.trim.toInt)
        .getOrElse(DefaultShufflePartitions),
      enableHiveSupport =
        CsvReadOptions.boolean(parameters, ParameterKeys.SPARK_ENABLE_HIVE, default = false)
    )
}
