package com.example.datacraft.spark

import com.example.datacraft.engine.JobRegistry
import org.scalatest.funsuite.AnyFunSuite

import scala.jdk.CollectionConverters._

class SparkJobsSpec extends AnyFunSuite {

  test("engine converts checked exceptions from Scala jobs into failed results") {
    val registry = new com.example.datacraft.engine.JobRegistry()
    registry.register(new com.example.datacraft.engine.DataJob {
      override def name(): String        = "checked-failure"
      override def description(): String = "checked exception regression"
      override def run(
          request: com.example.datacraft.engine.JobExecutionRequest
      ): com.example.datacraft.engine.JobExecutionResult =
        throw new java.io.IOException("read failed")
    })
    val result = new com.example.datacraft.engine.JobExecutionEngine(registry).execute(
      com.example.datacraft.engine.JobExecutionRequest.of(
        "checked-failure",
        com.example.datacraft.common.Lifecycle.DEV,
        java.util.Map.of[String, String]()
      )
    )
    assert(result.status() == com.example.datacraft.engine.JobStatus.FAILED)
    assert(result.message() == "read failed")
  }

  test("registers all spark data jobs under stable names") {
    val registry = SparkJobs.register(new JobRegistry())

    assert(
      registry.jobNames().asScala.toList == List("csv-to-parquet", "row-count", "spark-version")
    )
  }

  test("every spark job exposes a non-blank description") {
    SparkJobs.all.foreach(job => assert(job.description().nonEmpty))
  }
}
