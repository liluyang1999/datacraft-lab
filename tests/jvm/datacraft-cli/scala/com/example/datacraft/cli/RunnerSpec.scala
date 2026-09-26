package com.example.datacraft.cli

import com.example.datacraft.engine.{
  BuiltInJobs,
  DataJob,
  JobExecutionRequest,
  JobExecutionResult,
  JobRegistry
}

import java.io.{ByteArrayOutputStream, PrintStream}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.Comparator
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import scala.jdk.CollectionConverters._

import org.scalatest.funsuite.AnyFunSuite

class RunnerSpec extends AnyFunSuite {

  test("explicit CLI master overrides config while explicit job parameters take precedence") {
    val file = java.nio.file.Files.createTempFile("datacraft-config-", ".properties")
    try {
      java.nio.file.Files.writeString(file, "spark.master=local[3]\n")
      val defaults = CommandLineArgs(configFile = Some(file))
      assert(Runner.buildRequest(defaults, "noop").parameters().get("spark.master") == "local[3]")
      assert(
        Runner
          .buildRequest(defaults.copy(master = "local[*]", masterExplicit = true), "noop")
          .parameters()
          .get("spark.master") == "local[*]"
      )
      assert(
        Runner
          .buildRequest(
            defaults.copy(master = "local[2]", parameters = Map("spark.master" -> "local[4]")),
            "noop"
          )
          .parameters()
          .get("spark.master") == "local[4]"
      )
    } finally java.nio.file.Files.deleteIfExists(file)
  }

  test("without --master, config or --param no spark.master is injected") {
    assert(!Runner.buildRequest(CommandLineArgs(), "noop").parameters().containsKey("spark.master"))
    assert(
      Runner
        .buildRequest(CommandLineArgs(master = "local[*]", masterExplicit = true), "noop")
        .parameters()
        .get("spark.master") == "local[*]"
    )
  }

  test("config file delimiter reaches the job verbatim, like --param") {
    withTempDir { dir =>
      val file = dir.resolve("tsv.properties")
      Files.writeString(file, "delimiter=\\t\n")
      val fromConfig = Runner.buildRequest(CommandLineArgs(configFile = Some(file)), "noop")
      val fromParam  =
        Runner.buildRequest(CommandLineArgs(parameters = Map("delimiter" -> "\t")), "noop")
      assert(fromConfig.parameters().get("delimiter") == "\t")
      assert(fromConfig.parameters() == fromParam.parameters())
    }
  }

  test("structured output and result file contain the same job metrics") {
    withTempDir { dir =>
      val file   = dir.resolve("result.json")
      val output = new ByteArrayOutputStream()
      // A GBK stream stands in for a stdout whose charset is not UTF-8.
      val code = Console.withOut(new PrintStream(output, true, "GBK")) {
        Runner.run(
          CommandLineArgs(
            command = "echo",
            jsonOutput = true,
            resultFile = Some(file),
            parameters = Map("message" -> "a\"b\n中文")
          )
        )
      }
      assert(code == 0)
      val bytes = output.toByteArray
      assert(bytes.sameElements(Files.readAllBytes(file)))
      val json = strictUtf8(bytes)
      assert(json.endsWith("}\n") && json.count(_ == '\n') == 1 && !json.contains("\r"))
      assert(json.contains("中文"))
      assert(json.contains("\"status\":\"SUCCEEDED\""))
      assert(json.contains("durationMillis"))
    }
  }

  test("a CRLF child JVM prints the same LF-terminated UTF-8 bytes it writes to --result-file") {
    withTempDir { dir =>
      val config     = dir.resolve("job.properties")
      val resultFile = dir.resolve("result.json")
      val stdout     = dir.resolve("stdout.bin")
      val stderr     = dir.resolve("stderr.txt")
      // The config file is read as UTF-8, so the message survives any child locale or code page.
      Files.writeString(config, "message=中文\n", StandardCharsets.UTF_8)
      val command = List(
        Path.of(System.getProperty("java.home"), "bin", "java").toString,
        "-Dline.separator=\r\n",
        "-cp",
        System.getProperty("java.class.path"),
        "com.example.datacraft.cli.Runner",
        "--command",
        "echo",
        "--json",
        "--config",
        config.toString,
        "--result-file",
        resultFile.toString
      )
      val process = new ProcessBuilder(command.asJava)
        .redirectOutput(stdout.toFile)
        .redirectError(stderr.toFile)
        .start()
      try assert(process.waitFor(2, TimeUnit.MINUTES), "child JVM did not exit")
      finally process.destroyForcibly()
      val errors = new String(Files.readAllBytes(stderr), StandardCharsets.UTF_8)
      assert(process.exitValue() == 0, errors)
      val bytes = Files.readAllBytes(stdout)
      assert(bytes.sameElements(Files.readAllBytes(resultFile)))
      val json = strictUtf8(bytes)
      assert(json.endsWith("}\n") && !json.contains("\r"))
      assert(json.contains("\"message\":\"中文\""))
    }
  }

  test("runner executes built-in engine jobs by command name") {
    val (code, output, _) =
      captured(
        Runner.run(CommandLineArgs(command = "echo", parameters = Map("message" -> "hello")))
      )

    val text = new String(output, StandardCharsets.UTF_8)
    assert(code == 0)
    assert(text.contains("SUCCEEDED"))
    assert(text.contains("hello"))
  }

  test("an unknown job returns 2 without writing a result file") {
    withTempDir { dir =>
      val resultFile        = dir.resolve("result.json")
      val (code, _, errors) = captured(
        Runner.run(CommandLineArgs(command = "does-not-exist", resultFile = Some(resultFile)))
      )
      assert(code == 2)
      assert(errors.contains("Unknown command or job: does-not-exist"))
      assert(Files.notExists(resultFile))
    }
  }

  test("a FAILED job returns 1 and still delivers the result to stdout and the result file") {
    withTempDir { dir =>
      val resultFile = dir.resolve("result.json")
      val catalog    = registryOf(stubJob("always-fails") { request =>
        JobExecutionResult.failure("always-fails", "boom", request.startedAt(), request.startedAt())
      })
      val (code, output, _) = captured(
        Runner.execute(
          CommandLineArgs(
            command = "always-fails",
            jsonOutput = true,
            resultFile = Some(resultFile)
          ),
          catalog
        )
      )
      assert(code == 1)
      assert(new String(output, StandardCharsets.UTF_8).contains("\"status\":\"FAILED\""))
      assert(Files.readString(resultFile).contains("\"status\":\"FAILED\""))
    }
  }

  test("a job that throws returns 1") {
    val catalog           = registryOf(stubJob("throws")(_ => throw new RuntimeException("kaboom")))
    val (code, output, _) = captured(Runner.execute(CommandLineArgs(command = "throws"), catalog))
    assert(code == 1)
    assert(new String(output, StandardCharsets.UTF_8).contains("throws FAILED kaboom"))
  }

  test("an unreadable or malformed --config returns 2 before the job runs") {
    withTempDir { dir =>
      val backslash = '\\'
      val malformed = dir.resolve("malformed.properties")
      Files.writeString(malformed, s"key=${backslash}uZZZZ\n")
      val orphan = dir.resolve("orphan.properties")
      Files.writeString(orphan, "=orphan\n")
      val directory = Files.createDirectory(dir.resolve("config-dir"))
      val missing   = dir.resolve("missing.properties")

      for (config <- Seq(missing, malformed, orphan, directory)) {
        val runs       = new AtomicInteger()
        val resultFile = dir.resolve("result.json")
        val catalog    = registryOf(stubJob("counted") { request =>
          runs.incrementAndGet()
          JobExecutionResult.success("counted", "ran", request.startedAt(), request.startedAt())
        })
        val (code, _, errors) = captured(
          Runner.execute(
            CommandLineArgs(
              command = "counted",
              configFile = Some(config),
              resultFile = Some(resultFile)
            ),
            catalog
          )
        )
        assert(code == 2, config)
        assert(errors.startsWith(s"Invalid --config $config"), errors)
        assert(!errors.contains("\tat "), errors)
        assert(errors.trim.linesIterator.size == 1, errors)
        assert(runs.get() == 0, config)
        assert(Files.notExists(resultFile), config)
      }
    }
  }

  test("an unwritable --result-file returns 1 after the result is printed") {
    withTempDir { dir =>
      val (code, output, errors) = captured(
        Runner.run(CommandLineArgs(command = "echo", jsonOutput = true, resultFile = Some(dir)))
      )
      assert(code == 1)
      assert(new String(output, StandardCharsets.UTF_8).contains("\"status\":\"SUCCEEDED\""))
      assert(errors.startsWith(s"Failed to write --result-file $dir"), errors)
      assert(!errors.contains("\tat "), errors)
    }
  }

  test("spark-version rejects invalid Spark settings before creating a session") {
    val (code, output, _) = captured(
      Runner.run(
        CommandLineArgs(
          command = "spark-version",
          jsonOutput = true,
          parameters = Map("spark.shufflePartitions" -> "0")
        )
      )
    )
    val json = new String(output, StandardCharsets.UTF_8)
    assert(code == 1)
    assert(json.contains("\"status\":\"FAILED\""))
    // Without Spark on this classpath, reaching session creation would fail with a linkage error.
    assert(json.contains("shufflePartitions"), json)
  }

  test("list-jobs prints the production catalog and returns 0") {
    val (code, output, _) = captured(Runner.run(CommandLineArgs(command = "list-jobs")))
    val names             = new String(output, StandardCharsets.UTF_8).linesIterator
      .map(_.takeWhile(_ != '\t'))
      .toList
    assert(code == 0)
    assert(names == Runner.registry().jobNames().asScala.toList)
    assert(
      Set("csv-to-parquet", "echo", "noop", "row-count", "spark-version").subsetOf(names.toSet)
    )
  }

  test("production job names are dispatchable by the CLI, the API and Airflow") {
    val names = Runner.registry().jobNames().asScala
    assert(names.nonEmpty)
    assert(names.forall(name => !name.contains('/') && !Runner.ControlCommands(name)), names)
  }

  test("a job named after a control command is rejected") {
    for (reserved <- Runner.ControlCommands) {
      val catalog = registryOf(stubJob(reserved) { request =>
        JobExecutionResult.success(reserved, "", request.startedAt(), request.startedAt())
      })
      val error = intercept[IllegalStateException](Runner.requireDispatchable(catalog))
      assert(error.getMessage.contains(reserved))
    }
    val ordinary = BuiltInJobs.registry()
    assert(Runner.requireDispatchable(ordinary) eq ordinary)
  }

  test("serve-api off loopback requires a data root because the API is unauthenticated") {
    val refusal = Runner.unconfinedNetworkApi("0.0.0.0", Map.empty)
    assert(refusal.exists(_.contains("DATACRAFT_DATA_ROOT")))
    assert(Runner.unconfinedNetworkApi("0.0.0.0", Map("DATACRAFT_DATA_ROOT" -> " ")).isDefined)
    assert(Runner.unconfinedNetworkApi("0.0.0.0", Map("DATACRAFT_DATA_ROOT" -> "/data")).isEmpty)
    for (host <- Seq("127.0.0.1", "::1", "localhost"))
      assert(Runner.unconfinedNetworkApi(host, Map.empty).isEmpty, host)

    val err  = new ByteArrayOutputStream()
    val code = Console.withErr(err) {
      Runner.execute(
        CommandLineArgs(command = "serve-api", host = "0.0.0.0", port = 0),
        environment = Map.empty
      )
    }
    assert(code == 2)
    assert(err.toString(StandardCharsets.UTF_8).contains("serve-api refuses --host 0.0.0.0"))
  }

  test("runner starts embedded API for online engine access") {
    val server = Runner.startApi(CommandLineArgs(command = "serve-api", port = 0))
    try {
      val response = HttpClient
        .newHttpClient()
        .send(
          HttpRequest.newBuilder(server.uri("/health")).GET().build(),
          HttpResponse.BodyHandlers.ofString()
        )

      assert(response.statusCode() == 200)
      assert(response.body().contains("\"status\":\"UP\""))
    } finally
      server.close()
  }

  /**
   * Runs `body` with stdout and stderr captured; returns its exit code, stdout bytes and stderr.
   */
  private def captured(body: => Int): (Int, Array[Byte], String) = {
    val output = new ByteArrayOutputStream()
    val errors = new ByteArrayOutputStream()
    val code   = Console.withOut(new PrintStream(output, true, StandardCharsets.UTF_8)) {
      Console.withErr(new PrintStream(errors, true, StandardCharsets.UTF_8))(body)
    }
    (code, output.toByteArray, errors.toString(StandardCharsets.UTF_8))
  }

  private def strictUtf8(bytes: Array[Byte]): String =
    StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString

  private def stubJob(jobName: String)(body: JobExecutionRequest => JobExecutionResult): DataJob =
    new DataJob {
      override def name(): String                                        = jobName
      override def description(): String                                 = s"Test job $jobName."
      override def run(request: JobExecutionRequest): JobExecutionResult = body(request)
    }

  private def registryOf(jobs: DataJob*): JobRegistry = {
    val registry = new JobRegistry()
    jobs.foreach(registry.register)
    registry
  }

  private def withTempDir(body: Path => Unit): Unit = {
    val dir = Files.createTempDirectory("datacraft-runner-")
    try body(dir)
    finally {
      val paths = Files.walk(dir)
      try paths.sorted(Comparator.reverseOrder[Path]()).forEach(path => Files.deleteIfExists(path))
      finally paths.close()
    }
  }
}
