package com.example.datacraft.desktop.util;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import javafx.collections.ObservableList;

/** In-place list updates, so that a refresh repaints only the rows that changed. */
public final class Lists {

  private Lists() {}

  /**
   * Makes {@code target} equal to {@code fresh}: when the keys are the same and in the same order,
   * only the elements that changed are replaced; otherwise rows are removed, moved and inserted
   * with as few changes as a simple walk allows. Returns whether anything changed.
   */
  public static <T, K> boolean reconcile(
      ObservableList<T> target, List<T> fresh, Function<T, K> key) {
    if (sameKeys(target, fresh, key)) {
      boolean changed = false;
      for (int index = 0; index < fresh.size(); index++) {
        if (!Objects.equals(target.get(index), fresh.get(index))) {
          target.set(index, fresh.get(index));
          changed = true;
        }
      }
      return changed;
    }
    Map<K, Integer> wanted = new HashMap<>();
    for (int index = 0; index < fresh.size(); index++) {
      wanted.put(key.apply(fresh.get(index)), index);
    }
    // Drop rows that are gone, from the end so indices stay valid.
    for (int index = target.size() - 1; index >= 0; index--) {
      if (!wanted.containsKey(key.apply(target.get(index)))) {
        target.remove(index);
      }
    }
    for (int index = 0; index < fresh.size(); index++) {
      T element = fresh.get(index);
      K wantedKey = key.apply(element);
      if (index < target.size() && Objects.equals(key.apply(target.get(index)), wantedKey)) {
        if (!Objects.equals(target.get(index), element)) {
          target.set(index, element);
        }
        continue;
      }
      int existing = indexOf(target, wantedKey, key, index + 1);
      if (existing >= 0) {
        target.remove(existing);
      }
      target.add(index, element);
    }
    if (target.size() > fresh.size()) {
      target.remove(fresh.size(), target.size());
    }
    return true;
  }

  private static <T, K> boolean sameKeys(List<T> target, List<T> fresh, Function<T, K> key) {
    if (target.size() != fresh.size()) {
      return false;
    }
    for (int index = 0; index < fresh.size(); index++) {
      if (!Objects.equals(key.apply(target.get(index)), key.apply(fresh.get(index)))) {
        return false;
      }
    }
    return true;
  }

  private static <T, K> int indexOf(List<T> list, K wanted, Function<T, K> key, int from) {
    for (int index = from; index < list.size(); index++) {
      if (Objects.equals(key.apply(list.get(index)), wanted)) {
        return index;
      }
    }
    return -1;
  }
}
