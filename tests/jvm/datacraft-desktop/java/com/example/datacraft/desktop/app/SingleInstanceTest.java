package com.example.datacraft.desktop.app;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SingleInstanceTest {

  @TempDir Path home;

  @Test
  void aSecondStartWithTheSameFolderSignalsTheFirstAndAnotherFolderIsIndependent(
      @TempDir Path other) throws Exception {
    AppPaths paths = new AppPaths(home).ensureCreated();
    try (SingleInstance first = SingleInstance.acquire(paths).orElseThrow()) {
      CountDownLatch activated = new CountDownLatch(1);
      first.onActivate(activated::countDown);

      assertTrue(SingleInstance.acquire(paths).isEmpty(), "the folder is already held");
      assertTrue(Files.exists(home.resolve(SingleInstance.SIGNAL)));
      assertTrue(activated.await(10, TimeUnit.SECONDS), "the running app was not asked forward");

      try (SingleInstance separate =
          SingleInstance.acquire(new AppPaths(other).ensureCreated()).orElseThrow()) {
        assertTrue(Files.exists(other.resolve(SingleInstance.LOCK)), separate.toString());
      }
    }
    // Released on close: the next start holds the folder again.
    try (SingleInstance again = SingleInstance.acquire(paths).orElseThrow()) {
      assertTrue(Files.exists(home.resolve(SingleInstance.LOCK)), again.toString());
    }
  }
}
