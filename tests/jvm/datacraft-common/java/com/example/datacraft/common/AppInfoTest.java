package com.example.datacraft.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AppInfoTest {

  private static InputStream properties(String text) {
    return new ByteArrayInputStream(text.getBytes(StandardCharsets.ISO_8859_1));
  }

  @Test
  void theBuildRecordsAThreeNumberReleaseVersion() {
    assertTrue(AppInfo.version().matches("\\d+\\.\\d+\\.\\d+"), AppInfo.version());
  }

  @Test
  void aFilteredResourceYieldsItsVersion() {
    assertEquals("1.2.3", AppInfo.read(properties("# comment\nversion= 1.2.3 \n")));
  }

  @Test
  void aMissingUnfilteredOrSuffixedVersionFailsLoudly() {
    assertThrows(IllegalStateException.class, () -> AppInfo.read(null));
    assertThrows(
        IllegalStateException.class, () -> AppInfo.read(properties("version=${project.version}")));
    assertThrows(
        IllegalStateException.class, () -> AppInfo.read(properties("version=2.4.1-SNAPSHOT")));
    assertThrows(IllegalStateException.class, () -> AppInfo.read(properties("other=2.4.1")));
  }
}
