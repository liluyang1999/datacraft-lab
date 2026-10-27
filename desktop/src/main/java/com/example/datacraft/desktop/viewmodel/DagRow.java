package com.example.datacraft.desktop.viewmodel;

import com.example.datacraft.desktop.api.AirflowModels.Dag;
import com.example.datacraft.desktop.api.AirflowModels.DagRun;
import com.example.datacraft.desktop.service.PipelineService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** A DAG with its newest runs, newest first: one line of the overview and the pipeline table. */
public record DagRow(Dag dag, List<DagRun> runs) {

  public DagRow {
    runs = List.copyOf(runs);
  }

  public String dagId() {
    return dag.dagId();
  }

  public Optional<DagRun> latest() {
    return runs.stream().findFirst();
  }

  /** Every DAG with its newest {@code limit} runs; the run lists are fetched in parallel. */
  static CompletableFuture<List<DagRow>> fetch(PipelineService pipelines, int limit) {
    return pipelines
        .dags()
        .thenCompose(
            dags -> {
              List<CompletableFuture<DagRow>> rows = new ArrayList<>();
              for (Dag dag : dags) {
                rows.add(
                    pipelines.runs(dag.dagId(), limit).thenApply(runs -> new DagRow(dag, runs)));
              }
              return CompletableFuture.allOf(rows.toArray(CompletableFuture[]::new))
                  .thenApply(ignored -> rows.stream().map(CompletableFuture::join).toList());
            });
  }
}
