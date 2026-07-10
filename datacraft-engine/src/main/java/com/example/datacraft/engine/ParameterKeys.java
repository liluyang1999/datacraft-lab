package com.example.datacraft.engine;

/**
 * Well-known {@link JobExecutionRequest} parameter keys shared across the CLI, the Spark jobs, and
 * the HTTP API. Centralising them here keeps the bridge between layers free of duplicated magic
 * strings.
 */
public final class ParameterKeys {

  private ParameterKeys() {}

  /** Spark master URL, e.g. {@code local[*]} or {@code spark://host:7077}. */
  public static final String SPARK_MASTER = "spark.master";

  /** Optional Spark application name override. */
  public static final String SPARK_APP_NAME = "spark.appName";

  /** Spark SQL shuffle partition count. */
  public static final String SPARK_SHUFFLE_PARTITIONS = "spark.shufflePartitions";

  /** Spark SQL warehouse directory. */
  public static final String SPARK_WAREHOUSE_DIR = "spark.warehouseDir";

  /** Whether to enable Hive support on the Spark session ({@code true}/{@code false}). */
  public static final String SPARK_ENABLE_HIVE = "spark.enableHive";

  /** Source path or table for a data job. */
  public static final String INPUT = "input";

  /** Destination path or table for a data job. */
  public static final String OUTPUT = "output";

  /** Source data format (csv, json, parquet, orc, ...). */
  public static final String INPUT_FORMAT = "inputFormat";

  /** Destination data format (csv, json, parquet, orc, ...). */
  public static final String OUTPUT_FORMAT = "outputFormat";

  /** Spark write mode (overwrite, append, ignore, errorifexists). */
  public static final String WRITE_MODE = "mode";

  /** Whether a CSV source/target carries a header row ({@code true}/{@code false}). */
  public static final String HEADER = "header";

  /** Single-character field delimiter for delimited formats. */
  public static final String DELIMITER = "delimiter";

  /** Free-form message echoed by the {@code echo} job. */
  public static final String MESSAGE = "message";
}
