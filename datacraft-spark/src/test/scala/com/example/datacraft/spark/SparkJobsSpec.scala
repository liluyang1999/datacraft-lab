package com.example.datacraft.spark

import com.example.datacraft.engine.JobRegistry
import org.scalatest.funsuite.AnyFunSuite

import scala.jdk.CollectionConverters._

class SparkJobsSpec extends AnyFunSuite {

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
