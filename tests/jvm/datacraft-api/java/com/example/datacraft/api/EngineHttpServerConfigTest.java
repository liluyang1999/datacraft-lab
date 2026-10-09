package com.example.datacraft.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class EngineHttpServerConfigTest {

  private static final String TOKEN = "0123456789abcdef0123456789abcdef";
  private static final String RULE =
      "API token must be 32 to 512 characters from A-Z a-z 0-9 - . _ ~ + / with optional trailing"
          + " =.";

  @Test
  void theDefaultsAreAnOpenApiOnLoopbackThatRunsFourJobsAtATime() {
    EngineHttpServerConfig local = EngineHttpServerConfig.localEphemeral();

    assertEquals("127.0.0.1", local.host());
    assertEquals(0, local.port());
    assertEquals(4, local.maxConcurrentRuns());
    assertEquals(Optional.empty(), local.apiToken());
    assertEquals(
        new EngineHttpServerConfig("0.0.0.0", 8080, 0, 4, Optional.empty()),
        EngineHttpServerConfig.of("0.0.0.0", 8080));
  }

  @Test
  void aTokenIsKeptButNeverPrinted() {
    EngineHttpServerConfig config = EngineHttpServerConfig.of("0.0.0.0", 8080).withApiToken(TOKEN);

    assertEquals(Optional.of(TOKEN), config.apiToken());
    assertEquals(
        "EngineHttpServerConfig[host=0.0.0.0, port=8080, backlog=0, maxConcurrentRuns=4,"
            + " apiToken=<redacted>]",
        config.toString());
    assertTrue(
        EngineHttpServerConfig.localEphemeral().toString().endsWith("apiToken=<none>]"),
        EngineHttpServerConfig.localEphemeral().toString());
  }

  @Test
  void acceptsTokensOfTheBearerSyntaxFrom32To512Characters() {
    for (String token :
        new String[] {
          "x".repeat(32), "x".repeat(512), "AZaz09-._~+/".repeat(3), "x".repeat(30) + "=="
        }) {
      assertEquals(
          Optional.of(token),
          EngineHttpServerConfig.localEphemeral().withApiToken(token).apiToken(),
          token);
    }
  }

  @Test
  void rejectsOtherTokensWithoutRepeatingThem() {
    String[] rejected = {
      "",
      "x".repeat(31),
      "x".repeat(513),
      "with space " + "x".repeat(32),
      "with\ttab" + "x".repeat(32),
      "quote\"" + "x".repeat(32),
      "comma," + "x".repeat(32),
      "=" + "x".repeat(32),
      "pad=ding" + "x".repeat(32),
      "na\u00efve" + "x".repeat(32),
      "trailing-newline" + "x".repeat(32) + "\n"
    };
    for (String token : rejected) {
      IllegalArgumentException error =
          assertThrows(
              IllegalArgumentException.class,
              () -> EngineHttpServerConfig.localEphemeral().withApiToken(token),
              token);

      assertEquals(RULE, error.getMessage());
    }
    assertEquals(
        RULE,
        assertThrows(
                IllegalArgumentException.class,
                () -> EngineHttpServerConfig.localEphemeral().withApiToken(null))
            .getMessage());
  }

  @Test
  void aNullTokenNeverMeansAnOpenApi() {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> new EngineHttpServerConfig("127.0.0.1", 0, 0, 4, null));

    assertEquals("API token must be an Optional, not null.", error.getMessage());
  }

  @Test
  void theRunLimitMustBeAtLeastOne() {
    assertEquals(
        1, EngineHttpServerConfig.localEphemeral().withMaxConcurrentRuns(1).maxConcurrentRuns());
    for (int limit : new int[] {0, -1}) {
      IllegalArgumentException error =
          assertThrows(
              IllegalArgumentException.class,
              () -> EngineHttpServerConfig.localEphemeral().withMaxConcurrentRuns(limit));

      assertEquals("HTTP maxConcurrentRuns must be at least 1.", error.getMessage());
    }
    assertFalse(
        EngineHttpServerConfig.localEphemeral()
            .withMaxConcurrentRuns(7)
            .withApiToken(TOKEN)
            .toString()
            .contains(TOKEN));
  }
}
