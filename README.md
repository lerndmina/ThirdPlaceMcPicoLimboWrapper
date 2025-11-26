# PicoLimbo Wrapper

A Java wrapper application that automatically downloads and launches the latest [PicoLimbo](https://github.com/Quozul/PicoLimbo) Minecraft limbo server binary.

## Features

- 🚀 **Automatic Binary Download**: Downloads the latest PicoLimbo release from GitHub on first run
- 🖥️ **Cross-Platform**: Supports Windows (x64), Linux (x64, ARM64)
- 🔄 **Transparent I/O**: Bidirectional console forwarding (stdin/stdout/stderr)
- 🛡️ **Graceful Shutdown**: Properly handles SIGTERM and forwards to child process
- 📦 **Single JAR**: Self-contained executable with all dependencies included
- 💾 **Smart Caching**: Downloaded binaries are cached in `binaries/` directory

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
3. **Auto-Download**: If not found, downloads the latest release from GitHub
4. **Permission Setup**: Sets executable permissions on Unix systems
5. **Process Launch**: Starts PicoLimbo as a child process
6. **I/O Forwarding**: Forwards all console input/output bidirectionally
7. **Shutdown Handling**: Gracefully terminates PicoLimbo on exit

## Example Output

### First Run (with download):

```
[Wrapper] Starting PicoLimbo Wrapper...
[Wrapper] Detected OS: Linux x86_64
[Wrapper] Binary not found, fetching latest release...
[Wrapper] Downloading pico_limbo-x86_64-unknown-linux-gnu...
[Wrapper] Download progress: 10%
[Wrapper] Download progress: 20%
...
[Wrapper] Download complete
[Wrapper] Set executable permissions
[Wrapper] Launching PicoLimbo...
[PicoLimbo output follows...]
```

### Subsequent Runs (cached binary):

```
[Wrapper] Starting PicoLimbo Wrapper...
[Wrapper] Detected OS: Linux x86_64
[Wrapper] Binary found: pico_limbo-x86_64-unknown-linux-gnu
[Wrapper] Set executable permissions
[Wrapper] Launching PicoLimbo...
[PicoLimbo output follows...]
```

## Pterodactyl Panel Usage

This wrapper is designed to work seamlessly with Pterodactyl game server panels:

1. Upload `PicoLimboWrapper.jar` to your server directory
2. Set **SERVER JAR FILE** to: `PicoLimboWrapper.jar`
3. Upload your `server.toml` configuration file
4. Start the server

The wrapper will automatically download PicoLimbo on first startup. The downloaded binary is cached, so subsequent restarts are instant.

## Configuration

The wrapper itself requires no configuration. All PicoLimbo settings are managed through the standard `server.toml` file, which should be placed in the same directory as the wrapper JAR.

For PicoLimbo configuration options, see the [PicoLimbo documentation](https://github.com/Quozul/PicoLimbo).

## Supported Platforms

**Note:** The current PicoLimbo releases provide archives (`.zip`, `.tar.gz`) rather than raw binaries. The wrapper is configured to download these archives. Future versions will include automatic extraction.

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
- Built for [ThirdPlaceMC](https://github.com/YourOrg)

## Version History

### 1.0.0

- Initial release
- Automatic binary download from GitHub releases
- Cross-platform support (Windows, Linux x64/ARM64)
- Bidirectional console I/O forwarding
- Graceful shutdown handling
- Download progress indicators
