package com.example.datacraft.spark

import com.example.datacraft.common.Lifecycle
import com.example.datacraft.engine.{
  JobExecutionEngine,
  JobExecutionRequest,
  JobExecutionResult,
  JobRegistry,
  JobStatus
}
import org.apache.spark.sql.SparkSession
import org.scalatest.funsuite.AnyFunSuite

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.jdk.CollectionConverters._

class SparkSessionsSpec extends AnyFunSuite {

  test("an interrupted queued job finishes without waiting for or starting another Spark session") {
    val ownerEntered       = new CountDownLatch(1)
    val releaseOwner       = new CountDownLatch(1)
    val ownerFailure       = new AtomicReference[Throwable]()
    val waiterStarted      = new CountDownLatch(1)
    val waiterFinished     = new CountDownLatch(1)
    val callbackRan        = new AtomicBoolean()
    val interruptPreserved = new AtomicBoolean()
    val result             = new AtomicReference[JobExecutionResult]()
    val config = SparkRuntimeConfig.local("session-lock-owner").copy(master = "local[1]")
    val owner  = new Thread(
      () =>
        try
          SparkSessions.withSession(config) { _ =>
            ownerEntered.countDown()
            releaseOwner.await()
          }
        catch { case failure: Exception => ownerFailure.set(failure) },
      "spark-session-owner"
    )
    val registry = new JobRegistry()
    registry.register(new AbstractSparkDataJob {
      override def name(): String        = "queued"
      override def description(): String = "Checks cancellation before work starts."
      override protected def runSpark(
          spark: SparkSession,
          parameters: Map[String, String]
      ): Map[String, String] = {
        callbackRan.set(true)
        Map.empty
      }
    })
    val waiter = new Thread(
      () => {
        waiterStarted.countDown()
        try {
          result.set(
            new JobExecutionEngine(registry).execute(
              JobExecutionRequest.of(
                "queued",
                Lifecycle.DEV,
                Map("spark.master" -> "local[1]").asJava
              )
            )
          )
          interruptPreserved.set(Thread.currentThread().isInterrupted)
        } finally waiterFinished.countDown()
      },
      "spark-session-waiter"
    )
    owner.start()
    try {
      assert(
        ownerEntered.await(30, TimeUnit.SECONDS),
        s"owner did not start: ${ownerFailure.get()}"
      )
      waiter.start()
      assert(waiterStarted.await(5, TimeUnit.SECONDS))
      waiter.interrupt()
      assert(
        waiterFinished.await(5, TimeUnit.SECONDS),
        "cancellation stayed blocked behind the owner"
      )
      assert(result.get().status() == JobStatus.FAILED)
      assert(interruptPreserved.get())
      assert(!callbackRan.get())
    } finally {
      releaseOwner.countDown()
      owner.join(30000)
      waiter.join(30000)
      assert(!owner.isAlive && !waiter.isAlive, "test workers did not stop")
    }
    assert(ownerFailure.get() == null)
  }
}
