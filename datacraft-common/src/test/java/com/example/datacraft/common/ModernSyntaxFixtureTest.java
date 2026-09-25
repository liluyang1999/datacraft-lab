package com.example.datacraft.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Keeps Java 21 and Java 25 syntax in the build so every source tool (javac, Spotless, Checkstyle)
 * must parse it. If the Checkstyle engine falls behind the project's language level, {@code verify}
 * fails on this file instead of on the first production class that uses the syntax.
 */
class ModernSyntaxFixtureTest {

  record Point(int x, int y) {}

  static int sum(Object value) {
    if (value instanceof Point(int x, int y)) {
      return x + y;
    }
    return 0;
  }

  static String classify(Object value) {
    return switch (value) {
      case Integer i when i > 0 -> "positive";
      case Integer i -> "non-positive";
      default -> "other";
    };
  }

  static class Base {
    final int value;

    Base(int value) {
      this.value = value;
    }
  }

  /** Validates its argument before {@code super(...)}: a JEP 513 flexible constructor body. */
  static final class NonNegative extends Base {
    NonNegative(int value) {
      if (value < 0) {
        throw new IllegalArgumentException("value must not be negative: " + value);
      }
      super(value);
    }
  }

  @Test
  void recordPatternDeconstructsComponents() {
    assertEquals(5, sum(new Point(2, 3)));
    assertEquals(0, sum("not a point"));
  }

  @Test
  void guardedSwitchLabelSelectsByCondition() {
    assertEquals("positive", classify(1));
    assertEquals("non-positive", classify(0));
    assertEquals("other", classify("1"));
  }

  @Test
  void flexibleConstructorBodyValidatesBeforeSuper() {
    assertEquals(4, new NonNegative(4).value);
    assertThrows(IllegalArgumentException.class, () -> new NonNegative(-1));
  }
}
