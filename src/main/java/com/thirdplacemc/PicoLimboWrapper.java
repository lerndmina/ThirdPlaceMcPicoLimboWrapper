package com.thirdplacemc;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.HashSet;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class PicoLimboWrapper {
  private static final String BINARIES_DIR = "binaries";
  private static final int MAX_RESTART_COUNT = 5;

  private static Map<String, InstanceInfo> instances = new ConcurrentHashMap<>();
  private static WrapperConfig config;
  private static String currentArchiveName;
  private static File currentBinaryFile;
  private static volatile boolean shouldExit = false;
  private static volatile boolean isUpdating = false;
  private static final Object updateLock = new Object();

  public static void main(String[] args) {
    try {
      Logger.info("Starting PicoLimbo Wrapper...");

      // Load configuration
      config = new WrapperConfig();

      // Detect OS and architecture
      currentArchiveName = detectBinaryName();
      Logger.info("Detected OS: " + getOSInfo());

      // Ensure binary exists (download if needed)
      currentBinaryFile = ensureBinaryExists(currentArchiveName);

      // Set executable permissions on Unix systems
      if (!isWindows()) {
        currentBinaryFile.setExecutable(true, false);
        Logger.info("Set executable permissions");
      }

      // Load and validate instances
      List<String> instanceNames = config.getInstances();
      for (String name : instanceNames) {
        String configPath = config.getInstanceConfig(name);
        boolean autoStart = config.shouldAutoStart(name);
        InstanceInfo instance = new InstanceInfo(name, configPath, autoStart);
        instances.put(name, instance);
      }

      // Validate instance configurations
      config.validateInstances();

      if (config.isLegacyMode()) {
        Logger.info("Running in legacy single-instance mode");
      } else {
        Logger.info("Configured instances: " + String.join(", ", instanceNames));
      }

      // Register shutdown hook
      registerShutdownHook();

      // Start instances that are configured for auto-start
      for (InstanceInfo instance : instances.values()) {
        if (instance.shouldAutoStart()) {
          launchInstance(instance.getName());
        }
      }

      // Start input monitoring thread
      Thread inputThread = new Thread(() -> {
        try (BufferedReader consoleReader = new BufferedReader(new InputStreamReader(System.in))) {
          String line;
          while ((line = consoleReader.readLine()) != null) {
            String input = line.trim();
            String[] parts = input.split("\\s+", 2);
            String command = parts[0].toLowerCase();

            // Check for global stop/exit/quit commands
            if (command.equals("stop") || command.equals("exit") || command.equals("quit") || command.equals("end")) {
              if (parts.length > 1) {
                // Stop specific instance
                stopInstance(parts[1], false);
              } else {
                // Stop all and exit
                Logger.info("Received stop command, shutting down all instances...");
                shouldExit = true;
                for (InstanceInfo instance : instances.values()) {
                  if (instance.isRunning()) {
                    stopInstance(instance.getName(), false);
                  }
                }
                break;
              }
            }
            // Start instance command
            else if (command.equals("start") && parts.length > 1) {
              launchInstance(parts[1]);
            }
            // Restart instance command
            else if (command.equals("restart") && parts.length > 1) {
              restartInstance(parts[1]);
            }
            // Status command
            else if (command.equals("status")) {
              showStatus();
            }
            // Help command
            else if (command.equals("help")) {
              showHelp();
            }
            // Update command
            else if (command.equals("update") || command.equals("reload")) {
              Logger.info("Received update command, downloading latest version...");
              handleUpdate();
            } else if (!input.isEmpty()) {
              Logger.warn("Unknown command: " + input + " (type 'help' for available commands)");
            }
          }
        } catch (IOException e) {
          // Console closed, this is normal
        }
      }, "Input-Monitor");
      inputThread.setDaemon(true);
      inputThread.start();

      // Main monitoring loop - just keep wrapper alive
      while (!shouldExit) {
        try {
          Thread.sleep(1000);

          // Wait for update to complete if in progress
          synchronized (updateLock) {
            while (isUpdating) {
              updateLock.wait();
            }
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
      }

      Logger.info("Shutdown complete");
      System.exit(0);

    } catch (Exception e) {
      Logger.error("Fatal error: " + e.getMessage());
      e.printStackTrace();
      System.exit(1);
    }
  }

  private static void launchInstance(String instanceName) {
    InstanceInfo instance = instances.get(instanceName);
    if (instance == null) {
      Logger.error("Instance '" + instanceName + "' not found");
      return;
    }

    if (instance.isRunning()) {
      Logger.warn(instanceName, "Instance is already running");
      return;
    }

    try {
      Logger.info(instanceName, "Launching instance...");

      String configPath = instance.getConfigPath();
      ProcessBuilder processBuilder = new ProcessBuilder(
          currentBinaryFile.getAbsolutePath(),
          "-c", configPath);
      processBuilder.directory(new File(System.getProperty("user.dir")));
      processBuilder.redirectErrorStream(true);

      Process process = processBuilder.start();
      instance.setProcess(process);

      // Forward output from this instance to console with instance name prefix
      Thread outputThread = new Thread(() -> {
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(process.getInputStream()))) {
          String line;
          while ((line = reader.readLine()) != null) {
            // Forward PicoLimbo output with instance prefix
            System.out.println("\u001B[36m[" + instanceName + "]\u001B[0m " + line);
          }
        } catch (IOException e) {
          // Process ended, this is normal
        }

        // Process ended, check if should auto-restart
        if (instance.shouldRestart() && !shouldExit) {
          if (instance.getRestartCount() < MAX_RESTART_COUNT) {
            instance.incrementRestartCount();
            Logger.warn(instanceName, "Process crashed, restarting... (attempt " + instance.getRestartCount() + "/"
                + MAX_RESTART_COUNT + ")");
            try {
              Thread.sleep(1000); // Wait before restart
              launchInstance(instanceName);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          } else {
            Logger.error(instanceName, "Max restart attempts reached, giving up");
            instance.setShouldRestart(false);
          }
        }
      }, "Output-" + instanceName);
      outputThread.setDaemon(false);
      outputThread.start();
      instance.setOutputThread(outputThread);

      instance.resetRestartCount();
      Logger.info(instanceName, "Instance launched successfully");

    } catch (IOException e) {
      Logger.error(instanceName, "Failed to launch: " + e.getMessage());
      e.printStackTrace();
    }
  }

  private static void stopInstance(String instanceName, boolean setShouldRestart) {
    InstanceInfo instance = instances.get(instanceName);
    if (instance == null) {
      Logger.error("Instance '" + instanceName + "' not found");
      return;
    }

    if (!instance.isRunning()) {
      Logger.warn(instanceName, "Instance is not running");
      return;
    }

    try {
      Logger.info(instanceName, "Stopping instance...");
      instance.setShouldRestart(setShouldRestart);

      Process process = instance.getProcess();
      process.destroy();
      if (!process.waitFor(5, TimeUnit.SECONDS)) {
        process.destroyForcibly();
      }

      Logger.info(instanceName, "Instance stopped");
    } catch (InterruptedException e) {
      Logger.error(instanceName, "Error stopping instance: " + e.getMessage());
      instance.getProcess().destroyForcibly();
    }
  }

  private static void restartInstance(String instanceName) {
    InstanceInfo instance = instances.get(instanceName);
    if (instance == null) {
      Logger.error("Instance '" + instanceName + "' not found");
      return;
    }

    Logger.info(instanceName, "Restarting instance...");
    if (instance.isRunning()) {
      stopInstance(instanceName, false);
      try {
        Thread.sleep(500); // Brief pause before restart
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    instance.setShouldRestart(true);
    launchInstance(instanceName);
  }

  private static void showStatus() {
    Logger.info("Instance Status:");
    for (Map.Entry<String, InstanceInfo> entry : instances.entrySet()) {
      String name = entry.getKey();
      InstanceInfo instance = entry.getValue();
      String status = instance.isRunning() ? "\u001B[32mRUNNING\u001B[0m" : "\u001B[31mSTOPPED\u001B[0m";
      String config = instance.getConfigPath();
      String restarts = instance.getRestartCount() > 0 ? " (restarts: " + instance.getRestartCount() + ")" : "";
      Logger.info("  " + name + ": " + status + " - " + config + restarts);
    }
  }

  private static void showHelp() {
    Logger.info("Available commands:");
    Logger.info("  \u001B[33mstop\u001B[0m                 - Stop all instances and exit wrapper");
    Logger.info("  \u001B[33mstop <instance>\u001B[0m      - Stop a specific instance");
    Logger.info("  \u001B[33mstart <instance>\u001B[0m     - Start a specific instance");
    Logger.info("  \u001B[33mrestart <instance>\u001B[0m   - Restart a specific instance");
    Logger.info(
        "  \u001B[33mupdate\u001B[0m               - Update PicoLimbo binary and restart all running instances");
    Logger.info("  \u001B[33mstatus\u001B[0m               - Show status of all instances");
    Logger.info("  \u001B[33mhelp\u001B[0m                 - Show this help message");
    Logger.info("  \u001B[33mexit/quit/end\u001B[0m        - Stop all instances and exit wrapper");
  }

  private static void handleUpdate() {
    synchronized (updateLock) {
      isUpdating = true;
    }

    // Track which instances were running before the update
    Set<String> runningInstances = new HashSet<>();
    for (InstanceInfo instance : instances.values()) {
      if (instance.isRunning()) {
        runningInstances.add(instance.getName());
      }
    }

    File backupBinary = null;
    try {
      // Download new version
      File archiveFile = new File(BINARIES_DIR, currentArchiveName);

      Logger.info("Downloading latest version...");
      downloadArchive(currentArchiveName, archiveFile);

      // Extract to temporary directory
      Logger.info("Extracting new version...");
      File tempDir = new File(BINARIES_DIR, "update_temp");
      if (tempDir.exists()) {
        deleteDirectory(tempDir);
      }
      tempDir.mkdirs();

      extractArchive(archiveFile, tempDir);

      // Find the extracted binary in temp directory
      String binaryName = isWindows() ? "pico_limbo.exe" : "pico_limbo";
      File newBinary = new File(tempDir, binaryName);

      if (!newBinary.exists()) {
        throw new IOException("Extracted binary not found: " + newBinary.getPath());
      }

      // Set executable permissions on Unix
      if (!isWindows()) {
        newBinary.setExecutable(true, false);
      }

      Logger.info("Stopping all running instances...");

      // Stop all running instances
      for (String instanceName : runningInstances) {
        stopInstance(instanceName, false);
      }

      // Small delay to ensure file handles are released
      Thread.sleep(500);

      // Backup old binary
      backupBinary = new File(BINARIES_DIR, binaryName + ".backup");
      if (backupBinary.exists()) {
        backupBinary.delete();
      }
      if (currentBinaryFile.exists()) {
        currentBinaryFile.renameTo(backupBinary);
      }

      // Move new binary to final location
      Logger.info("Installing new binary...");
      if (!newBinary.renameTo(currentBinaryFile)) {
        // If rename fails (cross-device link), try copy
        Files.copy(newBinary.toPath(), currentBinaryFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        newBinary.delete();
      }

      // Set executable permissions again on Unix (in case copy was used)
      if (!isWindows()) {
        currentBinaryFile.setExecutable(true, false);
      }

      // Clean up temp dir and archive
      deleteDirectory(tempDir);
      if (archiveFile.exists()) {
        archiveFile.delete();
      }

      // Verify the new binary by attempting to start it and checking for "Listening"
      // message
      Logger.info("Verifying new binary...");
      ProcessBuilder verifyBuilder = new ProcessBuilder(currentBinaryFile.getAbsolutePath());
      verifyBuilder.redirectErrorStream(true);
      Process verifyProcess = verifyBuilder.start();

      // Read output and look for "Listening" message
      final boolean[] foundListening = { false };
      Thread outputReader = new Thread(() -> {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(verifyProcess.getInputStream()))) {
          String line;
          while ((line = reader.readLine()) != null) {
            if (line.contains("Listening on:")) {
              foundListening[0] = true;
              break;
            }
          }
        } catch (IOException e) {
          // Ignore
        }
      });
      outputReader.start();

      // Wait up to 5 seconds for the "Listening" message
      for (int i = 0; i < 50; i++) {
        if (foundListening[0]) {
          break;
        }
        if (!verifyProcess.isAlive()) {
          throw new IOException(
              "New binary crashed during verification (exit code: " + verifyProcess.exitValue() + ")");
        }
        Thread.sleep(100);
      }

      // Kill the test process
      verifyProcess.destroy();
      if (!verifyProcess.waitFor(5, TimeUnit.SECONDS)) {
        verifyProcess.destroyForcibly();
      }

      if (!foundListening[0]) {
        throw new IOException("New binary verification failed: did not see 'Listening' message within 5 seconds");
      }

      Logger.info("Verification successful! Update complete, restarting instances...");

      // Delete backup since update was successful
      if (backupBinary != null && backupBinary.exists()) {
        backupBinary.delete();
      }

      // Restart instances that were running before the update
      for (String instanceName : runningInstances) {
        Logger.info("Restarting instance: " + instanceName);
        launchInstance(instanceName);
      }

    } catch (Exception e) {
      Logger.error("Update failed: " + e.getMessage());
      e.printStackTrace();

      // Clean up temporary files
      File tempDir = new File(BINARIES_DIR, "update_temp");
      if (tempDir.exists()) {
        deleteDirectory(tempDir);
      }

      // Restore backup if it exists
      if (backupBinary != null && backupBinary.exists()) {
        Logger.info("Restoring previous version...");
        if (currentBinaryFile.exists()) {
          currentBinaryFile.delete();
        }
        backupBinary.renameTo(currentBinaryFile);

        // Set executable permissions on Unix
        if (!isWindows()) {
          currentBinaryFile.setExecutable(true, false);
        }

        // Restart instances with the old binary
        for (String instanceName : runningInstances) {
          Logger.info("Restarting instance with previous version: " + instanceName);
          launchInstance(instanceName);
        }
      }

    } finally {
      // Always clear the updating flag and notify waiting threads
      synchronized (updateLock) {
        isUpdating = false;
        updateLock.notifyAll();
      }
    }
  }

  private static void deleteDirectory(File directory) {
    if (directory.exists()) {
      File[] files = directory.listFiles();
      if (files != null) {
        for (File file : files) {
          if (file.isDirectory()) {
            deleteDirectory(file);
          } else {
            file.delete();
          }
        }
      }
      directory.delete();
    }
  }

  private static String detectBinaryName() {
    String os = System.getProperty("os.name").toLowerCase();
    String arch = System.getProperty("os.arch").toLowerCase();

    if (os.contains("win")) {
      return "pico_limbo_windows-x86_64.zip";
    } else if (os.contains("linux")) {
      if (arch.contains("aarch64") || arch.contains("arm64")) {
        return "pico_limbo_linux-aarch64-musl.tar.gz";
      } else {
        return "pico_limbo_linux-x86_64-musl.tar.gz";
      }
    } else if (os.contains("mac")) {
      return "pico_limbo_macos-aarch64.tar.gz";
    }

    throw new RuntimeException("Unsupported operating system: " + os + " " + arch);
  }

  private static String getOSInfo() {
    String os = System.getProperty("os.name");
    String arch = System.getProperty("os.arch");
    return os + " " + arch;
  }

  private static boolean isWindows() {
    return System.getProperty("os.name").toLowerCase().contains("win");
  }

  private static File ensureBinaryExists(String archiveName) throws IOException {
    // Create binaries directory if it doesn't exist
    Path binariesPath = Paths.get(BINARIES_DIR);
    if (!Files.exists(binariesPath)) {
      Files.createDirectories(binariesPath);
    }

    // Determine the expected binary name after extraction
    String binaryName = getBinaryNameFromArchive(archiveName);
    File binaryFile = new File(BINARIES_DIR, binaryName);

    // Check if binary already exists
    if (binaryFile.exists()) {
      Logger.info("Binary found: " + binaryName);
      return binaryFile;
    }

    // Download and extract archive from GitHub releases
    Logger.info("Binary not found, fetching latest release...");
    File archiveFile = new File(BINARIES_DIR, archiveName);
    downloadArchive(archiveName, archiveFile);
    extractArchive(archiveFile, binariesPath.toFile());

    // Delete archive after extraction
    if (archiveFile.exists()) {
      archiveFile.delete();
    }

    // Verify binary was extracted
    if (!binaryFile.exists()) {
      throw new IOException("Binary not found after extraction: " + binaryName);
    }

    return binaryFile;
  }

  private static String getBinaryNameFromArchive(String archiveName) {
    // Extract binary name based on OS
    if (isWindows()) {
      return "pico_limbo.exe";
    } else {
      return "pico_limbo";
    }
  }

  private static void downloadArchive(String archiveName, File targetFile) throws IOException {
    // Check if custom download URL is provided
    if (config.hasCustomDownloadUrl()) {
      String customUrl = config.getDownloadUrl();
      Logger.info("Using custom download URL");
      downloadFromUrl(customUrl, targetFile);
      return;
    }

    // Get latest release info from GitHub API
    String apiUrl = "https://api.github.com/repos/" + config.getGitHubRepo() + "/releases/latest";
    HttpURLConnection connection = (HttpURLConnection) new URL(apiUrl).openConnection();
    connection.setRequestMethod("GET");
    connection.setRequestProperty("User-Agent", "PicoLimboWrapper");
    connection.setConnectTimeout(10000);
    connection.setReadTimeout(10000);

    int responseCode = connection.getResponseCode();
    if (responseCode != 200) {
      throw new IOException("Failed to fetch release info from GitHub API. Response code: " + responseCode);
    }

    // Parse JSON response
    String jsonResponse;
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
      StringBuilder response = new StringBuilder();
      String line;
      while ((line = reader.readLine()) != null) {
        response.append(line);
      }
      jsonResponse = response.toString();
    }

    // Find the download URL for our archive
    String downloadUrl = extractDownloadUrl(jsonResponse, archiveName);
    if (downloadUrl == null) {
      throw new IOException("Could not find archive '" + archiveName + "' in latest release");
    }

    // Download the archive
    Logger.info("Downloading " + archiveName + "...");
    HttpURLConnection downloadConnection = (HttpURLConnection) new URL(downloadUrl).openConnection();
    downloadConnection.setRequestProperty("User-Agent", "PicoLimboWrapper");
    downloadConnection.setConnectTimeout(10000);
    downloadConnection.setReadTimeout(30000);

    long fileSize = downloadConnection.getContentLengthLong();
    long downloadedSize = 0;
    int lastProgress = 0;

    try (InputStream in = new BufferedInputStream(downloadConnection.getInputStream());
        FileOutputStream out = new FileOutputStream(targetFile)) {

      byte[] buffer = new byte[8192];
      int bytesRead;
      while ((bytesRead = in.read(buffer)) != -1) {
        out.write(buffer, 0, bytesRead);
        downloadedSize += bytesRead;

        // Show progress every 10%
        if (fileSize > 0) {
          int progress = (int) ((downloadedSize * 100) / fileSize);
          if (progress >= lastProgress + 10) {
            Logger.info("Download progress: " + progress + "%");
            lastProgress = progress;
          }
        }
      }
    }

    Logger.info("Download complete");
  }

  private static void downloadFromUrl(String downloadUrl, File targetFile) throws IOException {
    Logger.info("Downloading from " + downloadUrl + "...");
    HttpURLConnection downloadConnection = (HttpURLConnection) new URL(downloadUrl).openConnection();
    downloadConnection.setRequestProperty("User-Agent", "PicoLimboWrapper");
    downloadConnection.setConnectTimeout(10000);
    downloadConnection.setReadTimeout(30000);

    long fileSize = downloadConnection.getContentLengthLong();
    long downloadedSize = 0;
    int lastProgress = 0;

    try (
        InputStream in = new BufferedInputStream(downloadConnection.getInputStream());
        FileOutputStream out = new FileOutputStream(targetFile)) {
      byte[] buffer = new byte[8192];
      int bytesRead;
      while ((bytesRead = in.read(buffer)) != -1) {
        out.write(buffer, 0, bytesRead);
        downloadedSize += bytesRead;

        // Show progress every 10%
        if (fileSize > 0) {
          int progress = (int) ((downloadedSize * 100) / fileSize);
          if (progress >= lastProgress + 10) {
            Logger.info("Download progress: " + progress + "%");
            lastProgress = progress;
          }
        }
      }
    }

    Logger.info("Download complete");
  }

  private static void extractArchive(File archiveFile, File destinationDir) throws IOException {
    String fileName = archiveFile.getName().toLowerCase();

    if (fileName.endsWith(".zip")) {
      extractZip(archiveFile, destinationDir);
    } else if (fileName.endsWith(".tar.gz") || fileName.endsWith(".tgz")) {
      extractTarGz(archiveFile, destinationDir);
    } else {
      throw new IOException("Unsupported archive format: " + fileName);
    }

    Logger.info("Extraction complete");
  }

  private static void extractZip(File zipFile, File destinationDir) throws IOException {
    Logger.info("Extracting ZIP archive...");

    try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
      ZipEntry entry;
      while ((entry = zis.getNextEntry()) != null) {
        File outputFile = new File(destinationDir, entry.getName());

        if (entry.isDirectory()) {
          outputFile.mkdirs();
        } else {
          // Ensure parent directories exist
          outputFile.getParentFile().mkdirs();

          // Extract file
          try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = zis.read(buffer)) > 0) {
              fos.write(buffer, 0, len);
            }
          }
        }
        zis.closeEntry();
      }
    }
  }

  private static void extractTarGz(File tarGzFile, File destinationDir) throws IOException {
    Logger.info("Extracting TAR.GZ archive...");

    try (GZIPInputStream gzis = new GZIPInputStream(new FileInputStream(tarGzFile))) {
      extractTar(gzis, destinationDir);
    }
  }

  private static void extractTar(InputStream tarInputStream, File destinationDir) throws IOException {
    byte[] buffer = new byte[8192];

    while (true) {
      // Read TAR header (512 bytes)
      byte[] header = new byte[512];
      int bytesRead = readFully(tarInputStream, header);
      if (bytesRead < 512) {
        break; // End of archive
      }

      // Check if we've reached the end (two consecutive zero blocks)
      if (isZeroBlock(header)) {
        break;
      }

      // Parse TAR header
      String fileName = parseTarFileName(header);
      long fileSize = parseTarFileSize(header);
      char typeFlag = (char) header[156];

      if (fileName.isEmpty()) {
        break;
      }

      File outputFile = new File(destinationDir, fileName);

      // Handle directories (type '5') and regular files (type '0' or '\0')
      if (typeFlag == '5' || fileName.endsWith("/")) {
        outputFile.mkdirs();
      } else if (typeFlag == '0' || typeFlag == '\0') {
        // Ensure parent directories exist
        outputFile.getParentFile().mkdirs();

        // Extract file
        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
          long remaining = fileSize;
          while (remaining > 0) {
            int toRead = (int) Math.min(buffer.length, remaining);
            int read = tarInputStream.read(buffer, 0, toRead);
            if (read <= 0) {
              break;
            }
            fos.write(buffer, 0, read);
            remaining -= read;
          }
        }

        // Skip padding to next 512-byte boundary
        long padding = (512 - (fileSize % 512)) % 512;
        if (padding > 0) {
          tarInputStream.skip(padding);
        }
      } else {
        // Skip this entry
        long totalSize = fileSize + ((512 - (fileSize % 512)) % 512);
        tarInputStream.skip(totalSize);
      }
    }
  }

  private static int readFully(InputStream in, byte[] buffer) throws IOException {
    int offset = 0;
    int remaining = buffer.length;

    while (remaining > 0) {
      int read = in.read(buffer, offset, remaining);
      if (read <= 0) {
        return offset;
      }
      offset += read;
      remaining -= read;
    }

    return offset;
  }

  private static boolean isZeroBlock(byte[] block) {
    for (byte b : block) {
      if (b != 0) {
        return false;
      }
    }
    return true;
  }

  private static String parseTarFileName(byte[] header) {
    // File name is at offset 0, max 100 bytes
    StringBuilder name = new StringBuilder();
    for (int i = 0; i < 100; i++) {
      if (header[i] == 0) {
        break;
      }
      name.append((char) header[i]);
    }
    return name.toString().trim();
  }

  private static long parseTarFileSize(byte[] header) {
    // File size is at offset 124, 12 bytes, in octal ASCII
    String sizeStr = new String(header, 124, 12).trim();
    if (sizeStr.isEmpty()) {
      return 0;
    }
    try {
      // Remove any trailing spaces or null characters
      sizeStr = sizeStr.replace("\0", "").trim();
      return Long.parseLong(sizeStr, 8); // Parse as octal
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private static String extractDownloadUrl(String jsonResponse, String archiveName) {
    try {
      JsonObject jsonObject = JsonParser.parseString(jsonResponse).getAsJsonObject();
      JsonArray assets = jsonObject.getAsJsonArray("assets");

      for (int i = 0; i < assets.size(); i++) {
        JsonObject asset = assets.get(i).getAsJsonObject();
        String name = asset.get("name").getAsString();
        if (name.equals(archiveName)) {
          return asset.get("browser_download_url").getAsString();
        }
      }
    } catch (Exception e) {
      Logger.error("Error parsing GitHub API response: " + e.getMessage());
    }
    return null;
  }

  private static void registerShutdownHook() {
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      Logger.info("Shutting down all instances...");

      for (InstanceInfo instance : instances.values()) {
        if (instance.isRunning()) {
          Process process = instance.getProcess();
          Logger.info("Stopping instance: " + instance.getName());
          process.destroy();

          try {
            // Wait up to 5 seconds for graceful shutdown
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
              Logger.warn("Force killing instance: " + instance.getName());
              process.destroyForcibly();
            }
          } catch (InterruptedException e) {
            process.destroyForcibly();
          }
        }
      }

      Logger.info("Shutdown complete");
    }));
  }
}
