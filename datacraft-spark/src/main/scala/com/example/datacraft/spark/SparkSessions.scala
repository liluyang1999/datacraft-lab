package com.example.datacraft.spark

import org.apache.spark.sql.SparkSession

object SparkSessions {

  def create(config: SparkRuntimeConfig): SparkSession = {
    val builder = SparkSession.builder().appName(config.appName).master(config.master)

    config.sparkSettings.foreach { case (key, value) =>
      builder.config(key, value)
    }

    val configuredBuilder =
      if (config.enableHiveSupport) builder.enableHiveSupport()
      else builder

    configuredBuilder.getOrCreate()
  }

  def stop(session: SparkSession): Unit =
    if (session != null) {
      session.stop()
    }

  /** Opens a session for the duration of {@code f}, deterministically stopping it afterwards. */
  def withSession[A](config: SparkRuntimeConfig)(f: SparkSession => A): A = {
    val session = create(config)
    try f(session)
    finally stop(session)
  }
}
