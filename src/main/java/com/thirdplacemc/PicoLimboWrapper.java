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
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class PicoLimboWrapper {
  private static final String BINARIES_DIR = "binaries";
  private static Process picoLimboProcess;
  private static WrapperConfig config;
  private static String currentArchiveName;
  private static File currentBinaryFile;
  private static volatile boolean shouldRestart = false;
  private static volatile boolean isUpdating = false;
  private static final Object updateLock = new Object();

  public static void main(String[] args) {
    try {
      System.out.println("[TPMC Limbo] Starting PicoLimbo Wrapper...");

      // Load configuration
      config = new WrapperConfig();

      // Detect OS and architecture
      currentArchiveName = detectBinaryName();
      System.out.println("[TPMC Limbo] Detected OS: " + getOSInfo());

      // Ensure binary exists (download if needed)
      currentBinaryFile = ensureBinaryExists(currentArchiveName);

      // Set executable permissions on Unix systems
      if (!isWindows()) {
        currentBinaryFile.setExecutable(true, false);
        System.out.println("[TPMC Limbo] Set executable permissions");
      }

      // Register shutdown hook
      registerShutdownHook();

      // Launch PicoLimbo in a restart loop
      while (true) {
        shouldRestart = false;
        try {
          int exitCode = launchPicoLimbo();

          if (!shouldRestart) {
            System.out.println("[TPMC Limbo] PicoLimbo exited with code " + exitCode);
            System.exit(exitCode);
          }

          System.out.println("[TPMC Limbo] Restarting PicoLimbo...");
        } catch (Exception e) {
          if (shouldRestart) {
            System.err.println("[TPMC Limbo] Error during restart: " + e.getMessage());
            System.out.println("[TPMC Limbo] Attempting to restart...");
          } else {
            throw e;
          }
        }
      }

    } catch (Exception e) {
      System.err.println("[TPMC Limbo] Error: " + e.getMessage());
      e.printStackTrace();
      System.exit(1);
    }
  }

  private static int launchPicoLimbo() throws IOException, InterruptedException {
    System.out.println("[TPMC Limbo] Launching PicoLimbo...");
    ProcessBuilder processBuilder = new ProcessBuilder(currentBinaryFile.getAbsolutePath());
    processBuilder.directory(new File(System.getProperty("user.dir")));
    processBuilder.redirectErrorStream(true);

    picoLimboProcess = processBuilder.start();

    // Forward output from PicoLimbo to console
    Thread outputThread = new Thread(() -> {
      try (BufferedReader reader = new BufferedReader(
          new InputStreamReader(picoLimboProcess.getInputStream()))) {
        String line;
        while ((line = reader.readLine()) != null) {
          System.out.println(line);
        }
      } catch (IOException e) {
        // Process ended, this is normal
      }
    });
    outputThread.setDaemon(false);
    outputThread.start();

    // Monitor console input for stop and update commands
    Thread inputThread = new Thread(() -> {
      try (BufferedReader consoleReader = new BufferedReader(new InputStreamReader(System.in))) {
        String line;
        while ((line = consoleReader.readLine()) != null) {
          String command = line.trim().toLowerCase();

          // Check for stop/exit/quit commands
          if (command.equals("stop") || command.equals("exit") || command.equals("quit") || command.equals("end")) {
            System.out.println("[TPMC Limbo] Received stop command, shutting down...");
            if (picoLimboProcess != null && picoLimboProcess.isAlive()) {
              picoLimboProcess.destroy();
              try {
                if (!picoLimboProcess.waitFor(5, TimeUnit.SECONDS)) {
                  picoLimboProcess.destroyForcibly();
                }
              } catch (InterruptedException e) {
                picoLimboProcess.destroyForcibly();
              }
            }
            System.exit(0);
          }
          // Check for update command
          else if (command.equals("update") || command.equals("reload")) {
            System.out.println("[TPMC Limbo] Received update command, downloading latest version...");
            handleUpdate();
          }
        }
      } catch (IOException e) {
        // Console closed, this is normal
      }
    });
    inputThread.setDaemon(true);
    inputThread.start();

    // Wait for process to complete
    int exitCode = picoLimboProcess.waitFor();

    // If an update is in progress, wait for it to complete
    synchronized (updateLock) {
      while (isUpdating) {
        try {
          updateLock.wait();
        } catch (InterruptedException e) {
          // Interrupted, continue
        }
      }
    }

    return exitCode;
  }

  private static void handleUpdate() {
    synchronized (updateLock) {
      isUpdating = true;
    }

    File backupBinary = null;
    try {
      // Download new version (while PicoLimbo is still running)
      File archiveFile = new File(BINARIES_DIR, currentArchiveName);

      System.out.println("[TPMC Limbo] Downloading latest version...");
      downloadArchive(currentArchiveName, archiveFile);

      // Extract to temporary directory
      System.out.println("[TPMC Limbo] Extracting new version...");
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

      System.out.println("[TPMC Limbo] Stopping current server...");

      // Stop current process
      if (picoLimboProcess != null && picoLimboProcess.isAlive()) {
        picoLimboProcess.destroy();
        if (!picoLimboProcess.waitFor(5, TimeUnit.SECONDS)) {
          picoLimboProcess.destroyForcibly();
        }
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
      System.out.println("[TPMC Limbo] Installing new binary...");
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
      System.out.println("[TPMC Limbo] Verifying new binary...");
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

      System.out.println("[TPMC Limbo] Verification successful! Update complete, restarting PicoLimbo...");

      // Delete backup since update was successful
      if (backupBinary != null && backupBinary.exists()) {
        backupBinary.delete();
      }

      // NOW signal restart after everything is done
      shouldRestart = true;

    } catch (Exception e) {
      System.err.println("[TPMC Limbo] Update failed: " + e.getMessage());
      e.printStackTrace();

      // Clean up temporary files
      File tempDir = new File(BINARIES_DIR, "update_temp");
      if (tempDir.exists()) {
        deleteDirectory(tempDir);
      }

      // Restore backup if it exists
      if (backupBinary != null && backupBinary.exists()) {
        System.out.println("[TPMC Limbo] Restoring previous version...");
        if (currentBinaryFile.exists()) {
          currentBinaryFile.delete();
        }
        backupBinary.renameTo(currentBinaryFile);

        // Set executable permissions on Unix
        if (!isWindows()) {
          currentBinaryFile.setExecutable(true, false);
        }
      }

      // Signal restart with the old binary
      shouldRestart = true;
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
      System.out.println("[TPMC Limbo] Binary found: " + binaryName);
      return binaryFile;
    }

    // Download and extract archive from GitHub releases
    System.out.println("[TPMC Limbo] Binary not found, fetching latest release...");
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
      System.out.println("[TPMC Limbo] Using custom download URL");
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
    System.out.println("[TPMC Limbo] Downloading " + archiveName + "...");
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
            System.out.println("[TPMC Limbo] Download progress: " + progress + "%");
            lastProgress = progress;
          }
        }
      }
    }

    System.out.println("[TPMC Limbo] Download complete");
  }

  private static void downloadFromUrl(String downloadUrl, File targetFile) throws IOException {
    System.out.println("[TPMC Limbo] Downloading from " + downloadUrl + "...");
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
            System.out.println("[TPMC Limbo] Download progress: " + progress + "%");
            lastProgress = progress;
          }
        }
      }
    }

    System.out.println("[TPMC Limbo] Download complete");
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

    System.out.println("[TPMC Limbo] Extraction complete");
  }

  private static void extractZip(File zipFile, File destinationDir) throws IOException {
    System.out.println("[TPMC Limbo] Extracting ZIP archive...");

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
    System.out.println("[TPMC Limbo] Extracting TAR.GZ archive...");

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
      System.err.println("[TPMC Limbo] Error parsing GitHub API response: " + e.getMessage());
    }
    return null;
  }

  private static void registerShutdownHook() {
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      if (picoLimboProcess != null && picoLimboProcess.isAlive()) {
        System.out.println("[TPMC Limbo] Shutting down PicoLimbo...");
        picoLimboProcess.destroy();

        try {
          // Wait up to 5 seconds for graceful shutdown
          if (!picoLimboProcess.waitFor(5, TimeUnit.SECONDS)) {
            System.out.println("[TPMC Limbo] Force killing PicoLimbo...");
            picoLimboProcess.destroyForcibly();
          }
        } catch (InterruptedException e) {
          picoLimboProcess.destroyForcibly();
        }

        System.out.println("[TPMC Limbo] Shutdown complete");
      }
    }));
  }
}
