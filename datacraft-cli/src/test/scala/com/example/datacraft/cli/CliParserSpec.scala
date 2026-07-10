package com.example.datacraft.cli

import com.example.datacraft.common.Lifecycle
import org.scalatest.funsuite.AnyFunSuite

class CliParserSpec extends AnyFunSuite {

  test("parses command lifecycle master and repeated key value parameters") {
    val parsed = CliParser.parse(
      Seq(
        "--command",
        "inspect",
        "--lifecycle",
        "prod",
        "--master",
        "local[2]",
        "--port",
        "9090",
        "--param",
        "input=data/raw",
        "--param",
        "format=csv"
      )
    )

    assert(
      parsed.contains(
        CommandLineArgs(
          command = "inspect",
          lifecycle = Lifecycle.PROD,
          master = "local[2]",
          port = 9090,
          parameters = Map("input" -> "data/raw", "format" -> "csv")
        )
      )
    )
  }

  test("rejects parameters that are not key value pairs") {
    assert(CliParser.parseQuietly(Seq("--command", "inspect", "--param", "broken")).isEmpty)
  }
}
