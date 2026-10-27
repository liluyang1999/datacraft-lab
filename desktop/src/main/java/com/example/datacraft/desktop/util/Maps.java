package com.example.datacraft.desktop.util;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable map copies that keep the order the data came in. */
public final class Maps {

  private Maps() {}

  /**
   * An unmodifiable copy in insertion order. Unlike {@link Map#copyOf}, it keeps the order (metrics
   * read as the engine sent them) and accepts null values (an Airflow conf may hold JSON null).
   */
  public static <K, V> Map<K, V> frozen(Map<K, V> map) {
    return map == null || map.isEmpty()
        ? Map.of()
        : Collections.unmodifiableMap(new LinkedHashMap<>(map));
  }
}
