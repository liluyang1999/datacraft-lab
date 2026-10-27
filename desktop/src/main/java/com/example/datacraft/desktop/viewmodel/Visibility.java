package com.example.datacraft.desktop.viewmodel;

/** A model that polls only while its view is on screen. */
public interface Visibility {

  /** The view came on screen: start polling, fetching at once. */
  void shown();

  /** The view left the screen or the window was minimised: stop polling. */
  void hidden();
}
