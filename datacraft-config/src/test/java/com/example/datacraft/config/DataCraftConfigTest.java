package com.example.datacraft.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.datacraft.common.DataCraftException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DataCraftConfigTest {

  @Test
  void rejectsMistypedBooleans() {
    DataCraftConfig config = DataCraftConfig.fromMap(Map.of("enabled", "treu"));
    assertThrows(DataCraftException.class, () -> config.getBoolean("enabled", false));
  }

  @TempDir Path tempDir;

  @Test
  void loadsUtf8PropertiesAndReadsTypedValues() throws Exception {
    Path file = tempDir.resolve("datacraft.properties");
    Files.writeString(
        file,
        """
        engine.name=datacraft
        engine.workers=4
        engine.enabled=true
        spark.master=local[*]
        """);

    DataCraftConfig config = DataCraftConfig.load(file);

    assertEquals("datacraft", config.require("engine.name"));
    assertEquals(4, config.getInt("engine.workers", 1));
    assertTrue(config.getBoolean("engine.enabled", false));
    assertEquals("local[*]", config.getOrDefault("spark.master", "local[1]"));
  }

  @Test
  void extractsPrefixWithoutMutatingOriginalKeys() {
    DataCraftConfig config =
        DataCraftConfig.fromMap(
            Map.of("spark.master", "local[*]", "spark.shuffle", "8", "engine.name", "core"));

    DataCraftConfig spark = config.withPrefix("spark.");

    assertEquals(Map.of("master", "local[*]", "shuffle", "8"), spark.asMap());
    assertEquals("local[*]", config.require("spark.master"));
  }

  @Test
  void rejectsMissingRequiredKeysAndInvalidNumbers() {
    DataCraftConfig config = DataCraftConfig.fromMap(Map.of("workers", "many"));

    assertThrows(DataCraftException.class, () -> config.require("missing"));
    assertThrows(DataCraftException.class, () -> config.getInt("workers", 1));
  }

  @Test
  void loadsUtf8FileWithByteOrderMark() throws Exception {
    Path file = tempDir.resolve("bom.properties");
    writeWithByteOrderMark(file, "mode=append\nspark.master=local[3]\n");

    DataCraftConfig config = DataCraftConfig.load(file);

    assertEquals("append", config.require("mode"));
    assertEquals(Set.of("mode", "spark.master"), config.asMap().keySet());

    Path commented = tempDir.resolve("bom-comment.properties");
    writeWithByteOrderMark(commented, "# comment\nmode=append\n");

    assertEquals(Set.of("mode"), DataCraftConfig.load(commented).asMap().keySet());
  }

  @Test
  void preservesEscapedWhitespaceValuesAndTrimsTypedReads() throws Exception {
    Path file = tempDir.resolve("whitespace.properties");
    // On disk: delimiter=\t, pad=\ x\ (escaped spaces), and two values with a trailing space.
    Files.writeString(file, "delimiter=\\t\npad=\\ x\\ \nworkers=4 \nenabled=true \n");

    DataCraftConfig config = DataCraftConfig.load(file);

    assertEquals("\t", config.require("delimiter"));
    assertEquals(" x ", config.require("pad"));
    assertEquals("4 ", config.require("workers"));
    assertEquals(4, config.getInt("workers", 1));
    assertTrue(config.getBoolean("enabled", false));
  }

  @Test
  void fromMapTrimsKeysButKeepsValuesVerbatim() {
    DataCraftConfig config = DataCraftConfig.fromMap(Map.of(" message ", "  padded\t"));

    assertEquals(Map.of("message", "  padded\t"), config.asMap());
  }

  @Test
  void loadsBackslashesWithJavaPropertiesEscaping() throws Exception {
    Path file = tempDir.resolve("paths.properties");
    // On disk: output=D:\\out\\x.parquet (doubled backslashes) and forward=D:/out/x.parquet.
    Files.writeString(file, "output=D:\\\\out\\\\x.parquet\nforward=D:/out/x.parquet\n");

    DataCraftConfig config = DataCraftConfig.load(file);

    assertEquals("D:\\out\\x.parquet", config.require("output"));
    assertEquals("D:/out/x.parquet", config.require("forward"));
  }

  @Test
  void malformedUnicodeEscapeFailsWithFileContext() throws Exception {
    Path file = tempDir.resolve("escape.properties");
    // Built from a char so that no backslash-u sequence appears in this source file.
    char backslash = '\\';
    Files.writeString(file, "password=s3cr3t" + backslash + "uZZZZ\n");

    DataCraftException exception =
        assertThrows(DataCraftException.class, () -> DataCraftConfig.load(file));

    assertTrue(exception.getMessage().contains(file.toString()), exception.getMessage());
    assertFalse(exception.getMessage().contains("s3cr3t"), exception.getMessage());
    assertInstanceOf(IllegalArgumentException.class, exception.getCause());
  }

  @Test
  void loadRejectsBlankKeyWithFileName() throws Exception {
    Path file = tempDir.resolve("bad.properties");
    Files.writeString(file, "=orphan\nmode=append\n");

    DataCraftException exception =
        assertThrows(DataCraftException.class, () -> DataCraftConfig.load(file));

    assertTrue(exception.getMessage().contains(file.toString()), exception.getMessage());
    assertFalse(exception.getMessage().contains("orphan"), exception.getMessage());
    assertInstanceOf(IllegalArgumentException.class, exception.getCause());
  }

  @Test
  void loadReportsMissingFile() {
    Path file = tempDir.resolve("missing.properties");

    DataCraftException exception =
        assertThrows(DataCraftException.class, () -> DataCraftConfig.load(file));

    assertTrue(exception.getMessage().contains("missing.properties"), exception.getMessage());
  }

  @Test
  void withPrefixKeepsPasswordWhitespaceVerbatim() throws Exception {
    Path file = tempDir.resolve("sftp.properties");
    // On disk: sftp.password=\ pa ss\ (escaped leading and trailing spaces).
    Files.writeString(file, "sftp.password=\\ pa ss\\ \n");

    DataCraftConfig sftp = DataCraftConfig.load(file).withPrefix("sftp.");

    assertEquals(" pa ss ", sftp.require("password"));
  }

  private static void writeWithByteOrderMark(Path file, String content) throws Exception {
    byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    byte[] body = content.getBytes(StandardCharsets.UTF_8);
    byte[] bytes = new byte[bom.length + body.length];
    System.arraycopy(bom, 0, bytes, 0, bom.length);
    System.arraycopy(body, 0, bytes, bom.length, body.length);
    Files.write(file, bytes);
  }
}
