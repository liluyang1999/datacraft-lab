package com.example.datacraft.io;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
