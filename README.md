# RokidStream

A real-time video streaming solution over Bluetooth Low Energy (BLE) L2CAP channel, designed for streaming camera feeds to Rokid AR glasses or similar devices.

## Overview

RokidStream consists of two Android applications:

- **Sender** - Captures camera video, encodes it as H.264, and streams over BLE
- **Receiver** - Receives the H.264 stream, decodes, and displays on screen

The project uses BLE GATT for connection setup and L2CAP channels for high-bandwidth video data transfer, providing a low-latency streaming solution without requiring Wi-Fi or traditional Bluetooth pairing.

## Architecture

```
┌─────────────────┐         BLE L2CAP          ┌─────────────────┐
│     Sender      │ ─────────────────────────► │    Receiver     │
│   (Phone/PC)    │                            │  (AR Glasses)   │
└─────────────────┘                            └─────────────────┘
        │                                              │
   ┌────┴────┐                                   ┌─────┴─────┐
   │ Camera  │                                   │ SurfaceView│
   │ CameraX │                                   │  Display   │
   └────┬────┘                                   └─────┬─────┘
        │                                              │
   ┌────┴────┐                                   ┌─────┴─────┐
   │ H.264   │                                   │  H.264    │
   │ Encoder │                                   │  Decoder  │
   └────┬────┘                                   └─────┬─────┘
        │                                              │
   ┌────┴────────────┐                          ┌──────┴──────────┐
   │ Frame Queue     │                          │ Frame Reader    │
   │ (10 frames max) │                          │ (length-prefix) │
   └────┬────────────┘                          └──────┬──────────┘
        │                                              │
   ┌────┴────────────────────────────────────────────┴────┐
   │                  BLE L2CAP Channel                    │
   │           (Reliable, Connection-Oriented)             │
   └───────────────────────────────────────────────────────┘
```

## Features

- **Real-time H.264 Video Streaming** - Hardware-accelerated encoding/decoding
- **BLE L2CAP Transport** - High-bandwidth, connection-oriented channel
- **GATT Service Discovery** - Automatic PSM negotiation for L2CAP
- **Frame Rate Control** - Configurable FPS (default: 10 FPS)
- **Adaptive Bitrate** - 300 Kbps optimized for BLE bandwidth
- **Frame Dropping** - Graceful degradation under network congestion
- **LZ4 Compression Support** - Optional compression library included
- **CameraX Integration** - Modern camera API with lifecycle awareness

## Requirements

### Hardware

- **Sender Device**: Android phone with Bluetooth 5.0+ and Camera
- **Receiver Device**: Android device with Bluetooth 5.0+ (e.g., Rokid glasses)

### Software

- Android SDK 29+ (Android 10)
- Target SDK 34 (Android 14)
- Kotlin 1.9.0
- Gradle 8.3.0

## Project Structure

```
RokidStream/
├── sender/                     # Sender application
│   ├── src/main/
│   │   ├── java/com/rokid/stream/sender/
│   │   │   └── MainActivity.kt    # Camera capture & BLE streaming
│   │   ├── res/layout/
│   │   │   └── activity_main.xml  # Camera preview & connect UI
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
│
├── receiver/                   # Receiver application
│   ├── src/main/
│   │   ├── java/com/rokid/stream/receiver/
│   │   │   └── MainActivity.kt    # BLE server & video decoder
│   │   ├── res/layout/
│   │   │   └── activity_main.xml  # Full-screen video display
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
│
├── gradle/
│   └── libs.versions.toml      # Version catalog
├── build.gradle.kts            # Root build file
└── settings.gradle.kts         # Project settings
```

## Installation

### Building from Source

1. Clone the repository:

   ```bash
   git clone https://github.com/zero2005x/RokidStream.git
   cd RokidStream
   ```

2. Open the project in Android Studio.

3. Build and install the Sender app on your phone:

   ```bash
   ./gradlew :sender:installDebug
   ```

4. Build and install the Receiver app on AR glasses:
   ```bash
   ./gradlew :receiver:installDebug
   ```

### Pre-built APKs

Download the latest APKs from the [Releases](https://github.com/zero2005x/RokidStream/releases) page.

## Usage

### On the Receiver Device (AR Glasses)

1. Launch **Rokid Receiver** app
2. The app will automatically:
   - Start a GATT server
   - Open an L2CAP server socket
   - Begin BLE advertising
3. Wait for a connection from the sender

### On the Sender Device (Phone)

1. Launch **Rokid Sender** app
2. Grant camera and Bluetooth permissions when prompted
3. Tap **"Connect & Stream Video"** button
4. The app will:
   - Scan for Rokid Receiver
   - Connect via GATT and read PSM
   - Establish L2CAP connection
   - Start streaming camera video

## Configuration

### Video Parameters (Sender)

| Parameter        | Value    | Location                      |
| ---------------- | -------- | ----------------------------- |
| Resolution       | 720x720  | `VIDEO_WIDTH`, `VIDEO_HEIGHT` |
| Bitrate          | 300 Kbps | `VIDEO_BITRATE`               |
| Frame Rate       | 10 FPS   | `VIDEO_FRAME_RATE`            |
| I-Frame Interval | 1 second | `VIDEO_I_FRAME_INTERVAL`      |
| Encoder Profile  | Baseline | `AVCProfileBaseline`          |

### BLE UUIDs

| UUID                                   | Purpose            |
| -------------------------------------- | ------------------ |
| `6e400001-b5a3-f393-e0a9-e50e24dcca9e` | Service UUID       |
| `6e400002-b5a3-f393-e0a9-e50e24dcca9e` | PSM Characteristic |

## Protocol

### Frame Format

Each video frame is transmitted with a simple length-prefix protocol:

```
┌─────────────────┬──────────────────────┐
│ Length (4 bytes)│ H.264 NAL Unit Data  │
│ Little-Endian   │ (variable length)    │
└─────────────────┴──────────────────────┘
```

### Connection Flow

1. **Receiver** starts GATT server and advertises service
2. **Sender** scans and discovers receiver by Service UUID
3. **Sender** connects GATT and reads PSM characteristic
4. **Sender** establishes L2CAP channel using PSM
5. **Sender** streams H.264 frames over L2CAP
6. **Receiver** decodes and renders frames

## Permissions

### Sender App

- `BLUETOOTH_SCAN` - Scan for BLE devices
- `BLUETOOTH_CONNECT` - Connect to BLE devices
- `ACCESS_FINE_LOCATION` - Required for BLE scanning
- `CAMERA` - Capture video frames
- `FOREGROUND_SERVICE` - Background streaming (optional)

### Receiver App

- `BLUETOOTH_ADVERTISE` - Advertise BLE service
- `BLUETOOTH_CONNECT` - Accept BLE connections

## Troubleshooting

### Common Issues

**"Scan Failed" Error**

- Ensure Bluetooth is enabled
- Grant location permission
- Restart Bluetooth adapter

**"L2CAP Connection Failed"**

- Ensure receiver is advertising
- Check if PSM value is valid
- Try restarting both apps

**Black Screen on Receiver**

- Wait for keyframe (I-frame)
- Check codec config (SPS/PPS) transmission
- Verify decoder initialization

**Low Frame Rate**

- Normal due to BLE bandwidth limits
- Consider reducing resolution
- Check for interference

### Debugging

Both apps log extensively to Logcat:

```bash
adb logcat -s RokidSender:D RokidReceiver:D
```

## Dependencies

| Library               | Version | Purpose                |
| --------------------- | ------- | ---------------------- |
| AndroidX Core KTX     | 1.12.0  | Kotlin extensions      |
| AndroidX AppCompat    | 1.6.1   | UI compatibility       |
| Material              | 1.11.0  | Material Design        |
| Lifecycle Runtime KTX | 2.7.0   | Lifecycle awareness    |
| LZ4 Java              | 1.8.0   | Compression (optional) |
| CameraX               | 1.3.0   | Camera API (Sender)    |

## Known Limitations

- BLE L2CAP bandwidth is limited (~1 Mbps theoretical)
- Frame rate limited to ~10 FPS for reliable streaming
- Requires Android 10+ for L2CAP support
- One-to-one connection only (no broadcast)

## Contributing

Contributions are welcome! Please:

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Acknowledgments

- Rokid for inspiring the AR glasses streaming use case
- Android CameraX team for the modern camera API
- BLE L2CAP documentation contributors

## Contact

For questions or support, please open an issue on GitHub.
