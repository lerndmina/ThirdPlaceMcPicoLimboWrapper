# PicoLimbo Wrapper

A Java wrapper application that automatically downloads and launches the latest [PicoLimbo](https://github.com/Quozul/PicoLimbo) Minecraft limbo server binary with support for running multiple server instances simultaneously.

## Features

- 🚀 **Automatic Binary Download & Extraction**: Downloads and extracts the latest PicoLimbo release from GitHub on first run
- 🖥️ **Cross-Platform**: Supports Windows (x64), Linux (x64, ARM64), macOS (ARM64)
- 🔄 **Multi-Instance Support**: Run multiple PicoLimbo instances with different configurations
- 🎮 **Instance Management**: Start, stop, and restart individual instances via console commands
- 🔄 **Transparent I/O**: Bidirectional console forwarding with per-instance prefixes
- 🔄 **Hot Reload**: Update PicoLimbo binary without restarting the wrapper
- 🛡️ **Graceful Shutdown**: Properly handles shutdown signals and forwards to child processes
- 📦 **Single JAR**: Self-contained executable with all dependencies included
- 💾 **Smart Caching**: Downloaded binaries are cached in `binaries/` directory
- 🔁 **Auto-Restart**: Configurable automatic restart on crash (up to 5 times)

## Requirements

- Java 11 or higher
- Internet connection (for first-time binary download)
- Gradle (for building from source)

## Quick Start

### Download Pre-built JAR

1. Download `PicoLimboWrapper.jar` from the releases
2. Place your `server.toml` configuration file in the same directory
3. Run: `java -jar PicoLimboWrapper.jar`

On first run, the wrapper will automatically download the appropriate PicoLimbo binary for your system.

### Build from Source

```bash
# Clone the repository
git clone https://github.com/YourUsername/PicoLimboWrapper.git
cd PicoLimboWrapper

# Build the JAR
./gradlew shadowJar

# Run the wrapper
java -jar build/libs/PicoLimboWrapper.jar
```

On Windows:

```powershell
.\gradlew.bat shadowJar
java -jar build\libs\PicoLimboWrapper.jar
```

## How It Works

1. **OS Detection**: Detects your operating system and architecture
2. **Binary Check**: Looks for the PicoLimbo binary in the `binaries/` directory
3. **Auto-Download**: If not found, downloads the latest release archive from GitHub
4. **Auto-Extract**: Extracts the binary from the downloaded archive (`.zip` or `.tar.gz`)
5. **Permission Setup**: Sets executable permissions on Unix systems
6. **Process Launch**: Starts PicoLimbo as a child process
7. **I/O Forwarding**: Forwards all console input/output bidirectionally
8. **Shutdown Handling**: Gracefully terminates PicoLimbo on exit

## Example Output

### First Run (with download):

```
[2024-01-15T10:00:00.000000Z] [INFO] Starting PicoLimbo Wrapper...
[2024-01-15T10:00:00.100000Z] [INFO] Detected OS: Linux x86_64
[2024-01-15T10:00:00.200000Z] [INFO] Binary not found, fetching latest release...
[2024-01-15T10:00:01.000000Z] [INFO] Downloading pico_limbo-x86_64-unknown-linux-gnu.tar.gz...
[2024-01-15T10:00:02.000000Z] [INFO] Download progress: 10%
[2024-01-15T10:00:03.000000Z] [INFO] Download progress: 20%
...
[2024-01-15T10:00:10.000000Z] [INFO] Download complete
[2024-01-15T10:00:10.500000Z] [INFO] Extracting TAR.GZ archive...
[2024-01-15T10:00:11.000000Z] [INFO] Extraction complete
[2024-01-15T10:00:11.100000Z] [INFO] Set executable permissions
[2024-01-15T10:00:11.200000Z] [INFO] Configured instances: lobby, survival
[2024-01-15T10:00:11.300000Z] [INFO] Starting instance: lobby
[2024-01-15T10:00:12.000000Z] [INFO] Starting instance: survival
[lobby] [PicoLimbo] Listening on: 0.0.0.0:25565
[survival] [PicoLimbo] Listening on: 0.0.0.0:25566
```

### Subsequent Runs (cached binary, multi-instance):

```
[2024-01-15T10:30:00.000000Z] [INFO] Starting PicoLimbo Wrapper...
[2024-01-15T10:30:00.100000Z] [INFO] Detected OS: Linux x86_64
[2024-01-15T10:30:00.200000Z] [INFO] Binary found: pico_limbo
[2024-01-15T10:30:00.300000Z] [INFO] Set executable permissions
[2024-01-15T10:30:00.400000Z] [INFO] Configured instances: lobby, survival
[2024-01-15T10:30:00.500000Z] [INFO] Starting instance: lobby
[2024-01-15T10:30:01.200000Z] [INFO] Starting instance: survival
[lobby] [PicoLimbo] Listening on: 0.0.0.0:25565
[survival] [PicoLimbo] Listening on: 0.0.0.0:25566
```

### Hot Reload (update command):

```
> update
[2024-01-15T11:00:00.000000Z] [INFO] Received update command, downloading latest version...
[2024-01-15T11:00:00.500000Z] [INFO] Downloading latest version...
[2024-01-15T11:00:05.000000Z] [INFO] Download complete
[2024-01-15T11:00:05.500000Z] [INFO] Extracting new version...
[2024-01-15T11:00:06.000000Z] [INFO] Extraction complete
[2024-01-15T11:00:06.100000Z] [INFO] Stopping all running instances...
[2024-01-15T11:00:06.200000Z] [INFO] Stopping instance: lobby
[2024-01-15T11:00:06.300000Z] [INFO] Stopping instance: survival
[2024-01-15T11:00:07.000000Z] [INFO] Installing new binary...
[2024-01-15T11:00:07.500000Z] [INFO] Verifying new binary...
[2024-01-15T11:00:10.000000Z] [INFO] Verification successful! Update complete, restarting instances...
[2024-01-15T11:00:10.100000Z] [INFO] Restarting instance: lobby
[2024-01-15T11:00:10.800000Z] [INFO] Restarting instance: survival
[lobby] [PicoLimbo] Listening on: 0.0.0.0:25565
[survival] [PicoLimbo] Listening on: 0.0.0.0:25566
```

## Pterodactyl Panel Usage

This wrapper is designed to work seamlessly with Pterodactyl game server panels:

1. Upload `PicoLimboWrapper.jar` to your server directory
2. Set **SERVER JAR FILE** to: `PicoLimboWrapper.jar`
3. Upload your `server.toml` configuration file
4. Start the server

The wrapper will automatically download PicoLimbo on first startup. The downloaded binary is cached, so subsequent restarts are instant.

## Configuration

The wrapper supports both single-instance (legacy) and multi-instance modes through `wrapper.properties`.

### wrapper.properties

```properties
# GitHub repository to fetch releases from
github.repo=Quozul/PicoLimbo

# Direct download URL (overrides GitHub if set)
download.url=

# Multi-instance configuration (optional)
# Format: instances=instance1,instance2,instance3
instances=lobby,survival,creative

# Per-instance configuration paths
instance.lobby.config=configs/lobby.toml
instance.lobby.autoStart=true

instance.survival.config=configs/survival.toml
instance.survival.autoStart=true

instance.creative.config=configs/creative.toml
instance.creative.autoStart=false
```

**Configuration Options:**

- **`github.repo`**: Specify a different GitHub repository (useful for forks)

  - Format: `owner/repo`
  - Default: `Quozul/PicoLimbo`
  - Example: `github.repo=YourUsername/CustomPicoLimbo`

- **`download.url`**: Provide a direct download URL for the PicoLimbo archive

  - If set, bypasses GitHub API and downloads from this URL
  - Useful for custom builds, avoiding rate limits, or pinning to a specific version
  - Leave empty to use GitHub releases (default)
  - Examples:

    ```properties
    # Specific GitHub release version
    download.url=https://github.com/Quozul/PicoLimbo/releases/download/v1.21.7/pico_limbo_windows-x86_64.zip

    # Custom build server
    download.url=https://example.com/builds/pico_limbo.zip
    ```

- **`instances`**: Comma-separated list of instance names (multi-instance mode)

  - If omitted or empty, runs in single-instance legacy mode with `server.toml`
  - Example: `instances=lobby,survival,creative`

- **`instance.<name>.config`**: Path to the TOML config file for this instance

  - Required for each instance defined in `instances`
  - Relative to wrapper directory
  - Example: `instance.lobby.config=configs/lobby.toml`

- **`instance.<name>.autoStart`**: Whether to start this instance on wrapper startup

  - Default: `true`
  - Example: `instance.creative.autoStart=false`

### Single Instance Mode (Legacy)

If the `instances` property is not defined or empty, the wrapper runs in legacy single-instance mode:

```properties
github.repo=Quozul/PicoLimbo
download.url=
```

The wrapper will use `server.toml` in the current directory as the configuration file.

### Multi-Instance Mode

Define multiple instances with different configurations:

```properties
instances=lobby,survival

instance.lobby.config=configs/lobby.toml
instance.lobby.autoStart=true

instance.survival.config=configs/survival.toml
instance.survival.autoStart=true
```

Each instance configuration file should specify a unique port to avoid conflicts:

**configs/lobby.toml:**

```toml
bind = "0.0.0.0:25565"
motd = "Lobby Server"
```

**configs/survival.toml:**

```toml
bind = "0.0.0.0:25566"
motd = "Survival Server"
```

All PicoLimbo settings are managed through the TOML configuration files. For PicoLimbo configuration options, see the [PicoLimbo documentation](https://github.com/Quozul/PicoLimbo).

## Console Commands

The wrapper provides an interactive console for managing instances:

### Single Instance Mode

- `stop` / `exit` / `quit` / `end` - Stop the server and exit wrapper
- `update` / `reload` - Download latest PicoLimbo version and restart
- `help` - Show available commands

### Multi-Instance Mode

- `stop` - Stop all instances and exit wrapper
- `stop <instance>` - Stop a specific instance
- `start <instance>` - Start a specific instance
- `restart <instance>` - Restart a specific instance
- `status` - Show status of all instances
- `update` / `reload` - Update PicoLimbo binary and restart all running instances
- `help` - Show available commands
- `exit` / `quit` / `end` - Stop all instances and exit wrapper

**Examples:**

```
> status
  lobby: RUNNING - configs/lobby.toml
  survival: RUNNING - configs/survival.toml (restarts: 1)
  creative: STOPPED - configs/creative.toml

> stop survival
[2024-01-15T10:30:45.123456Z] [INFO] Stopping instance: survival

> start creative
[2024-01-15T10:31:00.789012Z] [INFO] Starting instance: creative

> restart lobby
[2024-01-15T10:31:15.234567Z] [INFO] Restarting instance: lobby
```

### Signal Handling

- **SIGTERM/SIGINT**: The wrapper catches these signals and forwards them to all running instances
- **Ctrl+C**: Triggers graceful shutdown on all platforms
- **Pterodactyl**: Use the panel's stop button (sends SIGTERM)

The wrapper will wait up to 5 seconds for each instance to shutdown gracefully before forcefully terminating.

## Supported Platforms

**Note:** The PicoLimbo releases provide archives (`.zip`, `.tar.gz`) rather than raw binaries. The wrapper automatically downloads and extracts these archives.

| Platform | Architecture | Archive                                |
| -------- | ------------ | -------------------------------------- |
| Windows  | x64          | `pico_limbo_windows-x86_64.zip`        |
| Linux    | x64          | `pico_limbo_linux-x86_64-musl.tar.gz`  |
| Linux    | ARM64        | `pico_limbo_linux-aarch64-musl.tar.gz` |
| macOS    | ARM64        | `pico_limbo_macos-aarch64.tar.gz`      |

## Project Structure

```
PicoLimboWrapper/
├── build.gradle              # Gradle build configuration
├── settings.gradle           # Gradle settings
├── src/
│   └── main/
│       └── java/
│           └── com/
│               └── thirdplacemc/
│                   └── PicoLimboWrapper.java
├── binaries/                 # Auto-created directory for cached binaries
│   └── pico_limbo-*         # Downloaded PicoLimbo binaries (gitignored)
└── README.md
```

## Dependencies

- **Gson 2.10.1**: JSON parsing for GitHub API responses
- **Shadow Plugin 8.1.1**: Creates fat JAR with all dependencies

## Troubleshooting

### "Unsupported operating system" error

The wrapper currently supports Windows (x64), Linux (x64), and Linux (ARM64). If you need support for additional platforms, please check if PicoLimbo provides binaries for your system.

### Download fails

- Check your internet connection
- Verify you can access `https://api.github.com`
- GitHub API has rate limits (60 requests/hour for unauthenticated requests)
- Try again in a few minutes if you've hit the rate limit

### Binary doesn't execute

- On Linux: Ensure the wrapper has permission to set executable bits
- Check that you have the correct architecture binary
- Verify the downloaded binary isn't corrupted (delete `binaries/` and retry)

### Console input not working

The wrapper forwards stdin to PicoLimbo. If commands aren't working, verify that PicoLimbo itself supports console input in its current version.

## Building

### Requirements

- JDK 11 or higher
- Gradle 7.0+ (or use included wrapper)

### Build Commands

```bash
# Clean build
./gradlew clean shadowJar

# Run tests (if added)
./gradlew test

# Build without tests
./gradlew shadowJar
```

The compiled JAR will be located at: `build/libs/PicoLimboWrapper.jar`

## License

This wrapper is provided as-is for use with PicoLimbo. PicoLimbo itself is subject to its own license terms.

## Contributing

Contributions are welcome! Please feel free to submit issues or pull requests.

## Credits

- [PicoLimbo](https://github.com/Quozul/PicoLimbo) - The Minecraft limbo server this wrapper manages
- Built for [ThirdPlaceMC](https://discord.thirdplacemc.net)

## Version History

### 1.0.0

- Initial release
- Automatic binary download from GitHub releases
- Cross-platform support (Windows, Linux x64/ARM64)
- Bidirectional console I/O forwarding
- Graceful shutdown handling
- Download progress indicators
