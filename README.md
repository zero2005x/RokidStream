# RokidStream

<p align="center">
  <strong>Real-time Video Streaming Between Phone and Rokid AR Glasses</strong>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-brightgreen.svg" alt="Platform">
  <img src="https://img.shields.io/badge/Kotlin-2.0.21-blueviolet.svg" alt="Kotlin">
  <img src="https://img.shields.io/badge/Min%20SDK-29-blue.svg" alt="Min SDK">
  <img src="https://img.shields.io/badge/Target%20SDK-34-blue.svg" alt="Target SDK">
  <img src="https://img.shields.io/badge/License-MIT-yellow.svg" alt="License">
</p>

<p align="center">
  <a href="doc/zh-TW/README.md">繁體中文</a>
</p>

---

## Background & Purpose

RokidStream enables **real-time H.264 video streaming** between Android phones and Rokid AR glasses via BLE L2CAP or WiFi TCP. Designed for low-latency AR applications where phone camera feeds need to be displayed on glasses, or glasses camera feeds need to be viewed on phone.

### Scope

| In Scope                        | Out of Scope            |
| ------------------------------- | ----------------------- |
| Phone ↔ Glasses video streaming | Audio streaming         |
| BLE L2CAP / WiFi TCP transport  | Cloud relay / WebRTC    |
| H.264 Baseline encoding         | HEVC / VP9 codecs       |
| Rokid AR glasses (CXR-M SDK)    | Other AR glasses brands |

---

## ⚡ Quick Start (5 minutes)

### Prerequisites

| Tool           | Version     | Check Command   |
| -------------- | ----------- | --------------- |
| Android Studio | Arctic Fox+ | `Help > About`  |
| JDK            | 17+         | `java -version` |
| Android SDK    | API 29+     | SDK Manager     |

### Build & Run

```bash
# 1. Clone
git clone https://github.com/your-org/RokidStream.git
cd RokidStream

# 2. Build both apps (debug)
./gradlew assembleDebug

# 3. Install phone app (connect phone via USB)
adb -s <PHONE_SERIAL> install phone-app/build/outputs/apk/debug/phone-app-debug.apk

# 4. Install glasses app (connect glasses via USB)
adb -s <GLASSES_SERIAL> install glasses-app/build/outputs/apk/debug/glasses-app-debug.apk
```

> **Tip**: Use `adb devices` to list connected devices and get serial numbers.

### First Run

1. **Phone App**: Launch → Select "Phone → Glasses" → Select "BLE L2CAP" → Tap Connect
2. **Glasses App**: Launch → App auto-scans → Select phone from list
3. Video streaming starts automatically after connection

---

## 🏗️ Project Structure

```
RokidStream/
├── phone-app/                    # Phone application (Sender)
│   └── src/main/java/.../sender/
│       ├── MainActivity.kt       # Legacy entry
│       ├── streaming/            # Streaming Activities
│       ├── core/                 # ConnectionManager, VideoEncoder/Decoder
│       ├── ble/                  # BLE Advertiser
│       ├── ui/                   # Compose screens, Settings
│       └── util/                 # Locale, Logging
├── glasses-app/                  # Glasses application (Receiver)
│   └── src/main/java/.../receiver/
│       ├── MainActivity.kt       # Legacy entry
│       ├── ui/                   # GlassesScannerActivity, Compose screens
│       ├── core/                 # VideoEncoder
│       └── util/                 # Locale, Logging
├── sender/                       # Shared sender module
├── receiver/                     # Shared receiver module
├── gradle/
│   └── libs.versions.toml        # Version catalog
└── doc/                          # Documentation
```

### Module Overview

| Module        | Package                     | Description                                                                    |
| ------------- | --------------------------- | ------------------------------------------------------------------------------ |
| `phone-app`   | `com.rokid.stream.sender`   | Main phone app, can send or receive video                                      |
| `glasses-app` | `com.rokid.stream.receiver` | Glasses app, typically receives video (targetSdk=32 for glasses compatibility) |

### Key Components

| Class                    | Location                   | Purpose                                  |
| ------------------------ | -------------------------- | ---------------------------------------- |
| `ConnectionManager`      | `phone-app/.../core/`      | BLE L2CAP scanning, connection, I/O      |
| `VideoEncoder`           | `phone-app/.../core/`      | H.264 encoding (240×240, 100Kbps, 10FPS) |
| `VideoDecoder`           | `phone-app/.../core/`      | H.264 decoding to Surface                |
| `WiFiStreamManager`      | `phone-app/`               | mDNS discovery + TCP streaming           |
| `ModeSelectionActivity`  | `phone-app/.../streaming/` | Main entry, mode selection               |
| `GlassesScannerActivity` | `glasses-app/.../ui/`      | Glasses main entry, device scanning      |

---

## 🔨 Build Configuration

### Gradle Versions

| Tool   | Version |
| ------ | ------- |
| AGP    | 8.3.0   |
| Kotlin | 2.0.21  |
| Gradle | 8.x     |
| JDK    | 17      |

### SDK Configuration

| Config        | phone-app | glasses-app |
| ------------- | --------- | ----------- |
| `compileSdk`  | 34        | 34          |
| `minSdk`      | 29        | 29          |
| `targetSdk`   | 34        | 32          |
| `versionCode` | 1         | 1           |

### Build Commands

```bash
# Debug builds
./gradlew :phone-app:assembleDebug
./gradlew :glasses-app:assembleDebug

# Release builds
./gradlew :phone-app:assembleRelease
./gradlew :glasses-app:assembleRelease

# Build all
./gradlew assembleDebug

# Clean build
./gradlew clean assembleDebug
```

### Build Types

| Type      | Minify | ProGuard                | Description             |
| --------- | ------ | ----------------------- | ----------------------- |
| `debug`   | ❌     | ❌                      | Development, debuggable |
| `release` | ❌     | Configured but disabled | Production-ready        |

> **Note**: ProGuard is configured in `proguard-rules.pro` but `isMinifyEnabled = false` by default.

---

## 📱 Required Permissions

Declared in `AndroidManifest.xml`:

| Permission             | Purpose               | Runtime Request  |
| ---------------------- | --------------------- | ---------------- |
| `CAMERA`               | Video capture         | ✅ Yes           |
| `BLUETOOTH_SCAN`       | Device discovery      | ✅ Yes (API 31+) |
| `BLUETOOTH_CONNECT`    | BLE connection        | ✅ Yes (API 31+) |
| `BLUETOOTH_ADVERTISE`  | BLE advertising       | ✅ Yes (API 31+) |
| `ACCESS_FINE_LOCATION` | BLE/WiFi scanning     | ✅ Yes           |
| `NEARBY_WIFI_DEVICES`  | WiFi Direct (API 33+) | ✅ Yes           |
| `INTERNET`             | Network access        | ❌ No            |

### Adding a New Permission

1. Add to `AndroidManifest.xml`:

   ```xml
   <uses-permission android:name="android.permission.NEW_PERMISSION" />
   ```

2. Request at runtime in Activity:
   ```kotlin
   // See ModeSelectionActivity.kt for permission request pattern
   ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.NEW_PERMISSION), REQUEST_CODE)
   ```

---

## 🧪 Testing

### Unit Tests

```bash
# Run all unit tests
./gradlew test

# Run specific module tests
./gradlew :phone-app:testDebugUnitTest
./gradlew :glasses-app:testDebugUnitTest
```

### Instrumented Tests

```bash
# Run instrumented tests (requires connected device)
./gradlew connectedAndroidTest

# Specific module
./gradlew :phone-app:connectedDebugAndroidTest
```

### Manual Testing Checklist

- [ ] BLE L2CAP: Phone → Glasses streaming
- [ ] BLE L2CAP: Glasses → Phone streaming
- [ ] WiFi TCP: Both modes
- [ ] Reconnection after disconnect
- [ ] App backgrounding/foregrounding
- [ ] Permission denial handling

---

## 🛠️ Common Development Tasks

### Adding a New Screen (Compose)

1. Create Composable in `ui/` folder:

   ```kotlin
   // phone-app/.../ui/NewScreen.kt
   @Composable
   fun NewScreen(viewModel: NewViewModel = viewModel()) {
       // UI code
   }
   ```

2. Create ViewModel:

   ```kotlin
   // phone-app/.../ui/NewViewModel.kt
   class NewViewModel : ViewModel() {
       // State and logic
   }
   ```

3. Add navigation in Activity or NavHost

### Adding a New Activity

1. Create Activity class in appropriate package
2. Register in `AndroidManifest.xml`:
   ```xml
   <activity
       android:name=".package.NewActivity"
       android:exported="false"
       android:parentActivityName=".streaming.ModeSelectionActivity" />
   ```

### Modifying Video Parameters

Edit constants in `VideoEncoder.kt`:

```kotlin
// phone-app/.../core/VideoEncoder.kt
const val DEFAULT_WIDTH = 240       // Resolution
const val DEFAULT_HEIGHT = 240
const val DEFAULT_BITRATE = 100_000 // 100 Kbps
const val DEFAULT_FRAME_RATE = 10   // 10 FPS
const val I_FRAME_INTERVAL = 3      // Keyframe every 3 seconds
```

---

## ❓ FAQ & Troubleshooting

### Build Issues

<details>
<summary><b>Gradle sync failed: Could not resolve com.rokid.cxr:client-m</b></summary>

Ensure you have access to Rokid's Maven repository. Check `settings.gradle.kts` for repository configuration:

```kotlin
maven { url = uri("https://maven.rokid.com/repository/...") }
```

</details>

<details>
<summary><b>JDK version mismatch error</b></summary>

Project requires JDK 17. In Android Studio:

- `File > Settings > Build > Gradle > Gradle JDK` → Select JDK 17
</details>

### Runtime Issues

<details>
<summary><b>BLE scanning finds no devices</b></summary>

1. Ensure Location permission is granted
2. Ensure Bluetooth is enabled
3. Glasses app must be running and advertising
4. Check if glasses is already connected to another phone
</details>

<details>
<summary><b>Video freezes after a few seconds</b></summary>

1. BLE bandwidth limited (~100Kbps) - this is expected for low resolution
2. Check for `KEY_FRAME_REQUEST` handling
3. Try WiFi TCP mode for higher bandwidth
</details>

<details>
<summary><b>L2CAP connection fails on Android 12+</b></summary>

Ensure `BLUETOOTH_CONNECT` permission is granted (required for API 31+).

</details>

### ProGuard Issues

<details>
<summary><b>Release build crashes but debug works</b></summary>

Add keep rules in `proguard-rules.pro`:

```proguard
-keep class com.rokid.** { *; }
-keep class com.squareup.** { *; }
```

</details>

---

## 📚 Documentation

| Document                                  | Description                                |
| ----------------------------------------- | ------------------------------------------ |
| [Architecture](doc/architecture.md)       | System design, module structure, data flow |
| [API Reference](doc/api-reference.md)     | Core classes, methods, callbacks           |
| [Protocol](doc/protocol.md)               | BLE L2CAP / WiFi TCP frame formats         |
| [Developer Guide](doc/developer-guide.md) | Common dev tasks, debugging, code style    |

### 繁體中文文檔

- [README (繁體中文)](doc/zh-TW/README.md)
- [系統架構](doc/zh-TW/architecture.md)
- [API 參考](doc/zh-TW/api-reference.md)
- [通訊協定](doc/zh-TW/protocol.md)
- [開發者指南](doc/zh-TW/developer-guide.md)

---

## 📄 License

This project is licensed under the [MIT License](LICENSE).

## 🤝 Contributing

1. Fork this repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## 📞 Contact

For questions or suggestions, please contact us via GitHub Issues.
