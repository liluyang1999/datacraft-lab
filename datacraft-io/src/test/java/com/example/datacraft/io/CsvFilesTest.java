package com.example.datacraft.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CsvFilesTest {

  @Test
  void parsesQuotedFieldsWithDelimitersQuotesAndNewlines() {
    String csv = "name,note\n\"Doe, John\",\"line1\nline2\"\n\"quote\"\"inside\"\"\",plain\n";

    List<List<String>> rows = CsvFiles.parse(csv, ',');

    assertEquals(3, rows.size());
    assertEquals(List.of("name", "note"), rows.get(0));
    assertEquals(List.of("Doe, John", "line1\nline2"), rows.get(1));
    assertEquals(List.of("quote\"inside\"", "plain"), rows.get(2));
  }

  @Test
  void roundTripsThroughFormatAndParse() {
    List<List<String>> rows = List.of(List.of("a", "b,c"), List.of("d\"e", "f"));

    String text = CsvFiles.format(rows, ',');

    assertEquals(rows, CsvFiles.parse(text, ','));
  }

  @Test
  void parsesEmptyContentAsNoRows() {
    assertEquals(List.of(), CsvFiles.parse("", ','));
  }

  @Test
  void quotesALoneEmptyFieldSoTheRecordIsNotABlankLine() {
    List<List<String>> rows = List.of(List.of("a"), List.of(""), List.of("b"));

    assertEquals("a\n\"\"\nb\n", CsvFiles.format(rows, ','));
    assertEquals(rows, CsvFiles.parse(CsvFiles.format(rows, ','), ','));
    assertEquals("\"\"\n", CsvFiles.format(List.of(Collections.singletonList(null)), ','));
    assertEquals(",\n", CsvFiles.format(List.of(List.of("", "")), ','));
  }

  @Test
  void preservesALeadingByteOrderMarkInTheFirstField() {
    List<List<String>> rows = List.of(List.of("\ufeffid", "x"));

    assertEquals("\"\ufeffid\",x\n", CsvFiles.format(rows, ','));
    assertEquals(rows, CsvFiles.parse(CsvFiles.format(rows, ','), ','));
  }

  @Test
  void rejectsRowsWithoutFields() {
    assertThrows(
        IllegalArgumentException.class,
        () -> CsvFiles.format(List.of(List.of("a"), List.of()), ','));
  }

  @Test
  void terminatesEveryRecordWithLfAndKeepsEmbeddedLineBreaksQuoted() {
    assertEquals(
        "h1,h2\n1,\"a\r\nb\"\n",
        CsvFiles.format(List.of(List.of("h1", "h2"), List.of("1", "a\r\nb")), ','));
  }

  @Test
  void roundTripsRowsThroughAFile(@TempDir Path directory) {
    Path file = directory.resolve("rows.csv");
    List<List<String>> rows =
        List.of(
            List.of("id", "note"),
            List.of(""),
            List.of("1", "comma, inside"),
            List.of("2", "line\nbreak"),
            List.of("3", "quote \" inside"));

    CsvFiles.write(file, rows);

    assertEquals(rows, CsvFiles.read(file));
  }

  @Test
  void preservesAnEmptyQuotedRecordWithoutATrailingNewline() {
    assertEquals(List.of(List.of("")), CsvFiles.parse("\"\"", ','));
    assertEquals(List.of(List.of("", "")), CsvFiles.parse(",", ','));
    assertEquals(List.of(List.of("a"), List.of("")), CsvFiles.parse("a\r\n\"\"", ','));
  }

  @Test
  void rejectsMalformedQuotesInsteadOfSilentlyChangingData() {
    for (String csv : List.of("\"unterminated", "a\"b", "\"a\"b", "x,\"a\" ")) {
      assertThrows(IllegalArgumentException.class, () -> CsvFiles.parse(csv, ','), csv);
    }
  }

  @Test
  void rejectsDelimitersThatConflictWithCsvSyntax() {
    for (char delimiter : new char[] {'"', '\r', '\n', '\0'}) {
      assertThrows(IllegalArgumentException.class, () -> CsvFiles.parse("x", delimiter));
      assertThrows(
          IllegalArgumentException.class, () -> CsvFiles.format(List.of(List.of("x")), delimiter));
    }
  }

  @Test
  void readsBomAndPreservesWhitespaceAndCrLfInsideQuotedFields() {
    assertEquals(
        List.of(List.of("name", "note"), List.of("  李  ", "a\r\nb")),
        CsvFiles.parse("\ufeffname,note\r\n  李  ,\"a\r\nb\"\r\n", ','));
  }
}
