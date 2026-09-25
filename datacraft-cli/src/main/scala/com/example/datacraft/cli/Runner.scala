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
 * Exit codes: `0` success; `1` the job failed, or its `--result-file` could not be written (the
 * result is still printed); `2` invalid usage, i.e. a parse error (including job options given to a
 * control command), an unknown command or job, or an unreadable or malformed `--config` file.
 */
object Runner {

  private[cli] val ListJobs = "list-jobs"
  private[cli] val ServeApi = "serve-api"

  /** Command names handled by the CLI itself; no job may use them. */
  private[cli] val ControlCommands: Set[String] = Set(ListJobs, ServeApi)

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
        unconfinedNetworkApi(args.host, environment) match {
          case Some(message) =>
            Console.err.println(message)
            2
          case None =>
            val server = startApi(args, catalog)
            sys.addShutdownHook(server.close())
            println(s"datacraft-api listening on ${server.uri("/")}")
            Thread.currentThread().join()
            0
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
   * The API has no authentication and serves file jobs, so off loopback it must be confined to
   * `DATACRAFT_DATA_ROOT`; returns the refusal message when it would not be.
   */
  private[cli] def unconfinedNetworkApi(
      host: String,
      environment: collection.Map[String, String]
  ): Option[String] = {
    val confined = environment.get("DATACRAFT_DATA_ROOT").exists(_.trim.nonEmpty)
    val loopback = Try(InetAddress.getByName(host).isLoopbackAddress).getOrElse(false)
    Option.when(!loopback && !confined)(
      s"serve-api refuses --host $host without DATACRAFT_DATA_ROOT: the API is unauthenticated " +
        "and its file jobs could read any path. Bind 127.0.0.1 or set DATACRAFT_DATA_ROOT."
    )
  }

  def startApi(args: CommandLineArgs, catalog: JobCatalog = registry()): EngineHttpServer =
    EngineHttpServer.start(EngineHttpServerConfig.of(args.host, args.port), catalog)

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
