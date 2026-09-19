package com.example.datacraft.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

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
