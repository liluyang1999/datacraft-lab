package com.example.datacraft.desktop.util;

import java.util.concurrent.Executor;
import javafx.application.Platform;

/** The JavaFX application thread: where every property change and node update happens. */
public final class Fx {

  /** Runs on the FX thread: now when already there, otherwise at the next pulse. */
  public static final Executor UI =
      task -> {
        if (Platform.isFxApplicationThread()) {
          task.run();
        } else {
          Platform.runLater(task);
        }
      };

  private Fx() {}
}
