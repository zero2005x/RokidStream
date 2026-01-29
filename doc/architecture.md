# System Architecture

This document provides a detailed description of the RokidStream project's system architecture, module design, and data flow.

## 1. System Overview

RokidStream is a real-time video streaming system between smartphones and Rokid AR glasses, using a client-server architecture for video transmission.

```
┌─────────────────────┐         ┌─────────────────────┐
│                     │         │                     │
│   Phone App         │◄───────►│   Glasses App       │
│   (Sender)          │         │   (Receiver)        │
│                     │         │                     │
│  ┌───────────────┐  │   BLE   │  ┌───────────────┐  │
│  │ Camera        │  │   or    │  │ Video Display │  │
│  │ Capture       │──┼────────►│  │ (SurfaceView) │  │
│  └───────────────┘  │  WiFi   │  └───────────────┘  │
│                     │         │                     │
│  ┌───────────────┐  │         │  ┌───────────────┐  │
│  │ Video Display │◄─┼─────────┼──│ Camera        │  │
│  │ (SurfaceView) │  │         │  │ Capture       │  │
│  └───────────────┘  │         │  └───────────────┘  │
│                     │         │                     │
└─────────────────────┘         └─────────────────────┘
```

## 2. Module Architecture

### 2.1 Phone App Module Structure

```
phone-app/
└── src/main/java/com/rokid/stream/sender/
    ├── MainActivity.kt              # Main entry (Legacy)
    ├── RokidSDKManager.kt           # Rokid CXR-M SDK management
    ├── WiFiStreamManager.kt         # WiFi TCP streaming management
    ├── ble/
    │   └── BleAdvertiser.kt         # BLE advertiser
    ├── core/
    │   ├── ConnectionManager.kt     # Unified connection management
    │   ├── VideoEncoder.kt          # H.264 encoder
    │   └── VideoDecoder.kt          # H.264 decoder
    ├── streaming/
    │   ├── ModeSelectionActivity.kt # Mode selection main page
    │   ├── PhoneToGlassesActivity.kt# Phone→Glasses streaming
    │   └── GlassesToPhoneActivity.kt# Glasses→Phone streaming
    ├── ui/
    │   ├── SettingsActivity.kt      # Settings page
    │   ├── LanguageSelectionActivity.kt # Language selection
    │   ├── StreamingScreen.kt       # Compose streaming screen
    │   ├── StreamingViewModel.kt    # Streaming ViewModel
    │   └── theme/                   # Material Theme
    └── util/
        ├── LocaleManager.kt         # Locale management
        └── LogManager.kt            # Log management
```

### 2.2 Glasses App Module Structure

```
glasses-app/
└── src/main/java/com/rokid/stream/receiver/
    ├── MainActivity.kt              # Main entry (Legacy)
    ├── RokidSDKManager.kt           # Rokid CXR-M SDK management
    ├── WiFiStreamManager.kt         # WiFi TCP streaming management
    ├── core/
    │   └── VideoEncoder.kt          # H.264 encoder
    ├── ui/
    │   ├── GlassesScannerActivity.kt# Scanner main page
    │   ├── GlassesScannerScreen.kt  # Compose scanner screen
    │   ├── ReceiverScreen.kt        # Compose receiver screen
    │   ├── ReceiverViewModel.kt     # Receiver ViewModel
    │   └── theme/                   # Material Theme
    ├── viewmodel/
    │   └── ...                      # ViewModel classes
    └── util/
        ├── LocaleManager.kt         # Locale management
        └── LogManager.kt            # Log management
```

## 3. Core Component Design

### 3.1 ConnectionManager

Unified BLE connection manager responsible for:

- BLE device scanning
- GATT connection establishment
- PSM (Protocol/Service Multiplexer) discovery
- L2CAP channel management

```kotlin
class ConnectionManager(private val context: Context) {
    // BLE Service UUID
    val SERVICE_UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")

    // PSM Characteristic UUID (Phone → Glasses)
    val PSM_CHAR_UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")

    // Reverse PSM Characteristic UUID (Glasses → Phone)
    val REVERSE_PSM_CHAR_UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")

    fun startScan()           // Start scanning
    fun stopScan()            // Stop scanning
    fun connect(device)       // Connect to device
    fun disconnect()          // Disconnect
}
```

### 3.2 VideoEncoder

Low-latency H.264 video encoder:

| Parameter         | Default   | Description                      |
| ----------------- | --------- | -------------------------------- |
| Resolution        | 240×240   | Suitable for BLE L2CAP bandwidth |
| Bitrate           | 100 Kbps  | Stable BLE transmission          |
| Frame Rate        | 10 FPS    | Balance latency and smoothness   |
| Profile           | Baseline  | Maximum compatibility            |
| Keyframe Interval | 3 seconds | Reduce bandwidth usage           |

```kotlin
class VideoEncoder(
    private val width: Int = 240,
    private val height: Int = 240,
    private val bitrate: Int = 100_000,
    private val frameRate: Int = 10
) {
    fun start(): Boolean                    // Start encoder
    fun stop()                              // Stop encoder
    fun encodeFrame(imageProxy: ImageProxy) // Encode frame
    fun getEncodedFrame(): ByteArray?       // Get encoded data
}
```

### 3.3 VideoDecoder

Low-latency H.264 video decoder:

```kotlin
class VideoDecoder(
    private val width: Int = 720,
    private val height: Int = 720
) {
    fun setSurface(surface: Surface)        // Set output surface
    fun start(configData: ByteArray?): Boolean // Start decoder
    fun stop()                              // Stop decoder
    fun decodeFrame(frameData: ByteArray)   // Decode frame
}
```

### 3.4 WiFiStreamManager

WiFi TCP streaming manager supporting:

- mDNS/NSD service discovery
- TCP Socket connections
- Data transmission

```kotlin
class WiFiStreamManager(private val context: Context) {
    // Service discovery
    const val SERVICE_TYPE = "_rokidstream._tcp."
    const val STREAM_PORT = 18800      // Phone → Glasses
    const val REVERSE_PORT = 18801     // Glasses → Phone

    // Frame types
    const val FRAME_TYPE_VIDEO = 0x01
    const val FRAME_TYPE_CONTROL = 0x02
    const val FRAME_TYPE_HEARTBEAT = 0x03
}
```

### 3.5 RokidSDKManager

Rokid CXR-M SDK wrapper providing:

- SDK initialization
- Device discovery and connection
- Message send/receive

## 4. Streaming Modes

### 4.1 Phone → Glasses (PhoneToGlasses)

```
┌──────────┐    ┌───────────┐    ┌────────┐    ┌────────────┐
│ Camera   │───►│ Encoder   │───►│ Socket │───►│ Decoder    │
│ (Phone)  │    │ (H.264)   │    │ (BLE/  │    │ (Glasses)  │
│          │    │           │    │  WiFi) │    │            │
└──────────┘    └───────────┘    └────────┘    └────────────┘

                    Phone                          Glasses
```

### 4.2 Glasses → Phone (GlassesToPhone)

```
┌──────────┐    ┌───────────┐    ┌────────┐    ┌────────────┐
│ Camera   │───►│ Encoder   │───►│ Socket │───►│ Decoder    │
│ (Glasses)│    │ (H.264)   │    │ (BLE/  │    │ (Phone)    │
│          │    │           │    │  WiFi) │    │            │
└──────────┘    └───────────┘    └────────┘    └────────────┘

                   Glasses                          Phone
```

## 5. UI Architecture

The project uses a hybrid architecture of Jetpack Compose and traditional Views:

### 5.1 Activity Structure

```
ModeSelectionActivity (Main entry)
    ├── PhoneToGlassesActivity
    ├── GlassesToPhoneActivity
    └── SettingsActivity
        └── LanguageSelectionActivity
```

### 5.2 Compose Components

- `StreamingScreen` - Main streaming screen
- `GlassesScannerScreen` - Device scanner screen
- `ReceiverScreen` - Receiver display screen
- `LogManagerScreen` - Log viewer screen

## 6. Dependencies

### 6.1 AndroidX

| Package            | Version    | Purpose            |
| ------------------ | ---------- | ------------------ |
| core-ktx           | 1.12.0     | Kotlin extensions  |
| appcompat          | 1.6.1      | Backward compat    |
| lifecycle          | 2.7.0      | Lifecycle mgmt     |
| camera             | 1.3.0      | CameraX            |
| compose-bom        | 2024.02.00 | Jetpack Compose    |
| navigation-compose | 2.7.7      | Compose navigation |

### 6.2 Third-party

| Package   | Version | Purpose      |
| --------- | ------- | ------------ |
| lz4-java  | 1.8.0   | Compression  |
| retrofit2 | 2.9.0   | Network      |
| okhttp3   | 4.9.3   | HTTP client  |
| gson      | 2.10.1  | JSON parsing |

### 6.3 Rokid SDK

| Package  | Version | Purpose          |
| -------- | ------- | ---------------- |
| client-m | 1.0.1   | CXR-M connection |

## 7. Build Configuration

### 7.1 Gradle Versions

- AGP: 8.3.0
- Kotlin: 2.0.21
- Gradle: 8.x

### 7.2 Target Versions

| Config     | Phone App | Glasses App |
| ---------- | --------- | ----------- |
| compileSdk | 34        | 34          |
| minSdk     | 29        | 29          |
| targetSdk  | 34        | 32          |

## 8. Design Principles

1. **Modularity**: Core components are reusable across Activities
2. **Low Latency**: Encoding parameters optimized for real-time streaming
3. **Multiple Transports**: Abstracted transport layer supporting BLE and WiFi
4. **Reactive UI**: MVVM implementation using Compose and ViewModel
