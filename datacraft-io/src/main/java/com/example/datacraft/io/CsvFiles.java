package com.example.datacraft.io;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal, dependency-free CSV reader/writer following RFC 4180 quoting rules. Suited to
 * small-scale, non-Spark data wrangling; for large datasets use the Spark data jobs instead.
 *
 * <p>Each input line maps to one row. Fields may be quoted with {@code "}; inside a quoted field a
 * literal quote is written as {@code ""} and delimiters/newlines are preserved verbatim.
 */
public final class CsvFiles {

  public static final char DEFAULT_DELIMITER = ',';

  private CsvFiles() {}

  public static List<List<String>> read(Path file) {
    return parse(LocalFiles.readUtf8String(file), DEFAULT_DELIMITER);
  }

  public static List<List<String>> read(Path file, char delimiter) {
    return parse(LocalFiles.readUtf8String(file), delimiter);
  }

  public static void write(Path file, List<List<String>> rows) {
    write(file, rows, DEFAULT_DELIMITER);
  }

  public static void write(Path file, List<List<String>> rows, char delimiter) {
    LocalFiles.writeUtf8String(file, format(rows, delimiter));
  }

  public static List<List<String>> parse(String content, char delimiter) {
    List<List<String>> rows = new ArrayList<>();
    List<String> record = new ArrayList<>();
    StringBuilder field = new StringBuilder();
    boolean inQuotes = false;
    int length = content.length();

    for (int i = 0; i < length; i++) {
      char c = content.charAt(i);
      if (inQuotes) {
        if (c == '"') {
          if (i + 1 < length && content.charAt(i + 1) == '"') {
            field.append('"');
            i++;
          } else {
            inQuotes = false;
          }
        } else {
          field.append(c);
        }
      } else if (c == '"') {
        inQuotes = true;
      } else if (c == delimiter) {
        record.add(field.toString());
        field.setLength(0);
      } else if (c == '\n' || c == '\r') {
        if (c == '\r' && i + 1 < length && content.charAt(i + 1) == '\n') {
          i++;
        }
        record.add(field.toString());
        field.setLength(0);
        rows.add(record);
        record = new ArrayList<>();
      } else {
        field.append(c);
      }
    }

    if (field.length() > 0 || !record.isEmpty()) {
      record.add(field.toString());
      rows.add(record);
    }
    return rows;
  }

  public static String format(List<List<String>> rows, char delimiter) {
    StringBuilder builder = new StringBuilder();
    for (List<String> row : rows) {
      for (int i = 0; i < row.size(); i++) {
        if (i > 0) {
          builder.append(delimiter);
        }
        builder.append(escape(row.get(i), delimiter));
      }
      builder.append('\n');
    }
    return builder.toString();
  }

  private static String escape(String field, char delimiter) {
    String value = field == null ? "" : field;
    boolean mustQuote =
        value.indexOf(delimiter) >= 0
            || value.indexOf('"') >= 0
            || value.indexOf('\n') >= 0
            || value.indexOf('\r') >= 0;
    if (!mustQuote) {
      return value;
    }
    return '"' + value.replace("\"", "\"\"") + '"';
  }
}
