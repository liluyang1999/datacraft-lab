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

  test("multi-character delimiters are accepted unchanged") {
    assert(CsvReadOptions(Map("delimiter" -> "||"), inferSchema = true)("delimiter") == "||")
  }

  test("named integer parsers reject blank or malformed values without echoing them") {
    for (value <- Seq("1,000", "", " ", "-1", "abc", "99999999999999999999")) {
      val error = intercept[IllegalArgumentException](
        CsvReadOptions.long(Map("expectedRows" -> value), "expectedRows", min = 0L)
      )
      assert(error.getMessage == "expectedRows must be a 64-bit integer >= 0")
    }
    for (value <- Seq("1,000", "", "0", "99999999999")) {
      val error = intercept[IllegalArgumentException](
        CsvReadOptions.int(Map("partitions" -> value), "partitions", min = 1)
      )
      assert(error.getMessage == "partitions must be a 32-bit integer >= 1")
    }
    assert(CsvReadOptions.long(Map("expectedRows" -> " 42 "), "expectedRows", 0L).contains(42L))
    assert(CsvReadOptions.long(Map("expectedRows" -> "0"), "expectedRows", 0L).contains(0L))
    assert(CsvReadOptions.int(Map("partitions" -> "42"), "partitions", 1).contains(42))
    assert(CsvReadOptions.long(Map.empty, "expectedRows", 0L).isEmpty)
    assert(CsvReadOptions.int(Map.empty, "partitions", 1).isEmpty)
  }
}
