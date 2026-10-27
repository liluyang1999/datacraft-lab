package com.example.datacraft.desktop.ui.view;

import com.example.datacraft.desktop.api.AirflowModels.DagRun;
import com.example.datacraft.desktop.api.RunTone;
import com.example.datacraft.desktop.store.RecentRunRepository.RecentRun;
import com.example.datacraft.desktop.ui.component.RunRibbon.Segment;
import com.example.datacraft.desktop.util.Formats;
import com.example.datacraft.desktop.viewmodel.Labels;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

/** Ribbon segments for DAG runs and job runs, with the text their tooltip shows. */
final class Segments {

  private Segments() {}

  static List<Segment> ofDagRuns(List<DagRun> runs, Instant now) {
    return runs.stream()
        .map(
            run ->
                new Segment(
                    run.tone(),
                    Labels.state(run.state())
                        + "  "
                        + Formats.when(
                            run.startDate().orElse(run.runAfter()), now, ZoneId.systemDefault())
                        + run.duration()
                            .map(duration -> "  " + Formats.duration(duration))
                            .orElse("")
                        + "\n"
                        + run.runId()))
        .toList();
  }

  static List<Segment> ofJobRuns(List<RecentRun> runs, Instant now) {
    return runs.stream()
        .map(
            run ->
                new Segment(
                    RunTone.of(run.status()),
                    Labels.state(run.status())
                        + "  "
                        + Formats.when(run.startedAt(), now, ZoneId.systemDefault())
                        + "  "
                        + Formats.duration(run.duration())))
        .toList();
  }
}
