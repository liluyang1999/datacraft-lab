package com.example.datacraft.engine;

/**
 * {@link JobExecutionRequest} parameter keys that more than one module reads: the CLI (for {@code
 * spark.master}), the Spark jobs, the plain-JVM jobs and the built-in jobs. Each job's own class
 * documents the values it accepts; a key that only one job uses belongs on that job's class.
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

  /**
   * Source path of a data job: a local path or a Hadoop-compatible URI for the Spark jobs, a local
   * file path for the plain-JVM jobs.
   */
  public static final String INPUT = "input";

  /** Destination path for a data job. */
  public static final String OUTPUT = "output";

  /** Source data format (csv, json, parquet, orc, ...). */
  public static final String INPUT_FORMAT = "inputFormat";

  /** Spark write mode (overwrite, append, ignore, error, errorifexists). */
  public static final String WRITE_MODE = "mode";

  /** Whether a CSV source carries a header row ({@code true}/{@code false}). */
  public static final String HEADER = "header";

  /**
   * CSV field delimiter, without a double quote, CR, LF or NUL: non-empty and possibly
   * multi-character for the Spark jobs, exactly one character for {@code csv-profile}.
   */
  public static final String DELIMITER = "delimiter";

  /** Optional Spark DDL schema, e.g. {@code id STRING, amount DECIMAL(22,4)}. */
  public static final String SCHEMA = "schema";

  public static final String INFER_SCHEMA = "inferSchema";
  public static final String MULTI_LINE = "multiLine";
  public static final String CSV_ESCAPE = "escape";

  /**
   * Character encoding of a CSV source read by the Spark jobs: the name of a charset the JVM
   * supports that encodes ASCII text as ASCII, such as UTF-8 (the default), GBK or ISO-8859-1.
   */
  public static final String ENCODING = "encoding";

  /** Optional nonnegative expected row count, used as a data quality gate. */
  public static final String EXPECTED_ROWS = "expectedRows";

  /** Free-form message echoed by the {@code echo} job. */
  public static final String MESSAGE = "message";
}
