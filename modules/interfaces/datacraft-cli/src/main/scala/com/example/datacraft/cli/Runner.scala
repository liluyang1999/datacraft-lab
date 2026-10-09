package com.example.datacraft.cli

import com.example.datacraft.api.{EngineHttpServer, EngineHttpServerConfig, EngineJson}
import com.example.datacraft.common.DataCraftException
import com.example.datacraft.config.DataCraftConfig
import com.example.datacraft.engine.{
  BuiltInJobs,
  JobCatalog,
  JobExecutionEngine,
  JobExecutionRequest,
  JobRegistry,
  JobStatus,
  ParameterKeys
}
import com.example.datacraft.spark.SparkJobs
import com.example.datacraft.io.LocalFiles
import com.example.datacraft.jobs.JvmJobs

import java.io.IOException
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Path

import scala.jdk.CollectionConverters._
import scala.util.Try

/**
 * Process entrypoint. Two control commands (`list-jobs`, `serve-api`) are handled directly; every
 * other command name is dispatched through the engine as a job, so the CLI, the HTTP API, and
 * Airflow all share one execution path.
 *
 * Exit codes: `0` success; `1` the job failed, its `--result-file` could not be written (the result
 * is still printed), or `serve-api` could not open its socket; `2` invalid usage, i.e. a parse
 * error (including job options given to a control command), an unknown command or job, an
 * unreadable or malformed `--config` file, or a `serve-api` that would listen off loopback without
 * a token and a data root.
 */
object Runner {

  private[cli] val ListJobs = "list-jobs"
  private[cli] val ServeApi = "serve-api"

  /** Command names handled by the CLI itself; no job may use them. */
  private[cli] val ControlCommands: Set[String] = Set(ListJobs, ServeApi)

  /** Environment variable holding the bearer token `serve-api` requires of its callers. */
  private[cli] val ApiTokenVariable = "DATACRAFT_API_TOKEN"

  /** Environment variable that confines the file jobs to one directory. */
  private[cli] val DataRootVariable = "DATACRAFT_DATA_ROOT"

  def main(args: Array[String]): Unit = {
    val code = CliParser.parse(args.toIndexedSeq).fold(2)(run)
    if (code != 0) sys.exit(code)
  }

  /** Runs one parsed command against the production catalog and returns the process exit code. */
  def run(args: CommandLineArgs): Int = execute(args)

  /** Same as [[run]] against the given catalog; `serve-api` blocks instead of returning. */
  private[cli] def execute(
      args: CommandLineArgs,
      catalog: JobCatalog = registry(),
      environment: collection.Map[String, String] = sys.env
  ): Int =
    args.command match {
      case `ListJobs` =>
        catalog.jobs().forEach((name, job) => println(s"$name\t${job.description()}"))
        0
      case `ServeApi` =>
        serverConfig(args, environment) match {
          case Left(message) =>
            Console.err.println(message)
            2
          case Right(config) =>
            listen(config, catalog) match {
              case Left(message) =>
                Console.err.println(message)
                1
              case Right(server) =>
                sys.addShutdownHook(server.close())
                println(s"datacraft-api listening on ${server.uri("/")}")
                Thread.currentThread().join()
                0
            }
        }
      case jobName if !catalog.find(jobName).isPresent =>
        Console.err.println(s"Unknown command or job: $jobName")
        2
      case jobName =>
        configParameters(args) match {
          case Left(message) =>
            Console.err.println(message)
            2
          case Right(config) => runJob(args, jobName, config, catalog)
        }
    }

  /**
   * The server configuration of `serve-api`, or the reason it must not start (exit 2).
   *
   * `DATACRAFT_API_TOKEN`, trimmed, becomes the bearer token every `/jobs` request must present; an
   * unset or blank variable leaves the API open, which is allowed on loopback only. Off loopback
   * the API needs the token, because anyone who reaches the port could otherwise run jobs, and
   * `DATACRAFT_DATA_ROOT`, because its file jobs read the paths a caller names.
   */
  private[cli] def serverConfig(
      args: CommandLineArgs,
      environment: collection.Map[String, String]
  ): Either[String, EngineHttpServerConfig] = {
    def configured(name: String): Option[String] =
      environment.get(name).map(_.trim).filter(_.nonEmpty)
    val token    = configured(ApiTokenVariable)
    val loopback = Try(InetAddress.getByName(args.host).isLoopbackAddress).getOrElse(false)
    val missing  = Seq(ApiTokenVariable -> token, DataRootVariable -> configured(DataRootVariable))
      .collect { case (name, None) => name }
    val open = EngineHttpServerConfig
      .of(args.host, args.port)
      .withMaxConcurrentRuns(args.maxConcurrentRuns)
    if (!loopback && missing.nonEmpty)
      Left(
        s"serve-api refuses --host ${args.host} without ${missing.mkString(" and ")}: off " +
          "loopback, callers must present a token and file jobs must be confined to a data " +
          s"root. Bind 127.0.0.1 or set ${if (missing.sizeIs == 1) "it" else "them"}."
      )
    else
      token.fold[Either[String, EngineHttpServerConfig]](Right(open)) { value =>
        // The rule comes from the configuration; the message never repeats the value.
        try Right(open.withApiToken(value))
        catch {
          case e: IllegalArgumentException => Left(s"Invalid $ApiTokenVariable: ${e.getMessage}")
        }
      }
  }

  def startApi(
      config: EngineHttpServerConfig,
      catalog: JobCatalog = registry()
  ): EngineHttpServer =
    EngineHttpServer.start(config, catalog)

  /** Starts the API; a port in use or a host that cannot be bound becomes a one-line error. */
  private def listen(
      config: EngineHttpServerConfig,
      catalog: JobCatalog
  ): Either[String, EngineHttpServer] =
    try Right(startApi(config, catalog))
    catch {
      case e: IOException =>
        Left(s"serve-api cannot listen on ${config.host}:${config.port}: $e")
    }

  /** Builds the full catalog: built-in core jobs, the plain-JVM data jobs and all Spark jobs. */
  private[cli] def registry(): JobRegistry =
    requireDispatchable(JvmJobs.register(SparkJobs.register(BuiltInJobs.registry())))

  /** Fails fast when a job name is shadowed by a control command and so could never run. */
  private[cli] def requireDispatchable(catalog: JobRegistry): JobRegistry = {
    catalog.jobNames().asScala.find(ControlCommands).foreach { name =>
      throw new IllegalStateException(s"Job name '$name' is reserved for a CLI control command.")
    }
    catalog
  }

  private def runJob(
      args: CommandLineArgs,
      jobName: String,
      configParameters: Map[String, String],
      catalog: JobCatalog
  ): Int = {
    val request = buildRequest(args, jobName, configParameters)
    val result  = new JobExecutionEngine(catalog).execute(request)
    // One UTF-8 line ending in LF on every platform, so stdout and the file are byte-identical.
    val json = EngineJson.result(result) + "\n"
    // stdout first, so the result is not lost when the result file cannot be written.
    if (args.jsonOutput) {
      val bytes = json.getBytes(StandardCharsets.UTF_8)
      Console.out.write(bytes, 0, bytes.length)
      Console.out.flush()
    } else println(s"${result.jobName()} ${result.status()} ${result.message()}")
    val written = args.resultFile.forall(path => writeResultFile(path, json))
    if (result.status() == JobStatus.SUCCEEDED && written) 0 else 1
  }

  private def writeResultFile(path: Path, json: String): Boolean =
    try {
      LocalFiles.writeUtf8String(path, json)
      true
    } catch {
      case e: DataCraftException =>
        Console.err.println(s"Failed to write --result-file $path: ${reason(e)}")
        false
    }

  /** Loads a `--config` file, turning any load failure into a one-line usage error. */
  private[cli] def loadConfigParameters(path: Path): Either[String, Map[String, String]] =
    try Right(DataCraftConfig.load(path).asMap().asScala.toMap)
    catch {
      case e @ (_: DataCraftException | _: IllegalArgumentException) =>
        Left(s"Invalid --config $path: ${reason(e)}")
    }

  private def configParameters(args: CommandLineArgs): Either[String, Map[String, String]] =
    args.configFile.fold[Either[String, Map[String, String]]](Right(Map.empty))(
      loadConfigParameters
    )

  /** The wrapped cause when there is one, e.g. `java.nio.file.NoSuchFileException: <path>`. */
  private def reason(e: Throwable): String = Option(e.getCause).getOrElse(e).toString

  /**
   * Merges, lowest precedence first: config-file entries, an explicit `--master`, then `--param`
   * values. When none of them sets `spark.master` the request carries none, so a Spark job keeps
   * the launcher's master (spark-submit `--master`).
   */
  private[cli] def buildRequest(
      args: CommandLineArgs,
      jobName: String,
      configParameters: Map[String, String]
  ): JobExecutionRequest = {
    val explicitMaster =
      if (args.masterExplicit || args.master != "local[*]")
        Map(ParameterKeys.SPARK_MASTER -> args.master)
      else Map.empty[String, String]
    val merged = configParameters ++ explicitMaster ++ args.parameters
    JobExecutionRequest.of(jobName, args.lifecycle, merged.asJava)
  }

  /** As above, loading `args.configFile` first; an invalid file throws IllegalArgumentException. */
  private[cli] def buildRequest(args: CommandLineArgs, jobName: String): JobExecutionRequest =
    configParameters(args) match {
      case Right(config) => buildRequest(args, jobName, config)
      case Left(message) => throw new IllegalArgumentException(message)
    }
}
