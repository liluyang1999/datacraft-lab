package com.example.datacraft.desktop.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JobCatalogTest {

  private final JobCatalog catalog = JobCatalog.bundled(new ObjectMapper());

  @Test
  void knowsEveryBuiltInJobAndDag() {
    for (String job :
        List.of(
            "csv-profile",
            "file-checksum",
            "csv-to-parquet",
            "row-count",
            "spark-version",
            "echo",
            "noop")) {
      assertTrue(catalog.job(job).isPresent(), job);
    }
    for (String dag :
        List.of("datacraft_engine_jobs", "datacraft_spark_etl", "datacraft_sftp_ingest")) {
      assertTrue(catalog.dag(dag).isPresent(), dag);
    }
    assertTrue(catalog.job("unknown").isEmpty());
  }

  @Test
  void reportsTheRequiredKeysThatAreBlank() {
    var csvToParquet = catalog.job("csv-to-parquet").orElseThrow();
    assertEquals(List.of("input", "output"), csvToParquet.missing(Map.of("output", " ")));
    assertEquals(List.of(), csvToParquet.missing(Map.of("input", "a.csv", "output", "b")));
  }

  @Test
  void carriesDefaultsAndChoices() {
    var mode = catalog.job("csv-to-parquet").orElseThrow().param("mode").orElseThrow();
    assertEquals("overwrite", mode.defaultValue());
    assertEquals(
        List.of("overwrite", "append", "ignore", "error", "errorifexists"), mode.choices());
    var input = catalog.dag("datacraft_spark_etl").orElseThrow().param("input").orElseThrow();
    assertEquals("/opt/datacraft/data/input.csv", input.defaultValue());
  }
}
