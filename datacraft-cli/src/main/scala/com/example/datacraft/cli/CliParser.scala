package com.example.datacraft.cli

import com.example.datacraft.common.Lifecycle
import scopt.{DefaultOEffectSetup, OParser}

import java.nio.file.Path
import scala.util.Try

object CliParser {

  private val builder = OParser.builder[CommandLineArgs]

  private val parser = {
    import builder._

    OParser.sequence(
      programName("datacraft-lab"),
      head("datacraft-lab", "0.1.0-SNAPSHOT"),
      opt[String]("command")
        .required()
        .validate(value =>
          if (value.trim.nonEmpty) success else failure("Command must not be blank.")
        )
        .action((value, args) => args.copy(command = value))
        .text("Job name to run, or a control command: list-jobs, serve-api."),
      opt[String]("lifecycle")
        .optional()
        .validate(value =>
          if (Try(Lifecycle.fromName(value)).isSuccess) success else failure("Invalid lifecycle.")
        )
        .action((value, args) =>
          args.copy(lifecycle = Lifecycle.fromName(value), lifecycleExplicit = true)
        )
        .text(
          "Lifecycle label passed to jobs (dev|prod); built-in and Spark jobs currently ignore it."
        ),
      opt[String]("master")
        .optional()
        .validate(value =>
          if (value.trim.nonEmpty) success else failure("Master must not be blank.")
        )
        .action((value, args) => args.copy(master = value, masterExplicit = true))
        .text(
          "Spark master URL, for example local[4] or spark://host:7077; overrides spark.master " +
            "from --config. With no spark.master given anywhere, Spark jobs use spark-submit's " +
            "--master, else local[*]."
        ),
      opt[String]("host")
        .optional()
        .validate(value => if (value.trim.nonEmpty) success else failure("Host must not be blank."))
        .action((value, args) => args.copy(host = value))
        .text("Interface serve-api binds to (default 127.0.0.1; use 0.0.0.0 in containers)."),
      opt[Int]("port")
        .optional()
        .validate(value =>
          if (value >= 0 && value <= 65535) success
          else failure("Port must be between 0 and 65535.")
        )
        .action((value, args) => args.copy(port = value))
        .text("HTTP API port used by online engine commands."),
      opt[String]("config")
        .optional()
        .validate(value =>
          if (Try(Path.of(value)).isSuccess && value.trim.nonEmpty) success
          else failure("Invalid config path.")
        )
        .action((value, args) => args.copy(configFile = Some(Path.of(value))))
        .text(
          "UTF-8 java.util.Properties file (BOM optional; backslash is an escape character, so " +
            "write Windows paths with / or doubled backslashes); entries are merged verbatim " +
            "into job parameters."
        ),
      opt[Unit]("json")
        .action((_, args) => args.copy(jsonOutput = true))
        .text("Print the job result including metrics as one UTF-8 JSON line ending in LF."),
      opt[String]("result-file")
        .validate(value =>
          if (Try(Path.of(value)).isSuccess && value.trim.nonEmpty) success
          else failure("Invalid result file path.")
        )
        .action((value, args) => args.copy(resultFile = Some(Path.of(value))))
        .text(
          "Write the job result as that same JSON line, byte for byte, to a caller-owned file."
        ),
      opt[String]("param")
        .unbounded()
        .validate(validateKeyValue)
        .action { (value, args) =>
          val Array(key, parameterValue) = value.split("=", 2)
          args.copy(parameters = args.parameters + (key -> parameterValue))
        }
        .text("Repeatable key=value job parameter; overrides --config and --master."),
      checkConfig(rejectJobOptionsOnControlCommands)
    )
  }

  def parse(rawArgs: Seq[String]): Option[CommandLineArgs] =
    OParser.parse(parser, rawArgs, CommandLineArgs())

  def parseQuietly(rawArgs: Seq[String]): Option[CommandLineArgs] =
    OParser.parse(parser, rawArgs, CommandLineArgs(), silentEffects)

  private def validateKeyValue(value: String): Either[String, Unit] =
    value.split("=", 2) match {
      case Array(key, _) if key.trim.nonEmpty => Right(())
      case _ => Left(s"Parameter must use key=value syntax: $value")
    }

  /** Control commands would silently ignore job options, so they are a usage error (exit 2). */
  private def rejectJobOptionsOnControlCommands(args: CommandLineArgs): Either[String, Unit] = {
    val jobOnly = Seq(
      args.configFile.isDefined -> "--config",
      args.masterExplicit       -> "--master",
      args.lifecycleExplicit    -> "--lifecycle",
      args.parameters.nonEmpty  -> "--param",
      args.jsonOutput           -> "--json",
      args.resultFile.isDefined -> "--result-file"
    ).collect { case (true, option) => option }
    if (Runner.ControlCommands(args.command) && jobOnly.nonEmpty)
      Left(s"${args.command} does not accept job options: ${jobOnly.mkString(", ")}.")
    else Right(())
  }

  private val silentEffects = new DefaultOEffectSetup {
    override def displayToOut(msg: String): Unit = ()

    override def displayToErr(msg: String): Unit = ()

    override def reportError(msg: String): Unit = ()

    override def reportWarning(msg: String): Unit = ()

    override def terminate(exitState: Either[String, Unit]): Unit = ()
  }
}
