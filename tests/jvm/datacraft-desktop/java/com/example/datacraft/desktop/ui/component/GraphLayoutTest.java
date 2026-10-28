package com.example.datacraft.desktop.ui.component;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.datacraft.desktop.ui.component.GraphLayout.Node;
import com.example.datacraft.desktop.ui.component.GraphLayout.Placement;
import java.util.List;
import org.junit.jupiter.api.Test;

class GraphLayoutTest {

  @Test
  void aChainRunsLeftToRight() {
    assertEquals(
        List.of(
            new Placement("download", 0, 0),
            new Placement("csv_to_parquet", 1, 0),
            new Placement("row_count", 2, 0)),
        GraphLayout.place(
            List.of(
                new Node("download", List.of("csv_to_parquet")),
                new Node("csv_to_parquet", List.of("row_count")),
                new Node("row_count", List.of()))));
  }

  @Test
  void aTaskGoesRightOfItsLongestUpstreamPathAndRowsKeepTheGivenOrder() {
    // a -> b -> d, a -> c -> d, a -> d: d follows the longer path, b and c share a column.
    assertEquals(
        List.of(
            new Placement("a", 0, 0),
            new Placement("b", 1, 0),
            new Placement("c", 1, 1),
            new Placement("d", 2, 0),
            new Placement("lone", 0, 1)),
        GraphLayout.place(
            List.of(
                new Node("a", List.of("b", "c", "d")),
                new Node("b", List.of("d")),
                new Node("c", List.of("d")),
                new Node("d", List.of()),
                new Node("lone", List.of()))));
  }

  @Test
  void unknownDownstreamTasksAreIgnoredAndACycleDoesNotLoop() {
    assertEquals(
        List.of(new Placement("a", 0, 0), new Placement("b", 1, 0)),
        GraphLayout.place(List.of(new Node("a", List.of("b", "gone")), new Node("b", List.of()))));
    assertEquals(
        List.of(new Placement("x", 0, 0), new Placement("y", 0, 1)),
        GraphLayout.place(List.of(new Node("x", List.of("y")), new Node("y", List.of("x")))));
  }
}
