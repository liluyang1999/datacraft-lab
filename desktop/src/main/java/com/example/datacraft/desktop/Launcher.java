package com.example.datacraft.desktop;

import com.example.datacraft.desktop.app.AppPaths;
import com.example.datacraft.desktop.app.Logging;
import javafx.application.Application;

/**
 * The entry point. It checks the runtime before JavaFX loads, so a wrong Java prints one line of
 * reason instead of a stack trace, and exits with status 2.
 */
public final class Launcher {

  static final int REQUIRED_FEATURE = 25;
  static final String PIPE_PROPERTY = "jdk.net.unixdomain.tmpdir";

  private Launcher() {}

  public static void main(String[] args) {
    var enteredProcess = ProcessHandle.current().info();
    var enteredAt = java.time.Instant.now();
    String problem = problem(Runtime.version().feature(), javafxPresent());
    if (problem != null) {
      System.err.println(problem);
      System.exit(2);
    }
    // --smoke: quit two seconds after the first frame, to check that a build or package starts.
    if (java.util.Arrays.asList(args).contains("--smoke")) {
      System.setProperty(DesktopApp.SMOKE_PROPERTY, "true");
    }
    AppPaths paths = AppPaths.resolve().ensureCreated();
    usePipeFolder(paths);
    Logging.configure(paths);
    DesktopApp.mark("launcher entered", enteredAt, enteredProcess);
    DesktopApp.mark("launcher configured");
    // Open the store and the services while JavaFX starts; neither needs the other.
    DesktopApp.prepare(paths);
    Application.launch(DesktopApp.class, args);
  }

  /**
   * Points the JDK's pipe sockets at {@code ~/.datacraft/tmp} unless the property is already set.
   * It must run before the first NIO selector is opened, which reads the property once.
   */
  static void usePipeFolder(AppPaths paths) {
    if (System.getProperty(PIPE_PROPERTY) == null) {
      System.setProperty(PIPE_PROPERTY, paths.pipes().toString());
    }
  }

  static String problem(int feature, boolean javafx) {
    if (feature < REQUIRED_FEATURE) {
      return "数艺工坊需要 Java " + REQUIRED_FEATURE + " 或更新的版本，当前是 Java " + feature + "。";
    }
    if (!javafx) {
      return "找不到 JavaFX：用 ./mvnw -f desktop/pom.xml javafx:run 启动，或把 JavaFX 25 放在模块路径上。";
    }
    return null;
  }

  private static boolean javafxPresent() {
    try {
      Class.forName("javafx.application.Application", false, Launcher.class.getClassLoader());
      return true;
    } catch (ClassNotFoundException | LinkageError missing) {
      return false;
    }
  }
}
