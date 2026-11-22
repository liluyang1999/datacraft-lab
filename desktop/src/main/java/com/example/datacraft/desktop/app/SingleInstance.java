package com.example.datacraft.desktop.app;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Instant;
import java.util.Optional;

/**
 * One app per data folder. The first start locks {@code instance.lock} in the data folder; a later
 * start with the same folder finds it locked, touches {@code activate} there and exits, and the
 * running app brings its window forward when it sees the file change. Starts with another folder
 * (tests, packaging checks, a portable copy) are independent of each other.
 *
 * <p>On Windows the running app also holds the named mutex {@link #MUTEX}, which the installer and
 * the uninstaller look for ({@code AppMutex}), so neither replaces files of a running app. Creating
 * it is the second native call of the app (after DPAPI), hence the suppressed warning.
 */
@SuppressWarnings("restricted")
public final class SingleInstance implements AutoCloseable {

  /** The installer's {@code AppMutex}; it must equal deploy/windows/installer.iss. */
  public static final String MUTEX = "com.datacraft.lab";

  static final String LOCK = "instance.lock";
  static final String SIGNAL = "activate";
  private static final System.Logger LOG = System.getLogger(SingleInstance.class.getName());

  private final Path home;
  private final FileChannel channel;
  private final FileLock lock;
  private volatile WatchService watcher;

  private SingleInstance(Path home, FileChannel channel, FileLock lock) {
    this.home = home;
    this.channel = channel;
    this.lock = lock;
  }

  /**
   * Locks the folder for this process; empty when another process holds it, after asking that one
   * to come forward.
   */
  public static Optional<SingleInstance> acquire(AppPaths paths) {
    Path home = paths.home();
    try {
      FileChannel channel =
          FileChannel.open(home.resolve(LOCK), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
      FileLock lock;
      try {
        lock = channel.tryLock();
      } catch (OverlappingFileLockException sameProcess) {
        lock = null;
      }
      if (lock == null) {
        channel.close();
        signal(home);
        return Optional.empty();
      }
      return Optional.of(new SingleInstance(home, channel, lock));
    } catch (IOException exception) {
      throw new UncheckedIOException("Cannot lock " + home.resolve(LOCK), exception);
    }
  }

  /** Asks the app that holds {@code home} to bring its window forward. */
  static void signal(Path home) throws IOException {
    Files.writeString(home.resolve(SIGNAL), Instant.now().toString(), StandardCharsets.UTF_8);
  }

  /**
   * Runs {@code activate} on a daemon thread each time a later start signals; returns at once. Also
   * takes the installer mutex on Windows.
   */
  public void onActivate(Runnable activate) {
    holdInstallerMutex();
    try {
      WatchService service = home.getFileSystem().newWatchService();
      home.register(
          service, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
      watcher = service;
      Thread thread = new Thread(() -> watch(service, activate), "datacraft-instance");
      thread.setDaemon(true);
      thread.start();
    } catch (IOException exception) {
      LOG.log(
          System.Logger.Level.WARNING, "Later starts cannot bring this window forward", exception);
    }
  }

  private void watch(WatchService service, Runnable activate) {
    try {
      while (true) {
        WatchKey key = service.take();
        boolean signalled =
            key.pollEvents().stream().anyMatch(event -> Path.of(SIGNAL).equals(event.context()));
        if (signalled) {
          activate.run();
        }
        if (!key.reset()) {
          return;
        }
      }
    } catch (InterruptedException | ClosedWatchServiceException stopped) {
      Thread.currentThread().interrupt();
    }
  }

  private static void holdInstallerMutex() {
    if (!System.getProperty("os.name", "").startsWith("Windows")) {
      return;
    }
    try {
      MethodHandle create =
          Linker.nativeLinker()
              .downcallHandle(
                  SymbolLookup.libraryLookup("kernel32", Arena.global())
                      .findOrThrow("CreateMutexW"),
                  FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, ADDRESS));
      // Held until the process ends, when Windows releases it; never closed on purpose.
      MemorySegment name = Arena.global().allocateFrom(MUTEX, StandardCharsets.UTF_16LE);
      MemorySegment handle = (MemorySegment) create.invokeExact(MemorySegment.NULL, 0, name);
      if (handle.equals(MemorySegment.NULL)) {
        LOG.log(System.Logger.Level.WARNING, "The installer mutex could not be created");
      }
    } catch (Throwable failure) {
      LOG.log(System.Logger.Level.WARNING, "The installer mutex could not be created", failure);
    }
  }

  @Override
  public void close() {
    try {
      WatchService service = watcher;
      if (service != null) {
        service.close();
      }
      lock.release();
      channel.close();
    } catch (IOException exception) {
      LOG.log(System.Logger.Level.DEBUG, "Closing the instance lock failed", exception);
    }
  }
}
