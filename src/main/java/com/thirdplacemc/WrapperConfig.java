package com.thirdplacemc;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Properties;

public class WrapperConfig {
  private static final String CONFIG_FILE = "wrapper.properties";
  private static final String DEFAULT_GITHUB_REPO = "Quozul/PicoLimbo";

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
        System.out.println("[TPMC Limbo] Loaded configuration from " + CONFIG_FILE);
      } catch (IOException e) {
        System.err.println("[TPMC Limbo] Warning: Could not load " + CONFIG_FILE + ": " + e.getMessage());
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

    // Save default config
    try (FileOutputStream fos = new FileOutputStream(CONFIG_FILE)) {
      properties.store(fos, "PicoLimbo Wrapper Configuration\n" +
          "# github.repo - GitHub repository in format 'owner/repo' (default: Quozul/PicoLimbo)\n" +
          "# download.url - Direct download URL for the archive (overrides GitHub releases if set)");
      System.out.println("[TPMC Limbo] Created default configuration file: " + CONFIG_FILE);
    } catch (IOException e) {
      System.err.println("[TPMC Limbo] Warning: Could not create " + CONFIG_FILE + ": " + e.getMessage());
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
}
