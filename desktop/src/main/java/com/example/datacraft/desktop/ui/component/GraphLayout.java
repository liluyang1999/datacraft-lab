package com.example.datacraft.desktop.ui.component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where each task of a DAG goes in the graph: its column is the longest path from a task without
 * upstream tasks, so every edge points right; within a column, tasks keep the order they were
 * given. A downstream id that is not among the nodes is ignored, and a cycle, which a DAG cannot
 * have, leaves its tasks in the first column instead of looping.
 */
public final class GraphLayout {

  /** A task and the ids of the tasks that follow it. */
  public record Node(String id, List<String> downstream) {
    public Node {
      downstream = List.copyOf(downstream);
    }
  }

  /** A task's place: column from the left, row from the top. */
  public record Placement(String id, int column, int row) {}

  private GraphLayout() {}

  public static List<Placement> place(List<Node> nodes) {
    Map<String, Node> byId = new LinkedHashMap<>();
    nodes.forEach(node -> byId.putIfAbsent(node.id(), node));
    Map<String, Integer> upstreamCount = new HashMap<>();
    byId.keySet().forEach(id -> upstreamCount.put(id, 0));
    for (Node node : byId.values()) {
      for (String next : node.downstream()) {
        if (byId.containsKey(next)) {
          upstreamCount.merge(next, 1, Integer::sum);
        }
      }
    }
    Map<String, Integer> column = new HashMap<>();
    Deque<String> ready = new ArrayDeque<>();
    for (String id : byId.keySet()) {
      if (upstreamCount.get(id) == 0) {
        ready.add(id);
        column.put(id, 0);
      }
    }
    while (!ready.isEmpty()) {
      String id = ready.poll();
      for (String next : byId.get(id).downstream()) {
        if (!byId.containsKey(next)) {
          continue;
        }
        column.merge(next, column.get(id) + 1, Math::max);
        if (upstreamCount.merge(next, -1, Integer::sum) == 0) {
          ready.add(next);
        }
      }
    }
    List<Placement> placements = new ArrayList<>();
    Map<Integer, Integer> rows = new HashMap<>();
    for (String id : byId.keySet()) {
      int at = upstreamCount.get(id) > 0 ? 0 : column.getOrDefault(id, 0);
      placements.add(new Placement(id, at, rows.merge(at, 1, Integer::sum) - 1));
    }
    return List.copyOf(placements);
  }
}
