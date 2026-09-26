package com.example.datacraft.spark

import com.example.datacraft.engine.ParameterKeys
import org.scalatest.funsuite.AnyFunSuite

class SparkRuntimeConfigSpec extends AnyFunSuite {

  test("local defaults use all available local cores") {
    val config = SparkRuntimeConfig.local("datacraft-test")

    assert(config.appName == "datacraft-test")
    assert(config.master == "local[*]")
    assert(config.shufflePartitions == 8)
    assert(!config.enableHiveSupport)
  }

  test("spark settings are exposed as deterministic key value pairs") {
    val config = SparkRuntimeConfig(
      appName = "datacraft-test",
      master = "local[2]",
      warehouseDir = Some("target/spark-warehouse"),
      shufflePartitions = 4,
      enableHiveSupport = true
    )

    assert(
      config.sparkSettings == Map(
        "spark.sql.shuffle.partitions" -> "4",
        "spark.sql.warehouse.dir"      -> "target/spark-warehouse"
      )
    )
  }

  test("fromParameters falls back to local defaults") {
    val config = SparkRuntimeConfig.fromParameters("demo", Map.empty, launcherConf = Map.empty)

    assert(config.appName == "datacraft-lab-demo")
    assert(config.master == "local[*]")
    assert(config.shufflePartitions == 8)
    assert(config.warehouseDir.isEmpty)
    assert(!config.enableHiveSupport)
  }

  private val launcher = Map(
    "spark.master"                 -> "spark://c:7077",
    "spark.sql.shuffle.partitions" -> " 400 ",
    "spark.app.name"               -> "com.example.datacraft.cli.Main"
  )

  test("fromParameters inherits the spark-submit master and shuffle partitions") {
    val config = SparkRuntimeConfig.fromParameters("demo", Map.empty, launcher)

    assert(config.master == "spark://c:7077")
    assert(config.shufflePartitions == 400)
    assert(config.appName == "datacraft-lab-demo")
  }

  test("explicit job parameters win over the launcher") {
    val config = SparkRuntimeConfig.fromParameters(
      "demo",
      Map(
        ParameterKeys.SPARK_MASTER             -> "local[2] ",
        ParameterKeys.SPARK_SHUFFLE_PARTITIONS -> " 4",
        ParameterKeys.SPARK_APP_NAME           -> " nightly ",
        ParameterKeys.SPARK_WAREHOUSE_DIR      -> " /data/warehouse "
      ),
      launcher
    )

    assert(config.master == "local[2]")
    assert(config.shufflePartitions == 4)
    assert(config.appName == "nightly")
    assert(config.warehouseDir.contains("/data/warehouse"))
  }

  test("a blank master parameter defers to the launcher") {
    val config =
      SparkRuntimeConfig.fromParameters("demo", Map(ParameterKeys.SPARK_MASTER -> " "), launcher)

    assert(config.master == "spark://c:7077")
  }

  test("malformed shuffle partitions fail and name the parameter") {
    for (value <- Seq("auto", "", "1,000", "99999999999", "0")) {
      val error = intercept[IllegalArgumentException](
        SparkRuntimeConfig.fromParameters(
          "demo",
          Map(ParameterKeys.SPARK_SHUFFLE_PARTITIONS -> value),
          launcher
        )
      )
      assert(error.getMessage == "spark.shufflePartitions must be a 32-bit integer >= 1")
    }
    val launcherError = intercept[IllegalArgumentException](
      SparkRuntimeConfig.fromParameters(
        "demo",
        Map.empty,
        Map("spark.sql.shuffle.partitions" -> "auto")
      )
    )
    assert(launcherError.getMessage.contains("spark.sql.shuffle.partitions"))
  }

  test("fromParameters reads overrides from job parameters") {
    val config = SparkRuntimeConfig.fromParameters(
      "demo",
      Map(
        ParameterKeys.SPARK_MASTER             -> "spark://host:7077",
        ParameterKeys.SPARK_APP_NAME           -> "custom",
        ParameterKeys.SPARK_SHUFFLE_PARTITIONS -> "16",
        ParameterKeys.SPARK_WAREHOUSE_DIR      -> "/data/warehouse",
        ParameterKeys.SPARK_ENABLE_HIVE        -> "true"
      )
    )

    assert(config.appName == "custom")
    assert(config.master == "spark://host:7077")
    assert(config.shufflePartitions == 16)
    assert(config.warehouseDir.contains("/data/warehouse"))
    assert(config.enableHiveSupport)
  }
}
