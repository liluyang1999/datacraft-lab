package com.example.datacraft.desktop.api;

import java.util.Locale;

/**
 * The five tones every run and task state is shown in: success, running, waiting, failed, none.
 * Airflow has thirteen task states and four run states; the engine has SUCCEEDED and FAILED.
 */
public enum RunTone {
  SUCCESS,
  RUNNING,
  WAITING,
  FAILED,
  NONE;

  public static RunTone of(String state) {
    if (state == null) {
      return NONE;
    }
    return switch (state.strip().toLowerCase(Locale.ROOT)) {
      case "success", "succeeded" -> SUCCESS;
      case "running", "deferred", "restarting" -> RUNNING;
      case "queued", "scheduled", "up_for_retry", "up_for_reschedule", "awaiting_input" -> WAITING;
      case "failed", "upstream_failed" -> FAILED;
      default -> NONE;
    };
  }

  /** Whether more is still to happen: a run or task in this tone is polled faster. */
  public boolean active() {
    return this == RUNNING || this == WAITING;
  }
}
