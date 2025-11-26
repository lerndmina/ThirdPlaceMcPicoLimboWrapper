package com.thirdplacemc;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

public class Logger {
  // ANSI color codes
  private static final String RESET = "\u001B[0m";
  private static final String GRAY = "\u001B[90m";
  private static final String GREEN = "\u001B[32m";
  private static final String YELLOW = "\u001B[33m";
  private static final String RED = "\u001B[31m";
  private static final String CYAN = "\u001B[36m";

  private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter
      .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'");

  public static void info(String message) {
    log("INFO", GREEN, null, message);
  }

  public static void info(String instance, String message) {
    log("INFO", GREEN, instance, message);
  }

  public static void warn(String message) {
    log("WARN", YELLOW, null, message);
  }

  public static void warn(String instance, String message) {
    log("WARN", YELLOW, instance, message);
  }

  public static void error(String message) {
    log("ERROR", RED, null, message);
  }

  public static void error(String instance, String message) {
    log("ERROR", RED, instance, message);
  }

  public static void debug(String message) {
    log("DEBUG", CYAN, null, message);
  }

  public static void debug(String instance, String message) {
    log("DEBUG", CYAN, instance, message);
  }

  private static void log(String level, String levelColor, String instance, String message) {
    String timestamp = ZonedDateTime.now().format(TIMESTAMP_FORMAT);
    StringBuilder sb = new StringBuilder();

    // Gray timestamp
    sb.append(GRAY).append(timestamp).append(RESET);
    sb.append("  ");

    // Colored level
    sb.append(levelColor).append(String.format("%-5s", level)).append(RESET);
    sb.append(" ");

    // Instance name in cyan if provided
    if (instance != null && !instance.isEmpty()) {
      sb.append(CYAN).append("[").append(instance).append("]").append(RESET);
      sb.append(" ");
    }

    // Message
    sb.append(message);

    System.out.println(sb.toString());
  }
}
