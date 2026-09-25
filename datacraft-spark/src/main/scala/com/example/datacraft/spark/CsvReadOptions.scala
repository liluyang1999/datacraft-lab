package com.example.datacraft.spark

import com.example.datacraft.engine.ParameterKeys

/**
 * Shared CSV semantics for conversion and counting, plus the named parameter parsers the Spark jobs
 * use. Invalid values never silently become false or a default; errors name the key but never echo
 * the raw value.
 */
private[spark] object CsvReadOptions {
  def boolean(parameters: Map[String, String], key: String, default: Boolean): Boolean =
    parameters.get(key).map(_.trim.toLowerCase(java.util.Locale.ROOT)) match {
      case None          => default
      case Some("true")  => true
      case Some("false") => false
      case _             => throw new IllegalArgumentException(s"$key must be true or false")
    }

  /** An optional 32-bit integer of at least `min`; a present but blank or malformed value fails. */
  def int(parameters: collection.Map[String, String], key: String, min: Int): Option[Int] =
    parameters
      .get(key)
      .map(value =>
        value.trim.toIntOption
          .filter(_ >= min)
          .getOrElse(throw new IllegalArgumentException(s"$key must be a 32-bit integer >= $min"))
      )

  /** An optional 64-bit integer of at least `min`; a present but blank or malformed value fails. */
  def long(parameters: collection.Map[String, String], key: String, min: Long): Option[Long] =
    parameters
      .get(key)
      .map(value =>
        value.trim.toLongOption
          .filter(_ >= min)
          .getOrElse(throw new IllegalArgumentException(s"$key must be a 64-bit integer >= $min"))
      )

  def schema(parameters: Map[String, String]): Option[String] =
    parameters.get(ParameterKeys.SCHEMA).map(_.trim).filter(_.nonEmpty)

  def apply(parameters: Map[String, String], inferSchema: Boolean): Map[String, String] = {
    val delimiter = parameters.getOrElse(ParameterKeys.DELIMITER, ",")
    require(
      delimiter.nonEmpty && !delimiter.exists(c =>
        c == '\n' || c == '\r' || c == '\u0000' || c == '"'
      ),
      "delimiter must be nonempty and must not contain quotes, newlines or NUL"
    )
    val escape = parameters.getOrElse(ParameterKeys.CSV_ESCAPE, "\"")
    require(escape.length == 1, "escape must contain exactly one character")
    Map(
      "header"        -> boolean(parameters, ParameterKeys.HEADER, default = true).toString,
      "delimiter"     -> delimiter,
      "inferSchema"   -> boolean(parameters, ParameterKeys.INFER_SCHEMA, inferSchema).toString,
      "multiLine"     -> boolean(parameters, ParameterKeys.MULTI_LINE, default = true).toString,
      "escape"        -> escape,
      "mode"          -> "FAILFAST",
      "enforceSchema" -> "false",
      "unescapedQuoteHandling" -> "RAISE_ERROR"
    )
  }
}
