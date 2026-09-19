package com.example.datacraft.cli

import com.example.datacraft.common.{AppInfo, Lifecycle}

import java.nio.file.Path

final case class CommandLineArgs(
    command: String = "noop",
    lifecycle: Lifecycle = Lifecycle.DEV,
    master: String = "local[*]",
    host: String = "127.0.0.1",
    port: Int = 8080,
    configFile: Option[Path] = None,
    parameters: Map[String, String] = Map.empty,
    masterExplicit: Boolean = false,
    jsonOutput: Boolean = false,
    resultFile: Option[Path] = None
) {

  def appName: String = s"${AppInfo.DEFAULT_APP_NAME}-$command"
}
