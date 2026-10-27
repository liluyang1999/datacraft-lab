package com.example.datacraft.desktop.ui.view;

import com.example.datacraft.desktop.api.AirflowModels.DagRun;
import com.example.datacraft.desktop.api.RunTone;
import com.example.datacraft.desktop.store.RecentRunRepository.RecentRun;
import com.example.datacraft.desktop.ui.component.RunBars.Segment;
import com.example.datacraft.desktop.util.Formats;
import com.example.datacraft.desktop.viewmodel.Labels;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/** Run bars for DAG runs and job runs, with what their tooltip says and the key a click picks. */
final class Segments {

  private Segments() {}

  static List<Segment> ofDagRuns(List<DagRun> runs, Instant now) {
    return runs.stream()
        .map(
            run ->
                new Segment(
                    run.tone(),
                    run.duration(),
                    Labels.state(run.state())
                        + "  "
                        + Formats.when(
                            run.startDate().orElse(run.runAfter()), now, ZoneId.systemDefault())
                        + run.duration()
                            .map(duration -> "  " + Formats.duration(duration))
                            .orElse("")
                        + "\n"
                        + Labels.runType(run.runType())
                        + "  "
                        + run.runId(),
                    run.runId()))
        .toList();
  }

  static List<Segment> ofJobRuns(List<RecentRun> runs, Instant now) {
    return runs.stream()
        .map(
            run ->
                new Segment(
                    RunTone.of(run.status()),
                    Optional.of(run.duration()),
                    Labels.state(run.status())
                        + "  "
                        + Formats.when(run.startedAt(), now, ZoneId.systemDefault())
                        + "  "
                        + Formats.duration(run.duration())
                        + (run.message().isBlank() ? "" : "\n" + run.message()),
                    run.jobName() + ":" + run.startedAt().toEpochMilli()))
        .toList();
  }
}
