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
          lifecycleExplicit = true,
          master = "local[2]",
          masterExplicit = true,
          port = 9090,
          parameters = Map("input" -> "data/raw", "format" -> "csv")
        )
      )
    )
  }

  test("rejects parameters that are not key value pairs") {
    assert(CliParser.parseQuietly(Seq("--command", "inspect", "--param", "broken")).isEmpty)
    assert(CliParser.parseQuietly(Seq("--command", "inspect", "--param", "=value")).isEmpty)
  }

  test("a parameter value is everything after the first '=', may be empty, and the last one wins") {
    val parsed = CliParser.parseQuietly(
      Seq("--command", "echo") ++
        Seq("expr=a=b=c", "empty=", "key=1", "key=2").flatMap(Seq("--param", _))
    )
    assert(parsed.map(_.parameters).contains(Map("expr" -> "a=b=c", "empty" -> "", "key" -> "2")))
  }

  test("the port must be between 0 and 65535") {
    for (port <- Seq(0, 65535))
      assert(
        CliParser
          .parseQuietly(Seq("--command", "serve-api", "--port", port.toString))
          .exists(_.port == port)
      )
    for (port <- Seq("65536", "-1", "http"))
      assert(CliParser.parseQuietly(Seq("--command", "serve-api", "--port", port)).isEmpty, port)
  }

  test("--max-concurrent-runs defaults to 4 and must be at least 1") {
    assert(CliParser.parseQuietly(Seq("--command", "serve-api")).exists(_.maxConcurrentRuns == 4))
    for (limit <- Seq(1, 64))
      assert(
        CliParser
          .parseQuietly(Seq("--command", "serve-api", "--max-concurrent-runs", limit.toString))
          .exists(_.maxConcurrentRuns == limit)
      )
    for (limit <- Seq("0", "-1", "many"))
      assert(
        CliParser
          .parseQuietly(Seq("--command", "serve-api", "--max-concurrent-runs", limit))
          .isEmpty,
        limit
      )
  }

  test("an option other than --param may be given only once") {
    assert(CliParser.parseQuietly(Seq("--command", "echo", "--json", "--json")).isEmpty)
    assert(
      CliParser.parseQuietly(Seq("--command", "echo", "--master", "a", "--master", "b")).isEmpty
    )
  }

  test("invalid lifecycle is a parse error rather than an uncaught exception") {
    assert(CliParser.parseQuietly(Seq("--command", "noop", "--lifecycle", "typo")).isEmpty)
  }

  test("rejects blank commands and masters") {
    assert(CliParser.parseQuietly(Seq("--command", " ")).isEmpty)
    assert(CliParser.parseQuietly(Seq("--command", "noop", "--master", " ")).isEmpty)
  }

  test("control commands reject job-only options") {
    val jobOnly = Seq(
      Seq("--config", "x.properties"),
      Seq("--master", "local[2]"),
      Seq("--lifecycle", "prod"),
      Seq("--param", "a=b"),
      Seq("--json"),
      Seq("--result-file", "r.json")
    )
    for (command <- Seq("serve-api", "list-jobs"); extra <- jobOnly)
      assert(
        CliParser.parseQuietly(Seq("--command", command) ++ extra).isEmpty,
        s"$command $extra"
      )
  }

  test("control commands accept their own options and job commands accept every option") {
    assert(
      CliParser
        .parseQuietly(Seq("--command", "serve-api", "--host", "0.0.0.0", "--port", "8080"))
        .contains(CommandLineArgs(command = "serve-api", host = "0.0.0.0", port = 8080))
    )
    assert(CliParser.parseQuietly(Seq("--command", "list-jobs")).isDefined)
    val job = CliParser.parseQuietly(
      Seq(
        "--command",
        "echo",
        "--config",
        "x.properties",
        "--lifecycle",
        "prod",
        "--master",
        "local[2]",
        "--json",
        "--result-file",
        "r.json",
        "--param",
        "a=b"
      )
    )
    assert(job.exists(args => args.jsonOutput && args.lifecycleExplicit && args.masterExplicit))
  }
}
