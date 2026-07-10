package com.example.datacraft.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.datacraft.common.DataCraftException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DataCraftConfigTest {

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
}
