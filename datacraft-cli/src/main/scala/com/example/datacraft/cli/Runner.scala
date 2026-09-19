package com.example.datacraft.cli

import com.example.datacraft.api.{EngineHttpServer, EngineHttpServerConfig, EngineJson}
import com.example.datacraft.config.DataCraftConfig
import com.example.datacraft.engine.{
  BuiltInJobs,
  JobExecutionEngine,
  JobExecutionRequest,
  JobRegistry,
  JobStatus,
  ParameterKeys
}
import com.example.datacraft.spark.SparkJobs
import com.example.datacraft.io.LocalFiles

import scala.jdk.CollectionConverters._

/**
 * Process entrypoint. Two control commands (`list-jobs`, `serve-api`) are handled directly; every
 * other command name is dispatched through the engine as a job, so the CLI, the HTTP API, and
 * Airflow all share one execution path.
 */
object Runner {

  def main(args: Array[String]): Unit =
    CliParser.parse(args.toIndexedSeq) match {
      case Some(commandLineArgs) => run(commandLineArgs)
      case None                  => sys.exit(2)
    }

  def run(args: CommandLineArgs): Unit = {
    val catalog = registry()
    args.command match {
      case "list-jobs" =>
        catalog.jobs().forEach((name, job) => println(s"$name\t${job.description()}"))
      case "serve-api" =>
        val server = startApi(args)
        sys.addShutdownHook(server.close())
        println(s"datacraft-api listening on ${server.uri("/")}")
        Thread.currentThread().join()
      case jobName =>
        if (!catalog.find(jobName).isPresent) {
          Console.err.println(s"Unknown command or job: $jobName")
          sys.exit(2)
        }
        val result = new JobExecutionEngine(catalog).execute(buildRequest(args, jobName))
        args.resultFile.foreach(path =>
          LocalFiles.writeUtf8String(path, EngineJson.result(result) + "\n")
        )
        if (args.jsonOutput) println(EngineJson.result(result))
        else println(s"${result.jobName()} ${result.status()} ${result.message()}")
        if (result.status() != JobStatus.SUCCEEDED) {
          sys.exit(1)
        }
    }
  }

  def startApi(args: CommandLineArgs): EngineHttpServer =
    EngineHttpServer.start(EngineHttpServerConfig.of(args.host, args.port), registry())

  /** Builds the full catalog: built-in core jobs plus all Spark data jobs. */
  private def registry(): JobRegistry =
    SparkJobs.register(BuiltInJobs.registry())

  /** Merges config-file entries (lowest precedence) with CLI parameters and the Spark master. */
  private[cli] def buildRequest(args: CommandLineArgs, jobName: String): JobExecutionRequest = {
    val configParameters: Map[String, String] =
      args.configFile.fold(Map.empty[String, String])(path =>
        DataCraftConfig.load(path).asMap().asScala.toMap
      )
    val explicitMaster =
      if (args.masterExplicit || args.master != "local[*]")
        Map(ParameterKeys.SPARK_MASTER -> args.master)
      else Map.empty[String, String]
    val merged = Map(
      ParameterKeys.SPARK_MASTER -> args.master
    ) ++ configParameters ++ explicitMaster ++ args.parameters
    JobExecutionRequest.of(jobName, args.lifecycle, merged.asJava)
  }
}
