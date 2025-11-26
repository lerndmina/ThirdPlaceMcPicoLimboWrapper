package com.thirdplacemc;

import java.io.File;

public class InstanceInfo {
  private final String name;
  private final String configPath;
  private final boolean autoStart;
  private Process process;
  private Thread outputThread;
  private int restartCount;
  private boolean shouldRestart;

  public InstanceInfo(String name, String configPath, boolean autoStart) {
    this.name = name;
    this.configPath = configPath;
    this.autoStart = autoStart;
    this.process = null;
    this.outputThread = null;
    this.restartCount = 0;
    this.shouldRestart = true;
  }

  public String getName() {
    return name;
  }

  public String getConfigPath() {
    return configPath;
  }

  public boolean shouldAutoStart() {
    return autoStart;
  }

  public Process getProcess() {
    return process;
  }

  public void setProcess(Process process) {
    this.process = process;
  }

  public Thread getOutputThread() {
    return outputThread;
  }

  public void setOutputThread(Thread outputThread) {
    this.outputThread = outputThread;
  }

  public int getRestartCount() {
    return restartCount;
  }

  public void incrementRestartCount() {
    this.restartCount++;
  }

  public void resetRestartCount() {
    this.restartCount = 0;
  }

  public boolean shouldRestart() {
    return shouldRestart;
  }

  public void setShouldRestart(boolean shouldRestart) {
    this.shouldRestart = shouldRestart;
  }

  public boolean isRunning() {
    return process != null && process.isAlive();
  }

  public boolean configFileExists() {
    return new File(configPath).exists();
  }
}
