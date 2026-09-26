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

  /** Launcher property that carries spark-submit's `--master`. */
  private val LauncherMasterKey = "spark.master"

  /** Launcher property that carries spark-submit's shuffle partition setting. */
  private val LauncherShufflePartitionsKey = "spark.sql.shuffle.partitions"

  /**
   * Derives a runtime configuration from job execution parameters. The master and shuffle
   * partitions come from the job parameter when present, then from the launcher configuration
   * (spark-submit copies `--master` and `--conf` into system properties), then from the local
   * defaults `local[*]` and 8. The application name is trimmed and defaults to
   * `datacraft-lab-<jobName>`; it is never inherited from the launcher, which always names the
   * application after its main class.
   */
  def fromParameters(
      jobName: String,
      parameters: Map[String, String],
      launcherConf: collection.Map[String, String] = sys.props
  ): SparkRuntimeConfig =
    SparkRuntimeConfig(
      appName = parameters
        .getOrElse(ParameterKeys.SPARK_APP_NAME, s"${AppInfo.DEFAULT_APP_NAME}-$jobName")
        .trim,
      master = parameters
        .get(ParameterKeys.SPARK_MASTER)
        .map(_.trim)
        .filter(_.nonEmpty)
        .orElse(launcherConf.get(LauncherMasterKey).map(_.trim).filter(_.nonEmpty))
        .getOrElse(DefaultMaster),
      warehouseDir =
        parameters.get(ParameterKeys.SPARK_WAREHOUSE_DIR).map(_.trim).filter(_.nonEmpty),
      shufflePartitions = CsvReadOptions
        .int(parameters, ParameterKeys.SPARK_SHUFFLE_PARTITIONS, min = 1)
        .orElse(CsvReadOptions.int(launcherConf, LauncherShufflePartitionsKey, min = 1))
        .getOrElse(DefaultShufflePartitions),
      enableHiveSupport =
        CsvReadOptions.boolean(parameters, ParameterKeys.SPARK_ENABLE_HIVE, default = false)
    )
}
