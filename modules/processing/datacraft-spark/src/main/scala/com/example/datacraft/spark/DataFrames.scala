package com.example.datacraft.spark

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.compress.CompressionCodecFactory
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions.{col, input_file_name}
import org.apache.spark.sql.types.{StringType, StructField, StructType}
import org.apache.spark.util.SerializableConfiguration

import java.net.URI
import java.nio.{ByteBuffer, CharBuffer}
import java.nio.charset.{Charset, CharsetDecoder, CodingErrorAction, StandardCharsets}
import java.util.Locale

/** Reusable, format-agnostic Spark read/write helpers shared by all data jobs. */
object DataFrames {

  def read(
      spark: SparkSession,
      format: String,
      path: String,
      options: Map[String, String] = Map.empty,
      schema: Option[String] = None
  ): DataFrame = {
    // Since 4.0 Spark's readers accept only a short list of charsets unless this conf is set. The
    // session belongs to one job run, so no other query sees the change.
    if (options.get("encoding").exists(!SparkCharsets.contains(_)))
      spark.conf.set(JdkCharsetsConf, "true")
    val reader = spark.read.format(format).options(options)
    schema.foreach(reader.schema)
    reader.load(path)
  }

  /** Lets Spark's readers decode with any charset of the JDK, such as GBK or Shift_JIS. */
  private val JdkCharsetsConf = "spark.sql.legacy.javaCharsets"

  /**
   * The canonical names of the ASCII-compatible charsets Spark reads without [[JdkCharsetsConf]].
   */
  private val SparkCharsets = Set("UTF-8", "ISO-8859-1", "US-ASCII")

  /**
   * Fails when a file-based read matched no data file. Spark's file listing skips names starting
   * with `_` or `.`, so such a file, a directory holding only such files and an empty directory all
   * read as zero rows once a schema is given, and an overwrite would then replace good output with
   * an empty dataset. A header-only file is a data file: it reads as zero rows and passes.
   */
  def requireDataFiles(dataFrame: DataFrame, parameter: String): DataFrame = {
    require(
      dataFrame.inputFiles.nonEmpty,
      s"$parameter contains no data files (Spark skips names starting with _ or .)"
    )
    dataFrame
  }

  /**
   * Fails when a text input holds bytes that are not valid in `charset`. Spark's CSV reader does
   * not fail on them: it puts U+FFFD in their place and succeeds, so a file written in another
   * encoding would be converted into damaged text. This reads the input once more, as raw lines
   * (decompressed the way the CSV reader would), and decodes every line strictly. A wrong encoding
   * whose bytes happen to be valid in `charset` cannot be told apart and still passes, with one
   * exception: a file that begins with the UTF-8 byte order mark is UTF-8.
   */
  def requireDecodable(
      spark: SparkSession,
      path: String,
      charset: Charset,
      parameter: String
  ): Unit = {
    // A Charset is not serializable; its name is, and resolves again in every task.
    val name = charset.name()
    // Listed once, for the files a read of this path covers and for its partition columns.
    val listed = spark.read.format("text").load(path)
    if (charset != StandardCharsets.UTF_8)
      requireNoUtf8Mark(spark, listed.inputFiles.toSeq, name, parameter)
    // The text source calls its one column "value", and a partition directory of that name
    // (value=...) takes its place, so the check would read the partition's value instead of the
    // lines. The column gets a name that no partition of this input has.
    val taken              = listed.schema.fieldNames.map(_.toLowerCase(Locale.ROOT)).toSet
    val line               = Iterator.from(0).map(number => s"line$number").filterNot(taken).next()
    val (lines, firstFile) = spark.read
      .format("text")
      .schema(StructType(Seq(StructField(line, StringType))))
      .load(path)
      // The text source keeps the bytes of each line as they are; the cast hands them over.
      .select(input_file_name(), col(line).cast("binary"))
      .rdd
      .mapPartitions { rows =>
        val decoder = Charset
          .forName(name)
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
        val scratch = CharBuffer.allocate(DecodingBuffer)
        rows.collect {
          case row if !decodes(decoder, scratch, row.getAs[Array[Byte]](1)) => row.getString(0)
        }
      }
      .aggregate((0L, ""))(
        { case ((count, first), file) => (count + 1, earlier(first, file)) },
        { case ((left, a), (right, b)) => (left + right, earlier(a, b)) }
      )
    require(
      lines == 0,
      s"$parameter is not valid $name: $lines line(s) cannot be decoded, the first in " +
        s"$firstFile; set encoding to the charset the data was written in"
    )
  }

  /** Characters decoded at a time; the text is only checked, so it is never kept. */
  private val DecodingBuffer = 8192

  /**
   * Whether `bytes` are a complete text in the decoder's charset. The characters go into `scratch`
   * and are discarded, so a long line costs no second copy of itself.
   */
  private def decodes(decoder: CharsetDecoder, scratch: CharBuffer, bytes: Array[Byte]): Boolean = {
    val input = ByteBuffer.wrap(bytes)
    decoder.reset()
    // With no more input to come, a character cut short at the end is an error as well.
    var result = decoder.decode(input, scratch.clear(), true)
    while (result.isOverflow) result = decoder.decode(input, scratch.clear(), true)
    if (result.isError) false
    else {
      var flushed = decoder.flush(scratch.clear())
      while (flushed.isOverflow) flushed = decoder.flush(scratch.clear())
      true
    }
  }

  /** The three bytes some programs put in front of UTF-8 text. */
  private val Utf8Mark = Array(0xef, 0xbb, 0xbf).map(_.toByte)

  /**
   * Fails when a file begins with the UTF-8 byte order mark although another charset was named.
   * Hadoop's line reader drops the mark, so the line check never sees it, while the CSV reader
   * decodes its three bytes in the given charset and puts them into the first column's name.
   */
  private def requireNoUtf8Mark(
      spark: SparkSession,
      files: Seq[String],
      name: String,
      parameter: String
  ): Unit = if (files.nonEmpty) {
    val context         = spark.sparkContext
    val configuration   = new SerializableConfiguration(context.hadoopConfiguration)
    val slices          = math.min(files.length, context.defaultParallelism)
    val (marked, first) = context
      .parallelize(files, slices)
      .filter(file => startsWithUtf8Mark(new Path(new URI(file)), configuration.value))
      .aggregate((0L, ""))(
        { case ((count, first), file) => (count + 1, earlier(first, file)) },
        { case ((left, a), (right, b)) => (left + right, earlier(a, b)) }
      )
    require(
      marked == 0,
      s"$parameter is not valid $name: $marked file(s) start with the UTF-8 byte order mark, " +
        s"the first is $first; set encoding to UTF-8"
    )
  }

  /** Reads the first three bytes of the text in `file`, decompressed like the readers do. */
  private def startsWithUtf8Mark(file: Path, configuration: Configuration): Boolean = {
    val codec  = new CompressionCodecFactory(configuration).getCodec(file)
    val raw    = file.getFileSystem(configuration).open(file)
    val stream = if (codec == null) raw else codec.createInputStream(raw)
    try stream.readNBytes(Utf8Mark.length).sameElements(Utf8Mark)
    finally stream.close()
  }

  /** The smaller of two file names, where an empty name stands for none. */
  private def earlier(left: String, right: String): String =
    if (left.isEmpty) right
    else if (right.isEmpty || left <= right) left
    else right

  def write(
      dataFrame: DataFrame,
      format: String,
      path: String,
      mode: String = "overwrite",
      options: Map[String, String] = Map.empty
  ): Unit =
    dataFrame.write.format(format).mode(mode).options(options).save(path)
}
