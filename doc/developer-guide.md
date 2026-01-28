# Developer Guide

This guide helps new Android developers get up to speed with RokidStream development in 30 minutes.

---

## Table of Contents

1. [Development Environment Setup](#1-development-environment-setup)
2. [Project Navigation](#2-project-navigation)
3. [Common Development Scenarios](#3-common-development-scenarios)
4. [Debugging Tips](#4-debugging-tips)
5. [Code Style & Conventions](#5-code-style--conventions)

---

## 1. Development Environment Setup

### Required Tools

| Tool           | Version     | Download                                                      |
| -------------- | ----------- | ------------------------------------------------------------- |
| Android Studio | Arctic Fox+ | [developer.android.com](https://developer.android.com/studio) |
| JDK            | 17          | Bundled with Android Studio                                   |
| Git            | Latest      | [git-scm.com](https://git-scm.com/)                           |

### First-Time Setup

```bash
# 1. Clone repository
git clone https://github.com/your-org/RokidStream.git
cd RokidStream

# 2. Open in Android Studio
# File > Open > Select RokidStream folder

# 3. Wait for Gradle sync (may take 2-5 minutes first time)

# 4. Verify setup by building
./gradlew assembleDebug
```

### Environment Variables (Optional)

```bash
# If using Rokid Maven repository, ensure credentials are set
# In ~/.gradle/gradle.properties:
rokidMavenUser=your_username
rokidMavenPassword=your_password
```

---

## 2. Project Navigation

### Where to Find Things

| Task                        | Location                                                       |
| --------------------------- | -------------------------------------------------------------- |
| Add a new screen (phone)    | `phone-app/src/main/java/.../sender/ui/`                       |
| Add a new Activity (phone)  | `phone-app/src/main/java/.../sender/streaming/`                |
| Modify BLE connection logic | `phone-app/src/main/java/.../sender/core/ConnectionManager.kt` |
| Modify video encoding       | `phone-app/src/main/java/.../sender/core/VideoEncoder.kt`      |
| Modify video decoding       | `phone-app/src/main/java/.../sender/core/VideoDecoder.kt`      |
| Add WiFi streaming features | `phone-app/src/main/java/.../sender/WiFiStreamManager.kt`      |
| Modify glasses UI           | `glasses-app/src/main/java/.../receiver/ui/`                   |
| Add a new dependency        | `gradle/libs.versions.toml` or module's `build.gradle.kts`     |
| Modify permissions          | `phone-app/src/main/AndroidManifest.xml`                       |
| Add string resources        | `phone-app/src/main/res/values/strings.xml`                    |

### Key Files Quick Reference

```
phone-app/
├── src/main/
│   ├── AndroidManifest.xml          # Permissions, Activities
│   ├── java/.../sender/
│   │   ├── streaming/
│   │   │   ├── ModeSelectionActivity.kt   # ⭐ Main entry point
│   │   │   ├── PhoneToGlassesActivity.kt  # Phone→Glasses streaming
│   │   │   ├── GlassesToPhoneActivity.kt  # Glasses→Phone streaming
│   │   │   └── BidirectionalActivity.kt   # Bidirectional streaming
│   │   ├── core/
│   │   │   ├── ConnectionManager.kt       # ⭐ BLE L2CAP logic
│   │   │   ├── VideoEncoder.kt            # ⭐ H.264 encoding
│   │   │   └── VideoDecoder.kt            # H.264 decoding
│   │   └── WiFiStreamManager.kt           # ⭐ WiFi TCP logic
│   └── res/
│       └── values/
│           └── strings.xml                # String resources
└── build.gradle.kts                       # Dependencies
```

---

## 3. Common Development Scenarios

### Scenario 1: Add a New API Endpoint

If integrating with a backend server:

```kotlin
// 1. Add Retrofit interface (create if not exists)
// phone-app/.../api/RokidApi.kt

interface RokidApi {
    @GET("devices")
    suspend fun getDevices(): Response<List<Device>>

    @POST("stream/start")
    suspend fun startStream(@Body request: StreamRequest): Response<StreamResponse>
}

// 2. Create data classes
// phone-app/.../model/Device.kt
data class Device(
    val id: String,
    val name: String,
    val status: String
)

// 3. Use in ViewModel
class StreamingViewModel : ViewModel() {
    private val api = Retrofit.Builder()
        .baseUrl("https://api.example.com/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(RokidApi::class.java)

    fun fetchDevices() {
        viewModelScope.launch {
            val response = api.getDevices()
            // Handle response
        }
    }
}
```

### Scenario 2: Add a New Screen (Jetpack Compose)

```kotlin
// 1. Create the Composable
// phone-app/.../ui/DeviceListScreen.kt

@Composable
fun DeviceListScreen(
    viewModel: DeviceListViewModel = viewModel(),
    onDeviceSelected: (Device) -> Unit
) {
    val devices by viewModel.devices.collectAsState()

    LazyColumn {
        items(devices) { device ->
            DeviceItem(
                device = device,
                onClick = { onDeviceSelected(device) }
            )
        }
    }
}

@Composable
private fun DeviceItem(device: Device, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .clickable(onClick = onClick)
    ) {
        Text(
            text = device.name,
            modifier = Modifier.padding(16.dp)
        )
    }
}

// 2. Create ViewModel
// phone-app/.../ui/DeviceListViewModel.kt

class DeviceListViewModel : ViewModel() {
    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()

    fun loadDevices() {
        viewModelScope.launch {
            // Load devices
        }
    }
}

// 3. Add to navigation (if using NavHost)
// In your NavHost setup:
composable("device_list") {
    DeviceListScreen(
        onDeviceSelected = { device ->
            navController.navigate("streaming/${device.id}")
        }
    )
}
```

### Scenario 3: Add a New Activity

```kotlin
// 1. Create Activity class
// phone-app/.../streaming/NewFeatureActivity.kt

class NewFeatureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RokidStreamTheme {
                NewFeatureScreen()
            }
        }
    }
}

// 2. Register in AndroidManifest.xml
// phone-app/src/main/AndroidManifest.xml

<activity
    android:name=".streaming.NewFeatureActivity"
    android:exported="false"
    android:parentActivityName=".streaming.ModeSelectionActivity"
    android:screenOrientation="portrait" />

// 3. Navigate to it
// From ModeSelectionActivity or another Activity:
startActivity(Intent(this, NewFeatureActivity::class.java))
```

### Scenario 4: Add a New Permission

```xml
<!-- 1. Declare in AndroidManifest.xml -->
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

```kotlin
// 2. Request at runtime (in Activity)
private val AUDIO_PERMISSION_CODE = 100

private fun checkAudioPermission() {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED) {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            AUDIO_PERMISSION_CODE
        )
    } else {
        // Permission granted, proceed
        startAudioCapture()
    }
}

override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<out String>,
    grantResults: IntArray
) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    when (requestCode) {
        AUDIO_PERMISSION_CODE -> {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startAudioCapture()
            } else {
                // Permission denied, show explanation
                Toast.makeText(this, "Audio permission required", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
```

### Scenario 5: Modify Video Encoding Parameters

```kotlin
// Edit: phone-app/.../core/VideoEncoder.kt

class VideoEncoder(
    private val width: Int = 240,      // Change resolution here
    private val height: Int = 240,
    private val bitrate: Int = 100_000, // Change bitrate (bps)
    private val frameRate: Int = 10     // Change FPS
) {
    companion object {
        const val I_FRAME_INTERVAL = 3  // Keyframe interval in seconds
    }

    // For WiFi mode, you might want higher quality:
    // width = 720, height = 720, bitrate = 2_000_000, frameRate = 30
}
```

### Scenario 6: Add a New Dependency

```kotlin
// Option A: Add to version catalog (recommended)
// gradle/libs.versions.toml

[versions]
newlib = "1.0.0"

[libraries]
new-library = { group = "com.example", name = "library", version.ref = "newlib" }

// Then in build.gradle.kts:
dependencies {
    implementation(libs.new.library)
}

// Option B: Direct dependency
// phone-app/build.gradle.kts

dependencies {
    implementation("com.example:library:1.0.0")
}
```

---

## 4. Debugging Tips

### Logcat Filters

```
# BLE connection logs
tag:ConnectionManager

# Video encoding logs
tag:VideoEncoder

# WiFi streaming logs
tag:WiFiStreamManager

# All RokidStream logs
package:com.rokid.stream
```

### Common Debugging Commands

```bash
# View connected devices
adb devices

# Install and run (phone)
adb -s <PHONE_SERIAL> install -r phone-app/build/outputs/apk/debug/phone-app-debug.apk
adb -s <PHONE_SERIAL> shell am start -n com.rokid.stream.sender/.streaming.ModeSelectionActivity

# View logs in real-time
adb logcat -s ConnectionManager:V VideoEncoder:V

# Clear app data
adb shell pm clear com.rokid.stream.sender
```

### Debugging BLE Issues

1. **Enable Bluetooth HCI Snoop Log**:
   - Settings > Developer Options > Enable Bluetooth HCI snoop log
   - Reproduce issue
   - Pull log: `adb pull /data/misc/bluetooth/logs/btsnoop_hci.log`

2. **Check BLE Connection State**:
   ```kotlin
   // Add logging in ConnectionManager.kt
   Log.d(TAG, "BLE State: ${bluetoothAdapter.state}")
   Log.d(TAG, "GATT State: ${gatt?.connectionState}")
   ```

### Debugging Video Issues

1. **Check encoder output**:

   ```kotlin
   // In VideoEncoder.kt, add after encoding
   Log.d(TAG, "Encoded frame: ${outputBuffer.remaining()} bytes, keyframe: $isKeyFrame")
   ```

2. **Verify SPS/PPS**:
   ```kotlin
   // Check if codec config is being sent
   Log.d(TAG, "SPS/PPS data: ${spsPpsData?.size} bytes")
   ```

---

## 5. Code Style & Conventions

### Kotlin Style

- Use `camelCase` for functions and properties
- Use `PascalCase` for classes and interfaces
- Use `SCREAMING_SNAKE_CASE` for constants
- Prefer `val` over `var`
- Use data classes for DTOs

### File Organization

```kotlin
// Order of declarations in a class:
class MyClass {
    // 1. Companion object (constants, factory methods)
    companion object {
        const val TAG = "MyClass"
    }

    // 2. Properties
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    // 3. Initialization blocks
    init {
        // ...
    }

    // 4. Public methods
    fun doSomething() {
        // ...
    }

    // 5. Private methods
    private fun helperMethod() {
        // ...
    }

    // 6. Inner classes
    sealed class State {
        object Idle : State()
        data class Connected(val device: BluetoothDevice) : State()
    }
}
```

### Naming Conventions

| Type       | Convention               | Example                         |
| ---------- | ------------------------ | ------------------------------- |
| Activity   | `*Activity`              | `ModeSelectionActivity`         |
| Fragment   | `*Fragment`              | `StreamingFragment`             |
| Composable | `*Screen` or descriptive | `StreamingScreen`, `DeviceItem` |
| ViewModel  | `*ViewModel`             | `StreamingViewModel`            |
| Interface  | Descriptive, no prefix   | `ConnectionCallback`            |
| Callback   | `on*`                    | `onConnected`, `onError`        |

### Documentation

````kotlin
/**
 * Manages BLE L2CAP connections for video streaming.
 *
 * Usage:
 * ```
 * val manager = ConnectionManager(context)
 * manager.onConnected = { output, input -> startStreaming() }
 * manager.startScan()
 * ```
 *
 * @param context Application or Activity context
 * @see VideoEncoder
 */
class ConnectionManager(private val context: Context) {
    // ...
}
````

---

## Quick Reference Card

### Build Commands

| Command                              | Description          |
| ------------------------------------ | -------------------- |
| `./gradlew assembleDebug`            | Build all debug APKs |
| `./gradlew :phone-app:assembleDebug` | Build phone app only |
| `./gradlew clean`                    | Clean build outputs  |
| `./gradlew test`                     | Run unit tests       |

### ADB Commands

| Command                                      | Description            |
| -------------------------------------------- | ---------------------- |
| `adb devices`                                | List connected devices |
| `adb install <apk>`                          | Install APK            |
| `adb logcat -s TAG:V`                        | Filter logcat by tag   |
| `adb shell am start -n <package>/<activity>` | Launch activity        |

### Key Classes

| Class                    | Purpose              |
| ------------------------ | -------------------- |
| `ConnectionManager`      | BLE L2CAP connection |
| `VideoEncoder`           | H.264 encoding       |
| `VideoDecoder`           | H.264 decoding       |
| `WiFiStreamManager`      | WiFi TCP streaming   |
| `ModeSelectionActivity`  | Phone app entry      |
| `GlassesScannerActivity` | Glasses app entry    |
