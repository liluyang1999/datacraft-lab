package com.example.datacraft.spark

import org.scalatest.funsuite.AnyFunSuite

class CsvReadOptionsSpec extends AnyFunSuite {
  test("CSV reading defaults to strict RFC quoting and multiline records") {
    val options = CsvReadOptions(Map.empty, inferSchema = true)
    assert(options("mode") == "FAILFAST")
    assert(options("multiLine") == "true")
    assert(options("escape") == "\"")
    assert(options("enforceSchema") == "false")
    assert(CsvReadOptions(Map.empty, inferSchema = false)("inferSchema") == "false")
  }

  test("invalid CSV options and hive flags fail explicitly") {
    for (key <- Seq("header", "inferSchema", "multiLine"))
      intercept[IllegalArgumentException](CsvReadOptions(Map(key -> "typo"), inferSchema = true))
    for (delimiter <- Seq("", "\n", "\r", "\"", "\u0000"))
      intercept[IllegalArgumentException](
        CsvReadOptions(Map("delimiter" -> delimiter), inferSchema = true)
      )
    intercept[IllegalArgumentException](
      SparkRuntimeConfig.fromParameters("test", Map("spark.enableHive" -> "typo"))
    )
  }

  test("explicit CSV values and schema are honored") {
    val options = CsvReadOptions(
      Map(
        "header"      -> "FALSE",
        "multiLine"   -> "false",
        "inferSchema" -> "false",
        "delimiter"   -> "\t",
        "escape"      -> "\\"
      ),
      inferSchema = true
    )
    assert(options("header") == "false")
    assert(options("delimiter") == "\t")
    assert(options("escape") == "\\")
    assert(CsvReadOptions.schema(Map("schema" -> "id STRING")).contains("id STRING"))
  }
}
