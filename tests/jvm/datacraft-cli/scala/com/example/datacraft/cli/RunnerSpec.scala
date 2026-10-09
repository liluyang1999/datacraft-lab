package com.example.datacraft.cli

import com.example.datacraft.engine.{
  BuiltInJobs,
  DataJob,
  JobExecutionRequest,
  JobExecutionResult,
  JobRegistry
}

import java.io.{ByteArrayOutputStream, PrintStream}
import java.net.{InetAddress, ServerSocket}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.time.Duration
import java.util.Comparator
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import scala.jdk.CollectionConverters._

import org.scalatest.concurrent.{Signaler, ThreadSignaler, TimeLimits}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.time.{Seconds, Span}

class RunnerSpec extends AnyFunSuite with TimeLimits {

  // Interrupts a test body that outlives its failAfter limit.
  private implicit val signaler: Signaler = ThreadSignaler

  private val token = "0123456789abcdef0123456789abcdef"

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

  test(
    "--result-file without --json gets the JSON line, with parents created and the file replaced"
  ) {
    withTempDir { dir =>
      val resultFile = dir.resolve("nested").resolve("deeper").resolve("result.json")
      def echo(message: String): (Int, Array[Byte], String) = captured(
        Runner.run(
          CommandLineArgs(
            command = "echo",
            resultFile = Some(resultFile),
            parameters = Map("message" -> message)
          )
        )
      )
      val (code, output, _) = echo("first")
      assert(code == 0)
      assert(new String(output, StandardCharsets.UTF_8).trim == "echo SUCCEEDED first")
      assert(Files.readString(resultFile).contains("\"message\":\"first\""))

      assert(echo("second")._1 == 0)
      val replaced = Files.readString(resultFile)
      assert(replaced.contains("\"message\":\"second\"") && !replaced.contains("first"), replaced)
      assert(replaced.endsWith("}\n") && replaced.count(_ == '\n') == 1, replaced)
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
    // The whole catalog, so a module that stops registering its jobs fails here.
    assert(
      names == List(
        "csv-profile",
        "csv-to-parquet",
        "echo",
        "file-checksum",
        "noop",
        "row-count",
        "spark-version"
      )
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

  test("serve-api off loopback requires a token and a data root and names what is missing") {
    val everywhere = CommandLineArgs(command = "serve-api", host = "0.0.0.0", port = 0)
    def refusal(environment: Map[String, String]): Option[String] =
      Runner.serverConfig(everywhere, environment).left.toOption
    val reason = "off loopback, callers must present a token and file jobs must be confined to " +
      "a data root. Bind 127.0.0.1 or set"

    assert(
      refusal(Map.empty).contains(
        "serve-api refuses --host 0.0.0.0 without DATACRAFT_API_TOKEN and DATACRAFT_DATA_ROOT: " +
          s"$reason them."
      )
    )
    assert(
      refusal(Map("DATACRAFT_DATA_ROOT" -> "/data")).contains(
        s"serve-api refuses --host 0.0.0.0 without DATACRAFT_API_TOKEN: $reason it."
      )
    )
    assert(
      refusal(Map("DATACRAFT_API_TOKEN" -> token)).contains(
        s"serve-api refuses --host 0.0.0.0 without DATACRAFT_DATA_ROOT: $reason it."
      )
    )
    // A blank value is an unset one.
    assert(
      refusal(Map("DATACRAFT_API_TOKEN" -> " ", "DATACRAFT_DATA_ROOT" -> "\t")) ==
        refusal(Map.empty)
    )

    val (code, output, errors) = serveApi(everywhere, Map.empty)
    assert(code == 2)
    assert(errors.startsWith("serve-api refuses --host 0.0.0.0 without DATACRAFT_API_TOKEN"))
    assert(errors.trim.linesIterator.size == 1 && output.isEmpty)
  }

  test("serve-api takes its token and run limit from the environment and the command line") {
    // The values are trimmed, so a CRLF .env file does not put a carriage return into the token.
    val configured = Runner.serverConfig(
      CommandLineArgs(command = "serve-api", host = "0.0.0.0", port = 8080, maxConcurrentRuns = 2),
      Map("DATACRAFT_API_TOKEN" -> s" $token\r\n", "DATACRAFT_DATA_ROOT" -> "/data")
    )
    assert(
      configured.map(config =>
        (config.host, config.port, config.maxConcurrentRuns, config.apiToken)
      ) == Right(("0.0.0.0", 8080, 2, java.util.Optional.of(token)))
    )

    // On loopback the API may stay open, but a token that is set is required there too.
    for (host <- Seq("127.0.0.1", "::1", "localhost")) {
      val loopback = CommandLineArgs(command = "serve-api", host = host)
      assert(Runner.serverConfig(loopback, Map.empty).exists(_.apiToken.isEmpty), host)
      assert(
        Runner
          .serverConfig(loopback, Map("DATACRAFT_API_TOKEN" -> token))
          .exists(_.apiToken == java.util.Optional.of(token)),
        host
      )
    }
    assert(
      Runner
        .serverConfig(CommandLineArgs(command = "serve-api"), Map.empty)
        .exists(_.maxConcurrentRuns == 4)
    )
  }

  test("a malformed DATACRAFT_API_TOKEN stops serve-api without printing the value") {
    val secret                 = "too short and with spaces"
    val (code, output, errors) =
      serveApi(
        CommandLineArgs(command = "serve-api", port = 0),
        Map("DATACRAFT_API_TOKEN" -> secret)
      )
    assert(code == 2)
    assert(
      errors.trim == "Invalid DATACRAFT_API_TOKEN: API token must be 32 to 512 characters from " +
        "A-Z a-z 0-9 - . _ ~ + / with optional trailing =."
    )
    assert(!errors.contains(secret) && output.isEmpty)
  }

  test("serve-api reports a port it cannot bind in one line and returns 1") {
    val occupied = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    try {
      val port                   = occupied.getLocalPort
      val (code, output, errors) =
        serveApi(CommandLineArgs(command = "serve-api", port = port), Map.empty)
      assert(code == 1)
      assert(errors.startsWith(s"serve-api cannot listen on 127.0.0.1:$port: "), errors)
      assert(errors.contains("BindException"), errors)
      assert(!errors.contains("\tat ") && errors.trim.linesIterator.size == 1, errors)
      assert(output.isEmpty)
    } finally occupied.close()
  }

  test("runner starts the embedded API, which requires the token from the environment") {
    val config = Runner
      .serverConfig(
        CommandLineArgs(command = "serve-api", port = 0),
        Map("DATACRAFT_API_TOKEN" -> token)
      )
      .getOrElse(fail("serve-api refused a loopback configuration"))
    val client = HttpClient.newHttpClient()
    val server = Runner.startApi(config, registryOf())
    try {
      // Bounded: a server that accepts a request and never answers must fail the test.
      def get(path: String): HttpRequest.Builder =
        HttpRequest.newBuilder(server.uri(path)).timeout(Duration.ofSeconds(30)).GET()
      def status(path: String, headers: (String, String)*): Int = {
        val request = get(path)
        headers.foreach { case (name, value) => request.header(name, value) }
        client.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode()
      }
      val health = client.send(get("/health").build(), HttpResponse.BodyHandlers.ofString())

      assert(health.statusCode() == 200)
      assert(health.body().contains("\"status\":\"UP\""))
      assert(status("/jobs") == 401)
      assert(status("/jobs", "Authorization" -> s"Bearer $token") == 200)
    } finally {
      // The server first: closing it while the client is idle avoids the JDK's full grace wait.
      server.close()
      client.close()
    }
  }

  /**
   * Runs `body` with stdout and stderr captured; returns its exit code, stdout bytes and stderr.
   */
  /**
   * Runs `serve-api` where it is expected to stop. A server that starts instead blocks forever, so
   * the call is bounded: such a regression fails its test and does not hang the build.
   */
  private def serveApi(
      args: CommandLineArgs,
      environment: Map[String, String]
  ): (Int, Array[Byte], String) =
    failAfter(Span(30, Seconds))(captured(Runner.execute(args, registryOf(), environment)))

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
