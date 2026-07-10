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
    val config = SparkRuntimeConfig.fromParameters("demo", Map.empty)

    assert(config.appName == "datacraft-lab-demo")
    assert(config.master == "local[*]")
    assert(config.shufflePartitions == 8)
    assert(config.warehouseDir.isEmpty)
    assert(!config.enableHiveSupport)
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
