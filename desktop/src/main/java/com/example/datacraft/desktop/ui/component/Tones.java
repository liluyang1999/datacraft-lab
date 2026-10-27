package com.example.datacraft.desktop.ui.component;

import com.example.datacraft.desktop.api.RunTone;
import com.example.datacraft.desktop.service.BackendStatus;
import java.util.List;
import java.util.Locale;

/** The CSS class of each tone: success, running, waiting, failed, warning, none. */
public final class Tones {

  public static final List<String> ALL =
      List.of("success", "running", "waiting", "failed", "warning", "none");

  private Tones() {}

  public static String of(RunTone tone) {
    return tone.name().toLowerCase(Locale.ROOT);
  }

  /** A backend's state as a tone: up is success, connecting is waiting, degraded is warning. */
  public static String of(BackendStatus status) {
    return switch (status.state()) {
      case UP -> "success";
      case CONNECTING -> "waiting";
      case DEGRADED -> "warning";
      case UNAUTHORIZED, DOWN -> "failed";
      case NOT_CONFIGURED -> "none";
    };
  }
}
