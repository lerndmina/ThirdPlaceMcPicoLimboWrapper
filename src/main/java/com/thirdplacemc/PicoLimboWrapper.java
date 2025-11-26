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
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class PicoLimboWrapper {
  private static final String GITHUB_REPO = "Quozul/PicoLimbo";
  private static final String BINARIES_DIR = "binaries";
  private static Process picoLimboProcess;

  public static void main(String[] args) {
    try {
      System.out.println("[Wrapper] Starting PicoLimbo Wrapper...");

      // Detect OS and architecture
      String binaryName = detectBinaryName();
      System.out.println("[Wrapper] Detected OS: " + getOSInfo());

      // Ensure binary exists (download if needed)
      File binaryFile = ensureBinaryExists(binaryName);

      // Set executable permissions on Unix systems
      if (!isWindows()) {
        binaryFile.setExecutable(true, false);
        System.out.println("[Wrapper] Set executable permissions");
      }

      // Register shutdown hook
      registerShutdownHook();

      // Launch PicoLimbo
      System.out.println("[Wrapper] Launching PicoLimbo...");
      ProcessBuilder processBuilder = new ProcessBuilder(binaryFile.getAbsolutePath());
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

      // Monitor console input for stop commands
      Thread inputThread = new Thread(() -> {
        try (BufferedReader consoleReader = new BufferedReader(new InputStreamReader(System.in))) {
          String line;
          while ((line = consoleReader.readLine()) != null) {
            String command = line.trim().toLowerCase();

            // Check for stop/exit/quit commands
            if (command.equals("stop") || command.equals("exit") || command.equals("quit") || command.equals("end")) {
              System.out.println("[Wrapper] Received stop command, shutting down...");
              if (picoLimboProcess != null && picoLimboProcess.isAlive()) {
                picoLimboProcess.destroy(); // Sends SIGTERM on Unix, terminates on Windows
                try {
                  if (!picoLimboProcess.waitFor(5, TimeUnit.SECONDS)) {
                    picoLimboProcess.destroyForcibly(); // Force kill if needed
                  }
                } catch (InterruptedException e) {
                  picoLimboProcess.destroyForcibly();
                }
              }
              System.exit(0);
            }
            // PicoLimbo doesn't read stdin, so we just ignore other input
          }
        } catch (IOException e) {
          // Console closed, this is normal
        }
      });
      inputThread.setDaemon(true);
      inputThread.start();

      // Wait for process to complete
      int exitCode = picoLimboProcess.waitFor();
      System.exit(exitCode);

    } catch (Exception e) {
      System.err.println("[Wrapper] Error: " + e.getMessage());
      e.printStackTrace();
      System.exit(1);
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
      System.out.println("[Wrapper] Binary found: " + binaryName);
      return binaryFile;
    }

    // Download and extract archive from GitHub releases
    System.out.println("[Wrapper] Binary not found, fetching latest release...");
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
    // Get latest release info from GitHub API
    String apiUrl = "https://api.github.com/repos/" + GITHUB_REPO + "/releases/latest";
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
    System.out.println("[Wrapper] Downloading " + archiveName + "...");
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
            System.out.println("[Wrapper] Download progress: " + progress + "%");
            lastProgress = progress;
          }
        }
      }
    }

    System.out.println("[Wrapper] Download complete");
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

    System.out.println("[Wrapper] Extraction complete");
  }

  private static void extractZip(File zipFile, File destinationDir) throws IOException {
    System.out.println("[Wrapper] Extracting ZIP archive...");

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
    System.out.println("[Wrapper] Extracting TAR.GZ archive...");

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
      System.err.println("[Wrapper] Error parsing GitHub API response: " + e.getMessage());
    }
    return null;
  }

  private static void registerShutdownHook() {
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      if (picoLimboProcess != null && picoLimboProcess.isAlive()) {
        System.out.println("[Wrapper] Shutting down PicoLimbo...");
        picoLimboProcess.destroy();

        try {
          // Wait up to 5 seconds for graceful shutdown
          if (!picoLimboProcess.waitFor(5, TimeUnit.SECONDS)) {
            System.out.println("[Wrapper] Force killing PicoLimbo...");
            picoLimboProcess.destroyForcibly();
          }
        } catch (InterruptedException e) {
          picoLimboProcess.destroyForcibly();
        }

        System.out.println("[Wrapper] Shutdown complete");
      }
    }));
  }
}
