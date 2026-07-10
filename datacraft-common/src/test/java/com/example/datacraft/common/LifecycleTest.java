package com.example.datacraft.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class LifecycleTest {

  @Test
  void parsesLifecycleNamesIgnoringCaseAndSeparators() {
    assertEquals(Lifecycle.DEV, Lifecycle.fromName("dev"));
    assertEquals(Lifecycle.DEV, Lifecycle.fromName("development"));
    assertEquals(Lifecycle.PROD, Lifecycle.fromName("PROD"));
    assertEquals(Lifecycle.PROD, Lifecycle.fromName("production"));
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
}
