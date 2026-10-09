package com.example.datacraft.spark

import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions.{col, input_file_name}

import java.nio.ByteBuffer
import java.nio.charset.{CharacterCodingException, Charset, CharsetDecoder, CodingErrorAction}

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
   * whose bytes happen to be valid in `charset` cannot be told apart and still passes.
   */
  def requireDecodable(
      spark: SparkSession,
      path: String,
      charset: Charset,
      parameter: String
  ): Unit = {
    // A Charset is not serializable; its name is, and resolves again in every task.
    val name               = charset.name()
    val (lines, firstFile) = spark.read
      .format("text")
      .load(path)
      // The text source keeps the bytes of each line as they are; the cast hands them over.
      .select(input_file_name(), col("value").cast("binary"))
      .rdd
      .mapPartitions { rows =>
        val decoder = Charset
          .forName(name)
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
        rows.collect {
          case row if !decodes(decoder, row.getAs[Array[Byte]](1)) => row.getString(0)
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

  private def decodes(decoder: CharsetDecoder, bytes: Array[Byte]): Boolean =
    try {
      decoder.decode(ByteBuffer.wrap(bytes))
      true
    } catch { case _: CharacterCodingException => false }

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
