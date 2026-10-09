package com.example.datacraft.spark

import com.example.datacraft.common.Lifecycle
import com.example.datacraft.engine.{
  JobExecutionEngine,
  JobExecutionRequest,
  JobExecutionResult,
  JobRegistry,
  JobStatus,
  ParameterKeys
}
import com.example.datacraft.spark.FileFixtures.deleteRecursively
import org.scalatest.funsuite.AnyFunSuite

import java.io.ByteArrayOutputStream
import java.nio.channels.Selector
import java.nio.charset.{Charset, StandardCharsets}
import java.nio.file.{Files, Path}
import java.util.zip.GZIPOutputStream
import scala.jdk.CollectionConverters._

/**
 * End-to-end check that Spark really starts on the running JDK and that [[AbstractSparkDataJob]]
 * produces genuine results through the engine. The other Spark specs only assert pure
 * configuration, so this is the suite that actually exercises the Spark runtime.
 */
class SparkPipelineSpec extends AnyFunSuite {

  private val sparkParameters = Map(
    ParameterKeys.SPARK_MASTER             -> "local[1]",
    ParameterKeys.SPARK_SHUFFLE_PARTITIONS -> "1"
  )

  private val GlobRejection = "input must be a literal file or directory path"

  private val NoDataFiles =
    "requirement failed: input contains no data files (Spark skips names starting with _ or .)"

  /** Two records whose second field holds a byte (0xE9, 0xEF) that is no UTF-8 sequence. */
  private val Latin1Csv =
    "id,name\n1,caf\u00e9\n2,na\u00efve\n".getBytes(StandardCharsets.ISO_8859_1)

  /** One record whose second field is two GBK characters; the bytes are not valid UTF-8 either. */
  private val GbkCsv = "id,name\n1,\u4e2d\u6587\n".getBytes("GBK")

  /** The three bytes some programs put in front of UTF-8 text. */
  private val Utf8Mark = Array(0xef, 0xbb, 0xbf).map(_.toByte)

  /** The start of the message for `lines` undecodable lines; the name of the first file follows. */
  private def notValid(charset: String, lines: Int): String =
    s"requirement failed: input is not valid $charset: $lines line(s) cannot be decoded, " +
      "the first in "

  private val EncodingHint = "; set encoding to the charset the data was written in"

  private def gzip(bytes: Array[Byte]): Array[Byte] = {
    val buffer = new ByteArrayOutputStream()
    val stream = new GZIPOutputStream(buffer)
    try stream.write(bytes)
    finally stream.close()
    buffer.toByteArray
  }

  /**
   * Spark's Netty transport cannot start without working NIO selectors. On Windows the selector
   * wakeup pipe is an AF_UNIX socket, which some profile TEMP trees cannot host; the build points
   * `jdk.net.unixdomain.tmpdir` at the module target directory to avoid that. Any host that still
   * cannot open a selector cancels instead of reporting a project defect. CI fails on a cancel.
   */
  private def requireNioSelectors(): Unit =
    try {
      val selector = Selector.open()
      selector.close()
    } catch {
      case failure: Throwable =>
        cancel(
          "Spark needs NIO selectors, which this host's JVM cannot open " +
            s"(${failure.getClass.getSimpleName}: ${failure.getMessage}). " +
            "Environment limitation, not a project defect - this suite runs on Linux and in CI."
        )
    }

  /**
   * Hadoop's local file system needs `winutils.exe` (HADOOP_HOME or hadoop.home.dir) to write files
   * on Windows. Writing tests cancel on a Windows host without it; other hosts always run them.
   */
  private def requireLocalHadoopWrites(): Unit =
    if (
      System.getProperty("os.name", "").startsWith("Windows") &&
      sys.env.get("HADOOP_HOME").forall(_.isBlank) &&
      sys.props.get("hadoop.home.dir").forall(_.isBlank)
    )
      cancel(
        "Hadoop needs winutils (HADOOP_HOME or hadoop.home.dir) to write local files on Windows. " +
          "Environment limitation, not a project defect - this test runs on Linux and in CI."
      )

  private def execute(
      engine: JobExecutionEngine,
      jobName: String,
      parameters: Map[String, String]
  ): JobExecutionResult =
    engine.execute(
      JobExecutionRequest.of(jobName, Lifecycle.DEV, (sparkParameters ++ parameters).asJava)
    )

  /**
   * The Spark jobs with an explicit data root, so an ambient `DATACRAFT_DATA_ROOT` cannot confine
   * the temporary paths these tests use.
   */
  private def newEngine(dataRoot: Option[String] = None): JobExecutionEngine = {
    val registry = new JobRegistry()
    List(new SparkVersionJob, new CsvToParquetJob(dataRoot), new RowCountJob)
      .foreach(registry.register)
    new JobExecutionEngine(registry)
  }

  test("CSV options preserve identifiers decimals and multiline records", PosixOnly) {
    requireNioSelectors()
    requireLocalHadoopWrites()
    val workDir = Files.createTempDirectory("datacraft-spark-precision")
    try {
      val input  = workDir.resolve("input.csv")
      val output = workDir.resolve("result.parquet")
      Files.writeString(
        input,
        "id;amount;note\n001;9007199254740993.1234;\"first\nsecond\"\n002;0.0001;ok\n"
      )
      val engine    = newEngine()
      val converted = execute(
        engine,
        "csv-to-parquet",
        Map(
          "input"     -> input.toString,
          "output"    -> output.toString,
          "delimiter" -> ";",
          "schema"    -> "id STRING, amount DECIMAL(22,4), note STRING"
        )
      )
      assert(converted.status() == JobStatus.SUCCEEDED, converted.message())
      assert(converted.metrics().get("rows") == "2")
      SparkSessions.withSession(
        SparkRuntimeConfig.local("inspect-precision").copy(master = "local[1]")
      ) { spark =>
        val rows = spark.read.parquet(output.toString).orderBy("id").collect()
        assert(rows(0).getString(0) == "001")
        assert(rows(0).getDecimal(1).toPlainString == "9007199254740993.1234")
        assert(rows(0).getString(2) == "first\nsecond")
      }
      val counted = execute(
        engine,
        "row-count",
        Map(
          "input"        -> input.toString,
          "inputFormat"  -> "csv",
          "delimiter"    -> ";",
          "expectedRows" -> "2"
        )
      )
      assert(counted.status() == JobStatus.SUCCEEDED, counted.message())
      val mismatch =
        execute(engine, "row-count", Map("input" -> output.toString, "expectedRows" -> "3"))
      assert(mismatch.status() == JobStatus.FAILED)
      assert(mismatch.message().contains("Expected 3 rows but found 2"))
    } finally deleteRecursively(workDir)
  }

  test("malformed CSV fails before overwriting previously valid output") {
    requireNioSelectors()
    val workDir = Files.createTempDirectory("datacraft-spark-invalid")
    try {
      val input  = workDir.resolve("bad.csv")
      val output = workDir.resolve("existing")
      Files.createDirectory(output)
      Files.writeString(output.resolve("sentinel"), "keep")
      Files.writeString(input, "id,amount\n1,not-a-number\n")
      val engine = newEngine()
      val result = execute(
        engine,
        "csv-to-parquet",
        Map(
          "input"  -> input.toString,
          "output" -> output.toString,
          "schema" -> "id INT, amount DECIMAL(10,2)"
        )
      )
      assert(result.status() == JobStatus.FAILED)
      assert(Files.readString(output.resolve("sentinel")) == "keep")
    } finally deleteRecursively(workDir)
  }

  /**
   * Converts and counts `input` against an existing output: both must fail on the missing data
   * files, and the output must stay as it was.
   */
  private def assertNoDataFiles(workDir: Path, input: Path): Unit = {
    val output = workDir.resolve("existing")
    Files.createDirectory(output)
    Files.writeString(output.resolve("sentinel"), "keep")
    val schema    = "schema" -> "id INT, amount DECIMAL(10,2)"
    val engine    = newEngine()
    val converted = execute(
      engine,
      "csv-to-parquet",
      Map("input" -> input.toString, "output" -> output.toString, schema)
    )
    assert(converted.status() == JobStatus.FAILED, converted.message())
    assert(converted.message() == NoDataFiles)
    assert(Files.readString(output.resolve("sentinel")) == "keep")
    val counted = execute(
      engine,
      "row-count",
      Map("input" -> input.toString, "inputFormat" -> "csv", schema)
    )
    assert(counted.status() == JobStatus.FAILED, counted.metrics())
    assert(counted.message() == NoDataFiles)
  }

  test("a file Spark treats as hidden fails instead of replacing the output with zero rows") {
    requireNioSelectors()
    val workDir = Files.createTempDirectory("datacraft-spark-hidden")
    try
      // Spark's file listing skips names starting with '_' or '.', even when the input names one.
      assertNoDataFiles(
        workDir,
        Files.writeString(workDir.resolve("_export.csv"), "id,amount\n1,2.50\n")
      )
    finally deleteRecursively(workDir)
  }

  // Listing a directory needs Hadoop's native I/O on Windows, like the writing tests.
  test("a directory without data files fails instead of replacing the output", PosixOnly) {
    requireNioSelectors()
    for (hiddenOnly <- Seq(false, true)) {
      val workDir = Files.createTempDirectory("datacraft-spark-no-data")
      try {
        val input = Files.createDirectory(workDir.resolve("input"))
        if (hiddenOnly) {
          Files.writeString(input.resolve("_SUCCESS"), "")
          Files.writeString(input.resolve(".part-0.csv.crc"), "id,amount\n1,2.50\n")
        }
        assertNoDataFiles(workDir, input)
      } finally deleteRecursively(workDir)
    }
  }

  test("error and errorifexists refuse an existing output before reading the input") {
    requireNioSelectors()
    val workDir = Files.createTempDirectory("datacraft-spark-exists")
    try {
      val output = workDir.resolve("existing")
      Files.createDirectory(output)
      Files.writeString(output.resolve("sentinel"), "keep")
      val engine = newEngine()
      for (mode <- Seq("error", " ErrorIfExists ")) {
        val result = execute(
          engine,
          "csv-to-parquet",
          // The input does not exist: the refusal must come first.
          Map(
            "input"  -> workDir.resolve("missing.csv").toString,
            "output" -> output.toString,
            "mode"   -> mode
          )
        )
        assert(result.status() == JobStatus.FAILED, mode)
        assert(result.message() == s"requirement failed: Output already exists: $output", mode)
        assert(Files.readString(output.resolve("sentinel")) == "keep")
      }
    } finally deleteRecursively(workDir)
  }

  test("conversion rejects overlapping paths without destroying the source") {
    requireNioSelectors()
    val workDir = Files.createTempDirectory("datacraft-spark-overlap")
    try {
      val input = workDir.resolve("input.csv")
      Files.writeString(input, "id\n1\n")
      val engine = newEngine()
      for (output <- Seq(input.toString, workDir.toString)) {
        val result =
          execute(engine, "csv-to-parquet", Map("input" -> input.toString, "output" -> output))
        assert(result.status() == JobStatus.FAILED)
        assert(
          result.message().contains("Input and output paths must not overlap"),
          result.message()
        )
        assert(Files.readString(input) == "id\n1\n")
      }
    } finally deleteRecursively(workDir)
  }

  test("conversion rejects output nested inside an input directory") {
    requireNioSelectors()
    val workDir = Files.createTempDirectory("datacraft-spark-descendant")
    try {
      val inputDir = Files.createDirectory(workDir.resolve("indir"))
      Files.writeString(inputDir.resolve("part.csv"), "id\n1\n")
      val engine = newEngine()
      val result = execute(
        engine,
        "csv-to-parquet",
        Map("input" -> inputDir.toString, "output" -> inputDir.resolve("sub").toString)
      )
      assert(result.status() == JobStatus.FAILED)
      assert(result.message().contains("Input and output paths must not overlap"), result.message())
      assert(Files.readString(inputDir.resolve("part.csv")) == "id\n1\n")
      assert(!Files.exists(inputDir.resolve("sub")))
    } finally deleteRecursively(workDir)
  }

  test("conversion rejects a symbolic link alias of the input directory", PosixOnly) {
    requireNioSelectors()
    val workDir = Files.createTempDirectory("datacraft-spark-alias")
    try {
      val input = workDir.resolve("input.csv")
      Files.writeString(input, "id\n1\n")
      val alias = workDir.resolve("alias")
      FileFixtures.createSymbolicLinkOrCancelOnWindows(alias, workDir)
      val engine = newEngine()
      val result =
        execute(
          engine,
          "csv-to-parquet",
          Map("input" -> input.toString, "output" -> alias.toString)
        )
      assert(result.status() == JobStatus.FAILED)
      assert(result.message().contains("Input and output paths must not overlap"), result.message())
      assert(Files.readString(input) == "id\n1\n")
    } finally deleteRecursively(workDir)
  }

  test("conversion rejects glob inputs that could match its own output") {
    requireNioSelectors()
    val workDir = Files.createTempDirectory("datacraft-spark-pattern-output")
    try {
      Files.writeString(workDir.resolve("input.csv"), "id\n1\n")
      val output = Files.createDirectory(workDir.resolve("out.parquet"))
      Files.writeString(output.resolve("sentinel"), "keep")
      val engine = newEngine()
      val result = execute(
        engine,
        "csv-to-parquet",
        Map("input" -> s"$workDir/*", "output" -> output.toString)
      )
      assert(result.status() == JobStatus.FAILED)
      assert(result.message().contains(GlobRejection), result.message())
      assert(Files.readString(output.resolve("sentinel")) == "keep")
    } finally deleteRecursively(workDir)
  }

  test("a glob input selecting files inside the output directory cannot delete the source") {
    requireNioSelectors()
    val workDir = Files.createTempDirectory("datacraft-spark-pattern-source")
    try {
      val batch = Files.createDirectory(workDir.resolve("batch1"))
      Files.writeString(batch.resolve("raw.csv"), "id\n1\n")
      val engine = newEngine()
      val result = execute(
        engine,
        "csv-to-parquet",
        Map("input" -> s"$workDir/*/raw.csv", "output" -> batch.toString)
      )
      assert(result.status() == JobStatus.FAILED)
      assert(result.message().contains(GlobRejection), result.message())
      assert(Files.readString(batch.resolve("raw.csv")) == "id\n1\n")
    } finally deleteRecursively(workDir)
  }

  test("row-count validates complete CSV records instead of pruned columns") {
    requireNioSelectors()
    val workDir = Files.createTempDirectory("datacraft-spark-count-csv")
    try {
      val engine = newEngine()
      for (
        (content, schema) <- Seq(
          "id,amount\n1,x\n" -> "id INT, amount DECIMAL(10,2)",
          "id\n1\nabc\n"     -> "id INT"
        )
      ) {
        val input  = Files.writeString(Files.createTempFile(workDir, "bad", ".csv"), content)
        val result = execute(
          engine,
          "row-count",
          Map("input" -> input.toString, "inputFormat" -> "csv", "schema" -> schema)
        )
        assert(result.status() == JobStatus.FAILED, s"$content counted as ${result.metrics()}")
        // Spark names the file it could not read; the malformed record is the cause.
        assert(result.message().contains("FAILED_READ_FILE"), result.message())
      }
      // The same reader accepts a well-formed file, so the failures above are about the records.
      val valid   = Files.writeString(workDir.resolve("valid.csv"), "id,amount\n1,2.50\n2,3\n")
      val counted = execute(
        engine,
        "row-count",
        Map(
          "input"        -> valid.toString,
          "inputFormat"  -> " CSV ",
          "schema"       -> "id INT, amount DECIMAL(10,2)",
          "expectedRows" -> "2"
        )
      )
      assert(counted.status() == JobStatus.SUCCEEDED, counted.message())
      assert(counted.metrics().get("rows") == "2")
      // The metric reports the format as given, trimmed but not lower-cased.
      assert(counted.metrics().get("inputFormat") == "CSV")
    } finally deleteRecursively(workDir)
  }

  test("row-count fails on CSV bytes that are not valid in the encoding and names the file") {
    requireNioSelectors()
    val workDir = Files.createTempDirectory("datacraft-spark-encoding")
    try {
      val engine                                                           = newEngine()
      def count(input: Path, extra: (String, String)*): JobExecutionResult =
        execute(
          engine,
          "row-count",
          Map("input" -> input.toString, "inputFormat" -> "csv") ++ extra
        )

      val latin1 = Files.write(workDir.resolve("latin1.csv"), Latin1Csv)
      for (asUtf8 <- Seq(count(latin1), count(latin1, "encoding" -> "UTF-8"))) {
        assert(asUtf8.status() == JobStatus.FAILED, asUtf8.metrics())
        assert(asUtf8.message().startsWith(notValid("UTF-8", 2)), asUtf8.message())
        assert(asUtf8.message().endsWith("/latin1.csv" + EncodingHint), asUtf8.message())
      }
      val asLatin1 = count(latin1, "encoding" -> " iso-8859-1 ", "expectedRows" -> "2")
      assert(asLatin1.status() == JobStatus.SUCCEEDED, asLatin1.message())

      val gbk = Files.write(workDir.resolve("gbk.csv"), GbkCsv)
      assert(count(gbk).message().startsWith(notValid("UTF-8", 1)), count(gbk).message())
      // Both line readers decode: the multi-line parser and the per-line one.
      for (multiLine <- Seq("true", "false")) {
        val asGbk = count(gbk, "encoding" -> "GBK", "multiLine" -> multiLine, "expectedRows" -> "1")
        assert(asGbk.status() == JobStatus.SUCCEEDED, asGbk.message())
      }
      // The first record is not GBK either: its line ends in half a character (0xE9).
      assert(count(latin1, "encoding" -> "GBK").message().startsWith(notValid("GBK", 1)))

      // A compressed file is checked on its text, as the CSV reader reads it.
      val packed     = Files.write(workDir.resolve("packed.csv.gz"), gzip(Latin1Csv))
      val packedUtf8 = Files.write(
        workDir.resolve("utf8.csv.gz"),
        gzip("id,name\n1,caf\u00e9\n".getBytes(StandardCharsets.UTF_8))
      )
      assert(count(packed).message().startsWith(notValid("UTF-8", 2)), count(packed).message())
      assert(count(packed).message().endsWith("/packed.csv.gz" + EncodingHint))
      val counted = count(packedUtf8, "expectedRows" -> "1")
      assert(counted.status() == JobStatus.SUCCEEDED, counted.message())
    } finally deleteRecursively(workDir)
  }

  test(
    "a partition directory is checked on the bytes of its files whatever it is called",
    PosixOnly
  ) {
    requireNioSelectors()
    val workDir = Files.createTempDirectory("datacraft-spark-encoding-partition")
    try {
      val engine                                                           = newEngine()
      def count(input: Path, extra: (String, String)*): JobExecutionResult =
        execute(
          engine,
          "row-count",
          Map("input" -> input.toString, "inputFormat" -> "csv") ++ extra
        )

      // Spark's text source calls its line column "value"; a partition of that name replaced it,
      // so the partition's value was checked and the file was not. Nor may a partition take the
      // place of the name the check gives the column instead.
      for (name <- Seq("value", "line0", "VALUE")) {
        val damaged = Files.createDirectories(workDir.resolve(s"damaged-$name/$name=abc"))
        Files.write(damaged.resolve("latin1.csv"), Latin1Csv)
        val refused = count(damaged.getParent)
        assert(refused.status() == JobStatus.FAILED, s"$name: ${refused.metrics()}")
        assert(refused.message().startsWith(notValid("UTF-8", 2)), refused.message())
        assert(
          refused.message().endsWith(s"/$name=abc/latin1.csv" + EncodingHint),
          refused.message()
        )
      }
      // A numeric partition of that name made the check itself fail on a file that is valid.
      val numbered = Files.createDirectories(workDir.resolve("numbered/value=200"))
      Files.writeString(numbered.resolve("ok.csv"), "id,name\n1,café\n")
      val counted = count(numbered.getParent, "expectedRows" -> "1")
      assert(counted.status() == JobStatus.SUCCEEDED, counted.message())
    } finally deleteRecursively(workDir)
  }

  test("a UTF-8 byte order mark fails every other encoding and names the file") {
    requireNioSelectors()
    val workDir = Files.createTempDirectory("datacraft-spark-encoding-mark")
    try {
      val engine                                                           = newEngine()
      def count(input: Path, extra: (String, String)*): JobExecutionResult =
        execute(
          engine,
          "row-count",
          Map("input" -> input.toString, "inputFormat" -> "csv") ++ extra
        )
      // What a spreadsheet's "CSV UTF-8" export writes: the mark, then here plain ASCII.
      val text   = Utf8Mark ++ "id,name\n1,ok\n".getBytes(StandardCharsets.US_ASCII)
      val marked = Files.write(workDir.resolve("marked.csv"), text)
      val packed = Files.write(workDir.resolve("marked.csv.gz"), gzip(text))

      // Hadoop's line reader drops the mark, so the line check never saw it, while the CSV reader
      // decoded its three bytes in the given charset and put them into the first column's name.
      for (
        encoding <- Seq("US-ASCII", "ISO-8859-1", "GBK", "Shift_JIS"); file <- Seq(marked, packed)
      ) {
        val refused = count(file, "encoding" -> encoding)
        assert(refused.status() == JobStatus.FAILED, s"$encoding: ${refused.metrics()}")
        assert(
          refused
            .message()
            .startsWith(
              s"requirement failed: input is not valid $encoding: 1 file(s) start with the UTF-8 " +
                "byte order mark, the first is "
            ),
          refused.message()
        )
        assert(
          refused.message().endsWith(s"/${file.getFileName}; set encoding to UTF-8"),
          refused.message()
        )
      }
      // Read as UTF-8 the mark is no part of the data.
      for (file <- Seq(marked, packed)) {
        val counted = count(file, "expectedRows" -> "1")
        assert(counted.status() == JobStatus.SUCCEEDED, counted.message())
      }
      SparkSessions.withSession(
        SparkRuntimeConfig.local("inspect-mark").copy(master = "local[1]")
      ) { spark =>
        val options = CsvReadOptions(Map.empty, inferSchema = false)
        assert(
          DataFrames.read(spark, "csv", marked.toString, options).columns.toSeq == Seq("id", "name")
        )
      }
    } finally deleteRecursively(workDir)
  }

  test("a line longer than the decoding buffer is checked to its last byte") {
    requireNioSelectors()
    val workDir = Files.createTempDirectory("datacraft-spark-encoding-long")
    try {
      val session = SparkSessions.create(
        SparkRuntimeConfig.local("long-lines").copy(master = "local[1]")
      )
      try {
        // 60,000 two-byte characters: many times the buffer the check decodes into.
        val field                                         = "中文" * 30000
        def check(name: String, bytes: Array[Byte]): Unit =
          DataFrames.requireDecodable(
            session,
            Files.write(workDir.resolve(name), bytes).toString,
            Charset.forName("GBK"),
            "input"
          )
        val valid = s"id,name\n1,$field\n".getBytes("GBK")
        check("valid.csv", valid)

        // Half a character in the middle of the long line (a space where the second byte of a
        // character belongs) and at its end (the last character loses its second byte).
        val start  = "id,name\n1,".length
        val middle = valid.clone()
        middle(start + 2 * 15000 + 1) = ' '.toByte
        val end = valid.dropRight(2) :+ '\n'.toByte
        for ((name, bytes) <- Seq("middle.csv" -> middle, "end.csv" -> end)) {
          val refused = intercept[IllegalArgumentException](check(name, bytes))
          assert(refused.getMessage.startsWith(notValid("GBK", 1)), refused.getMessage)
        }
      } finally session.stop()
    } finally deleteRecursively(workDir)
  }

  test(
    "csv-to-parquet converts a GBK file given its encoding and otherwise keeps the output",
    PosixOnly
  ) {
    requireNioSelectors()
    requireLocalHadoopWrites()
    val workDir = Files.createTempDirectory("datacraft-spark-encoding-convert")
    try {
      val engine    = newEngine()
      val input     = Files.write(workDir.resolve("gbk.csv"), GbkCsv)
      val output    = workDir.resolve("names.parquet")
      val converted = execute(
        engine,
        "csv-to-parquet",
        Map("input" -> input.toString, "output" -> output.toString, "encoding" -> "GBK")
      )
      assert(converted.status() == JobStatus.SUCCEEDED, converted.message())
      assert(converted.metrics().get("rows") == "1")

      // Read as UTF-8, the same file must not replace the good output with damaged text.
      val refused = execute(
        engine,
        "csv-to-parquet",
        Map("input" -> input.toString, "output" -> output.toString)
      )
      assert(refused.status() == JobStatus.FAILED, refused.metrics())
      assert(refused.message().startsWith(notValid("UTF-8", 1)), refused.message())
      assert(refused.message().endsWith("/gbk.csv" + EncodingHint), refused.message())
      SparkSessions.withSession(
        SparkRuntimeConfig.local("inspect-encoding").copy(master = "local[1]")
      ) { spark =>
        val names = spark.read.parquet(output.toString).collect().map(_.getString(1)).toSeq
        assert(names == Seq("\u4e2d\u6587"))
      }

      // In a directory every file is checked; the message counts all lines and names one file.
      val batch = Files.createDirectory(workDir.resolve("batch"))
      Files.writeString(batch.resolve("a-valid.csv"), "id,name\n1,ok\n")
      Files.write(batch.resolve("b-latin1.csv"), Latin1Csv)
      Files.write(batch.resolve("c-latin1.csv"), Latin1Csv)
      val directory = execute(
        engine,
        "csv-to-parquet",
        Map("input" -> batch.toString, "output" -> workDir.resolve("batch.parquet").toString)
      )
      assert(directory.status() == JobStatus.FAILED, directory.metrics())
      assert(directory.message().startsWith(notValid("UTF-8", 4)), directory.message())
      assert(directory.message().endsWith("/b-latin1.csv" + EncodingHint), directory.message())
      assert(!Files.exists(workDir.resolve("batch.parquet")))
    } finally deleteRecursively(workDir)
  }

  test("the CSV reader decodes with the given encoding and stores a quoted CRLF as LF") {
    requireNioSelectors()
    val workDir = Files.createTempDirectory("datacraft-spark-decoding")
    try {
      val latin1  = Files.write(workDir.resolve("latin1.csv"), Latin1Csv)
      val gbk     = Files.write(workDir.resolve("gbk.csv"), GbkCsv)
      val crlf    = Files.writeString(workDir.resolve("crlf.csv"), "id,note\r\n1,\"a\r\nb\"\r\n")
      val session = SparkSessions.create(
        SparkRuntimeConfig.local("csv-decoding").copy(master = "local[1]")
      )
      try {
        def secondColumn(path: Path, parameters: (String, String)*): String = {
          val options = CsvReadOptions(parameters.toMap, inferSchema = false)
          DataFrames.read(session, "csv", path.toString, options).collect().head.getString(1)
        }
        assert(secondColumn(latin1, "encoding" -> "ISO-8859-1") == "caf\u00e9")
        for (multiLine <- Seq("true", "false"))
          assert(
            secondColumn(gbk, "encoding" -> "GBK", "multiLine" -> multiLine) == "\u4e2d\u6587",
            multiLine
          )
        // Spark's own reader never fails on such a byte, which is why the jobs check first.
        assert(secondColumn(latin1) == "caf\uFFFD")
        val undecodable = intercept[IllegalArgumentException](
          DataFrames.requireDecodable(session, latin1.toString, StandardCharsets.UTF_8, "input")
        )
        assert(undecodable.getMessage.startsWith(notValid("UTF-8", 2)), undecodable.getMessage)
        DataFrames.requireDecodable(
          session,
          latin1.toString,
          StandardCharsets.ISO_8859_1,
          "input"
        )
        // A known limit of the conversion: with multiLine=true a quoted CRLF is stored as LF.
        assert(secondColumn(crlf) == "a\nb")
      } finally session.stop()
    } finally deleteRecursively(workDir)
  }

  test("row-count validates complete JSON records") {
    requireNioSelectors()
    val workDir = Files.createTempDirectory("datacraft-spark-count-json")
    try {
      val engine    = newEngine()
      val truncated = Files.writeString(
        workDir.resolve("truncated.json"),
        "{\"id\":1,\"name\":\"a\"}\n{\"id\":2,\"name\":\"b\"}\n{\"id\":3,\"na\n"
      )
      val truncatedResult = execute(
        engine,
        "row-count",
        Map("input" -> truncated.toString, "inputFormat" -> "json", "expectedRows" -> "3")
      )
      assert(truncatedResult.status() == JobStatus.FAILED, truncatedResult.metrics())
      val mistyped = Files.writeString(
        workDir.resolve("mistyped.json"),
        "{\"id\":1,\"name\":\"a\"}\n{\"id\":\"oops\",\"name\":\"b\"}\n"
      )
      val mistypedResult = execute(
        engine,
        "row-count",
        Map(
          "input"        -> mistyped.toString,
          "inputFormat"  -> " JSON ",
          "schema"       -> "id INT, name STRING",
          "expectedRows" -> "2"
        )
      )
      assert(mistypedResult.status() == JobStatus.FAILED, mistypedResult.metrics())
      val valid = Files.writeString(
        workDir.resolve("valid.json"),
        "{\"id\":1,\"name\":\"a\"}\n{\"id\":2,\"name\":\"b\"}\n"
      )
      val validResult = execute(
        engine,
        "row-count",
        Map("input" -> valid.toString, "inputFormat" -> "json", "expectedRows" -> "2")
      )
      assert(validResult.status() == JobStatus.SUCCEEDED, validResult.message())
      assert(validResult.metrics().get("rows") == "2")
    } finally deleteRecursively(workDir)
  }

  test("spark converts csv to parquet and counts the result through the engine", PosixOnly) {
    requireNioSelectors()
    requireLocalHadoopWrites()
    val workDir = Files.createTempDirectory("datacraft-spark-pipeline")
    try {
      val input  = workDir.resolve("input.csv")
      val output = workDir.resolve("output.parquet")
      Files.writeString(input, "id,name\n1,alice\n2,bob\n3,carol\n", StandardCharsets.UTF_8)

      val engine = newEngine()

      val converted = execute(
        engine,
        "csv-to-parquet",
        Map(ParameterKeys.INPUT -> input.toString, ParameterKeys.OUTPUT -> output.toString)
      )
      assert(converted.status() == JobStatus.SUCCEEDED, converted.message())
      assert(converted.metrics().get("rows") == "3")

      val counted = execute(
        engine,
        "row-count",
        Map(
          ParameterKeys.INPUT        -> output.toString,
          ParameterKeys.INPUT_FORMAT -> "parquet"
        )
      )
      assert(counted.status() == JobStatus.SUCCEEDED, counted.message())
      assert(counted.metrics().get("rows") == "3")
    } finally
      deleteRecursively(workDir)
  }

  test("spark-version job reports the running spark runtime") {
    requireNioSelectors()
    val engine = newEngine()

    val result = execute(engine, "spark-version", Map.empty[String, String])

    assert(result.status() == JobStatus.SUCCEEDED, result.message())
    assert(result.metrics().get("sparkVersion").startsWith("4."))
  }

  test("ignore preserves existing data and append reports only newly written rows", PosixOnly) {
    requireNioSelectors()
    requireLocalHadoopWrites()
    val workDir = Files.createTempDirectory("datacraft-spark-modes")
    try {
      val input  = workDir.resolve("input.csv")
      val output = workDir.resolve("output.parquet")
      Files.writeString(input, "id\n1\n2\n")
      val engine = newEngine()
      val params = Map("input" -> input.toString, "output" -> output.toString)
      val first  = execute(engine, "csv-to-parquet", params)
      assert(first.status() == JobStatus.SUCCEEDED, first.message())
      assert(first.message() == s"Wrote 2 rows to $output")
      val ignored = execute(
        engine,
        "csv-to-parquet",
        params ++ Map("mode" -> "ignore", "input" -> workDir.resolve("missing.csv").toString)
      )
      assert(ignored.status() == JobStatus.SUCCEEDED)
      assert(ignored.metrics().get("rows") == "0")
      assert(ignored.metrics().get("skipped") == "true")
      assert(ignored.message() == s"Skipped: $output already exists (mode=ignore)")
      assert(
        execute(engine, "csv-to-parquet", params + ("mode" -> "errorifexists"))
          .status() == JobStatus.FAILED
      )
      val appended = execute(engine, "csv-to-parquet", params + ("mode" -> "append"))
      assert(appended.status() == JobStatus.SUCCEEDED)
      assert(appended.metrics().get("rows") == "2")
      assert(
        execute(engine, "row-count", Map("input" -> output.toString, "expectedRows" -> "4"))
          .status() == JobStatus.SUCCEEDED
      )
    } finally deleteRecursively(workDir)
  }

  test("a configured data root rejects conversions that leave it before touching data") {
    requireNioSelectors()
    val workDir = Files.createTempDirectory("datacraft-spark-root-reject")
    try {
      val root = Files.createDirectory(workDir.resolve("root"))
      val kept = Files.createDirectories(root.resolve("keep")).resolve("part.parquet")
      Files.writeString(kept, "keep")
      val inside  = Files.writeString(root.resolve("in.csv"), "id\n1\n")
      val outside = Files.createDirectory(workDir.resolve("outside"))
      Files.writeString(outside.resolve("in.csv"), "id\n1\n")
      val engine = newEngine(Some(root.toString))

      val intoRoot = execute(
        engine,
        "csv-to-parquet",
        Map("input" -> outside.resolve("in.csv").toString, "output" -> root.toString)
      )
      assert(intoRoot.status() == JobStatus.FAILED)
      assert(
        intoRoot.message().contains("input must be inside the configured data root"),
        intoRoot.message()
      )
      assert(Files.readString(kept) == "keep")

      val leaving = execute(
        engine,
        "csv-to-parquet",
        Map("input" -> inside.toString, "output" -> outside.resolve("o.parquet").toString)
      )
      assert(leaving.status() == JobStatus.FAILED)
      assert(
        leaving.message().contains("output must be inside the configured data root"),
        leaving.message()
      )
      assert(!Files.exists(outside.resolve("o.parquet")))

      val overRoot =
        execute(
          engine,
          "csv-to-parquet",
          Map("input" -> inside.toString, "output" -> root.toString)
        )
      assert(overRoot.status() == JobStatus.FAILED)
      assert(
        overRoot.message().contains("output must be inside the configured data root"),
        overRoot.message()
      )
      assert(Files.readString(kept) == "keep")
    } finally deleteRecursively(workDir)
  }

  test(
    "a configured data root admits conversions inside it and no root leaves paths unconfined",
    PosixOnly
  ) {
    requireNioSelectors()
    requireLocalHadoopWrites()
    val workDir = Files.createTempDirectory("datacraft-spark-root-accept")
    try {
      val root    = Files.createDirectory(workDir.resolve("root"))
      val inside  = Files.writeString(root.resolve("in.csv"), "id\n1\n")
      val outside = Files.createDirectory(workDir.resolve("outside"))
      val within  = execute(
        newEngine(Some(root.toString)),
        "csv-to-parquet",
        Map("input" -> inside.toString, "output" -> root.resolve("out.parquet").toString)
      )
      assert(within.status() == JobStatus.SUCCEEDED, within.message())
      assert(within.metrics().get("rows") == "1")
      val unconfined = execute(
        newEngine(None),
        "csv-to-parquet",
        Map("input" -> inside.toString, "output" -> outside.resolve("out.parquet").toString)
      )
      assert(unconfined.status() == JobStatus.SUCCEEDED, unconfined.message())
      assert(unconfined.metrics().get("rows") == "1")
    } finally deleteRecursively(workDir)
  }

  test("managed jobs do not stop an externally owned Spark session") {
    requireNioSelectors()
    val session =
      SparkSessions.create(SparkRuntimeConfig.local("external-owner").copy(master = "local[1]"))
    try {
      val refused = execute(newEngine(), "spark-version", Map.empty[String, String])
      assert(refused.status() == JobStatus.FAILED)
      assert(
        refused.message() ==
          "requirement failed: A managed Spark job requires exclusive session ownership"
      )
      assert(session.range(3).count() == 3L)
    } finally session.stop()
  }
}
