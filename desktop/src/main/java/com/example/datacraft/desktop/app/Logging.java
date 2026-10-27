package com.example.datacraft.desktop.app;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Writes the app's log to {@code ~/.datacraft/logs/desktop-0.log} (1 MB each, three kept) and
 * warnings to the console. Messages never carry a token or password: the code that logs only names
 * what failed.
 */
public final class Logging {

  static final int LIMIT_BYTES = 1024 * 1024;
  static final int FILES = 3;
  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

  private Logging() {}

  /** One line per record: time, level, logger, message, and the stack trace when there is one. */
  static final class LineFormatter extends Formatter {
    @Override
    public String format(LogRecord record) {
      StringBuilder line =
          new StringBuilder()
              .append(TIME.format(Instant.ofEpochMilli(record.getMillis())))
              .append(' ')
              .append(record.getLevel().getName())
              .append(' ')
              .append(record.getLoggerName())
              .append(": ")
              .append(formatMessage(record))
              .append(System.lineSeparator());
      if (record.getThrown() != null) {
        java.io.StringWriter trace = new java.io.StringWriter();
        record.getThrown().printStackTrace(new java.io.PrintWriter(trace));
        line.append(trace);
      }
      return line.toString();
    }
  }

  public static void configure(AppPaths paths) {
    LogManager.getLogManager().reset();
    Logger root = Logger.getLogger("");
    root.setLevel(Level.INFO);
    ConsoleHandler console = new ConsoleHandler();
    console.setLevel(Level.WARNING);
    console.setFormatter(new LineFormatter());
    root.addHandler(console);
    try {
      Handler file =
          new FileHandler(
              paths.logs().resolve("desktop-%g.log").toString(), LIMIT_BYTES, FILES, true);
      file.setEncoding("UTF-8");
      file.setFormatter(new LineFormatter());
      file.setLevel(Level.INFO);
      root.addHandler(file);
    } catch (IOException | SecurityException unavailable) {
      root.log(
          Level.WARNING, "The log file cannot be opened; logging to the console only", unavailable);
    }
  }
}
