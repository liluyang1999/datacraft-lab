package com.example.datacraft.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LifecycleTest {

  @Test
  void parsesLifecycleNamesIgnoringCaseAndSeparators() {
    assertEquals(Lifecycle.DEV, Lifecycle.fromName("dev"));
    assertEquals(Lifecycle.DEV, Lifecycle.fromName("development"));
    assertEquals(Lifecycle.PROD, Lifecycle.fromName("PROD"));
    assertEquals(Lifecycle.PROD, Lifecycle.fromName("production"));
    assertEquals(Lifecycle.PROD, Lifecycle.fromName(" Pro-Duction "));
    assertEquals(Lifecycle.DEV, Lifecycle.fromName("DE_V"));
  }

  @Test
  void exposesStableFullNames() {
    assertEquals("development", Lifecycle.DEV.fullName());
    assertEquals("production", Lifecycle.PROD.fullName());
  }

  @Test
  void rejectsBlankLifecycleNames() {
    assertThrows(IllegalArgumentException.class, () -> Lifecycle.fromName(" "));
  }

  @Test
  void rejectsUnsupportedAndNullLifecycleNames() {
    IllegalArgumentException unsupported =
        assertThrows(IllegalArgumentException.class, () -> Lifecycle.fromName("staging"));

    assertTrue(unsupported.getMessage().contains("staging"), unsupported.getMessage());
    assertThrows(IllegalArgumentException.class, () -> Lifecycle.fromName(null));
  }
}
