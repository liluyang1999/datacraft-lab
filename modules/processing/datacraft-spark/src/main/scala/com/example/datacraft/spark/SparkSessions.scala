package com.example.datacraft.spark

import org.apache.spark.sql.SparkSession

import java.util.concurrent.locks.ReentrantLock

object SparkSessions {

  private val sessionLock = new ReentrantLock(true)

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

  /**
   * Opens a session for the duration of {@code f}, deterministically stopping it afterwards. A
   * caller interrupted while waiting must not start a new Spark context after the owner finishes.
   */
  def withSession[A](config: SparkRuntimeConfig)(f: SparkSession => A): A = {
    // A process has one SparkContext. Concurrent HTTP jobs must not stop each other's context.
    sessionLock.lockInterruptibly()
    try {
      val existing = SparkSession.getActiveSession.orElse(SparkSession.getDefaultSession)
      require(
        existing.forall(_.sparkContext.isStopped),
        "A managed Spark job requires exclusive session ownership"
      )
      val session = create(config)
      try f(session)
      finally stop(session)
    } finally sessionLock.unlock()
  }
}
