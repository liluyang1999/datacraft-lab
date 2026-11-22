package com.example.datacraft.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class RunActivityTest {

  /** A clock that moves only when told to. */
  private static final class StepClock extends Clock {
    private Instant now = Instant.parse("2026-11-20T00:00:00Z");

    void advance(Duration step) {
      now = now.plus(step);
    }

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  @Test
  void countsEachOutcomeOnceAndListsActiveRunsOldestFirst() {
    StepClock clock = new StepClock();
    RunActivity activity = new RunActivity(clock);
    clock.advance(Duration.ofSeconds(1));
    long first = activity.begin("csv-profile");
    clock.advance(Duration.ofSeconds(2));
    long second = activity.begin("echo");
    activity.rejected();

    RunActivity.Snapshot busy = activity.snapshot();
    assertEquals(
        List.of(
            new RunActivity.ActiveRun("csv-profile", Instant.parse("2026-11-20T00:00:01Z")),
            new RunActivity.ActiveRun("echo", Instant.parse("2026-11-20T00:00:03Z"))),
        busy.active());
    assertEquals(2, busy.started());
    assertEquals(1, busy.rejected());
    assertEquals(Duration.ofSeconds(3), busy.uptime());

    activity.end(first, true);
    activity.end(first, false); // a second end of the same run changes nothing
    activity.end(second, false);
    RunActivity.Snapshot idle = activity.snapshot();
    assertEquals(List.of(), idle.active());
    assertEquals(1, idle.succeeded());
    assertEquals(1, idle.failed());
    assertEquals(2, idle.started());
  }
}
