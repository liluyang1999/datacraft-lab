package com.example.datacraft.desktop.ui.view;

import javafx.scene.Node;

/** One of the five views: created the first time it is opened, then kept. */
public interface View {

  /** The node shown in the content area. */
  Node node();

  /** The view came on screen. */
  default void shown() {}

  /** The view left the screen, or the window was minimised. */
  default void hidden() {}

  /** Ctrl+R: fetch everything the view shows now. */
  default void refresh() {}

  /** Esc: close what the view has open (a drawer); true when something was closed. */
  default boolean escape() {
    return false;
  }
}
