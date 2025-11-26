package com.thirdplacemc;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class WrapperConfig {
  private static final String CONFIG_FILE = "wrapper.properties";
  private static final String DEFAULT_GITHUB_REPO = "Quozul/PicoLimbo";
  private static final String DEFAULT_INSTANCE_NAME = "default";
  private static final String DEFAULT_CONFIG_PATH = "server.toml";

  private final Properties properties;

  public WrapperConfig() {
    this.properties = new Properties();
    loadConfig();
  }

  private void loadConfig() {
    File configFile = new File(CONFIG_FILE);

    if (configFile.exists()) {
      try (FileInputStream fis = new FileInputStream(configFile)) {
        properties.load(fis);
        Logger.info("Loaded configuration from " + CONFIG_FILE);
      } catch (IOException e) {
        Logger.error("Warning: Could not load " + CONFIG_FILE + ": " + e.getMessage());
        createDefaultConfig();
      }
    } else {
      createDefaultConfig();
    }
  }

  private void createDefaultConfig() {
    // Set default values
    properties.setProperty("github.repo", DEFAULT_GITHUB_REPO);
    properties.setProperty("download.url", "");
    properties.setProperty("instances", "");

    // Save default config
    try (FileOutputStream fos = new FileOutputStream(CONFIG_FILE)) {
      properties.store(fos, "PicoLimbo Wrapper Configuration\n" +
          "# github.repo - GitHub repository in format 'owner/repo' (default: Quozul/PicoLimbo)\n" +
          "# download.url - Direct download URL for the archive (overrides GitHub releases if set)\n" +
          "#\n" +
          "# Multi-instance support:\n" +
          "# instances - Comma-separated list of instance names (e.g., lobby,survival,creative)\n" +
          "# instance.<name>.config - Config file path for the instance\n" +
          "# instance.<name>.autoStart - Whether to start this instance automatically (default: true)\n" +
          "#\n" +
          "# Example multi-instance configuration:\n" +
          "# instances=lobby,survival\n" +
          "# instance.lobby.config=configs/lobby.toml\n" +
          "# instance.lobby.autoStart=true\n" +
          "# instance.survival.config=configs/survival.toml\n" +
          "# instance.survival.autoStart=true");
      Logger.info("Created default configuration file: " + CONFIG_FILE);
    } catch (IOException e) {
      Logger.error("Warning: Could not create " + CONFIG_FILE + ": " + e.getMessage());
    }
  }

  public String getGitHubRepo() {
    return properties.getProperty("github.repo", DEFAULT_GITHUB_REPO);
  }

  public String getDownloadUrl() {
    return properties.getProperty("download.url", "").trim();
  }

  public boolean hasCustomDownloadUrl() {
    String url = getDownloadUrl();
    return url != null && !url.isEmpty();
  }

  public List<String> getInstances() {
    String instancesStr = properties.getProperty("instances", "").trim();
    if (instancesStr.isEmpty()) {
      // Legacy mode: single instance with default config
      return Arrays.asList(DEFAULT_INSTANCE_NAME);
    }

    List<String> instances = new ArrayList<>();
    for (String instance : instancesStr.split(",")) {
      String trimmed = instance.trim();
      if (!trimmed.isEmpty()) {
        instances.add(trimmed);
      }
    }

    return instances.isEmpty() ? Arrays.asList(DEFAULT_INSTANCE_NAME) : instances;
  }

  public String getInstanceConfig(String instanceName) {
    if (instanceName.equals(DEFAULT_INSTANCE_NAME)) {
      // Legacy mode: use default config path
      return properties.getProperty("instance." + DEFAULT_INSTANCE_NAME + ".config", DEFAULT_CONFIG_PATH);
    }

    String configPath = properties.getProperty("instance." + instanceName + ".config");
    if (configPath == null || configPath.trim().isEmpty()) {
      Logger.warn("No config path defined for instance '" + instanceName + "', using default: " + DEFAULT_CONFIG_PATH);
      return DEFAULT_CONFIG_PATH;
    }
    return configPath.trim();
  }

  public boolean shouldAutoStart(String instanceName) {
    String autoStart = properties.getProperty("instance." + instanceName + ".autoStart", "true");
    return Boolean.parseBoolean(autoStart.trim());
  }

  public boolean isLegacyMode() {
    String instancesStr = properties.getProperty("instances", "").trim();
    return instancesStr.isEmpty();
  }

  public void validateInstances() throws IllegalStateException {
    List<String> instances = getInstances();
    List<String> errors = new ArrayList<>();

    for (String instance : instances) {
      if (instance.equals(DEFAULT_INSTANCE_NAME) && isLegacyMode()) {
        // Skip validation for legacy mode
        continue;
      }

      String configPath = getInstanceConfig(instance);
      File configFile = new File(configPath);

      if (!configFile.exists()) {
        errors.add("Instance '" + instance + "': Config file not found: " + configPath);
      }
    }

    if (!errors.isEmpty()) {
      throw new IllegalStateException("Instance configuration errors:\n" + String.join("\n", errors));
    }
  }
}
