# API Reference

This document describes the core classes, interfaces, and public APIs of the RokidStream project.

## 1. Phone App - Core Module

### 1.1 ConnectionManager

Unified BLE L2CAP connection management class.

#### Package Path

```kotlin
com.rokid.stream.sender.core.ConnectionManager
```

#### Constructor

```kotlin
class ConnectionManager(private val context: Context)
```

#### Constants

| Name                    | Value                                  | Description             |
| ----------------------- | -------------------------------------- | ----------------------- |
| `SERVICE_UUID`          | `6e400001-b5a3-f393-e0a9-e50e24dcca9e` | BLE Service UUID        |
| `PSM_CHAR_UUID`         | `6e400002-b5a3-f393-e0a9-e50e24dcca9e` | PSM Characteristic UUID |
| `REVERSE_PSM_CHAR_UUID` | `6e400003-b5a3-f393-e0a9-e50e24dcca9e` | Reverse PSM UUID        |
| `MAX_FRAME_SIZE`        | `1,000,000`                            | Max frame size (1MB)    |

#### Properties

| Name          | Type      | Description                   |
| ------------- | --------- | ----------------------------- |
| `isConnected` | `Boolean` | Connection status (read-only) |
| `isScanning`  | `Boolean` | Scanning status (read-only)   |

#### Callback Configuration

```kotlin
// Scan result callback
var onScanResult: ((BluetoothDevice) -> Unit)?

// Connection success callback
var onConnected: ((OutputStream?, InputStream?) -> Unit)?

// Disconnection callback
var onDisconnected: (() -> Unit)?

// Error callback
var onError: ((String) -> Unit)?

// Log callback
var onLog: ((String) -> Unit)?
```

#### Methods

| Method                             | Description                 |
| ---------------------------------- | --------------------------- |
| `startScan()`                      | Start BLE scanning          |
| `stopScan()`                       | Stop BLE scanning           |
| `connect(device: BluetoothDevice)` | Connect to specified device |
| `disconnect()`                     | Disconnect                  |
| `sendFrame(data: ByteArray)`       | Send video frame            |
| `receiveFrame(): ByteArray?`       | Receive video frame         |

---

### 1.2 VideoEncoder

H.264 video encoder class.

#### Package Path

```kotlin
com.rokid.stream.sender.core.VideoEncoder
```

#### Constructor

```kotlin
class VideoEncoder(
    private val width: Int = DEFAULT_WIDTH,      // 240
    private val height: Int = DEFAULT_HEIGHT,    // 240
    private val bitrate: Int = DEFAULT_BITRATE,  // 100,000
    private val frameRate: Int = DEFAULT_FRAME_RATE // 10
)
```

#### Constants

| Name                 | Value     | Description                    |
| -------------------- | --------- | ------------------------------ |
| `DEFAULT_WIDTH`      | `240`     | Default width                  |
| `DEFAULT_HEIGHT`     | `240`     | Default height                 |
| `DEFAULT_BITRATE`    | `100,000` | Default bitrate (100 Kbps)     |
| `DEFAULT_FRAME_RATE` | `10`      | Default frame rate (10 FPS)    |
| `I_FRAME_INTERVAL`   | `3`       | Key frame interval (seconds)   |
| `ENCODER_TIMEOUT_US` | `1000`    | Encoder timeout (microseconds) |
| `MAX_PENDING_FRAMES` | `30`      | Max pending frames             |

#### Properties

| Name         | Type         | Description                |
| ------------ | ------------ | -------------------------- |
| `spsPpsData` | `ByteArray?` | SPS/PPS data (read-only)   |
| `isRunning`  | `Boolean`    | Running status (read-only) |

#### Callback Configuration

```kotlin
// Encoding complete callback
var onFrameEncoded: ((ByteArray, Boolean) -> Unit)?
// Parameters: frameData, isKeyFrame
```

#### Methods

| Method                                       | Return       | Description         |
| -------------------------------------------- | ------------ | ------------------- |
| `start(actualWidth: Int, actualHeight: Int)` | `Boolean`    | Start encoder       |
| `stop()`                                     | `Unit`       | Stop encoder        |
| `encodeFrame(image: ImageProxy)`             | `Unit`       | Encode camera frame |
| `getEncodedFrame()`                          | `ByteArray?` | Get encoded data    |
| `requestKeyFrame()`                          | `Unit`       | Request key frame   |

---

### 1.3 VideoDecoder

H.264 video decoder class.

#### Package Path

```kotlin
com.rokid.stream.sender.core.VideoDecoder
```

#### Constructor

```kotlin
class VideoDecoder(
    private val width: Int = DEFAULT_WIDTH,   // 720
    private val height: Int = DEFAULT_HEIGHT  // 720
)
```

#### Constants

| Name                 | Value  | Description                    |
| -------------------- | ------ | ------------------------------ |
| `DEFAULT_WIDTH`      | `720`  | Default width                  |
| `DEFAULT_HEIGHT`     | `720`  | Default height                 |
| `DECODER_TIMEOUT_US` | `1000` | Decoder timeout (microseconds) |
| `MAX_FRAME_QUEUE`    | `10`   | Max frame queue length         |

#### Properties

| Name              | Type         | Description                   |
| ----------------- | ------------ | ----------------------------- |
| `codecConfigData` | `ByteArray?` | Codec config data (read-only) |
| `isRunning`       | `Boolean`    | Running status (read-only)    |

#### Methods

| Method                          | Return    | Description        |
| ------------------------------- | --------- | ------------------ |
| `setSurface(surface: Surface)`  | `Unit`    | Set output surface |
| `start(configData: ByteArray?)` | `Boolean` | Start decoder      |
| `stop()`                        | `Unit`    | Stop decoder       |
| `decodeFrame(data: ByteArray)`  | `Unit`    | Decode video frame |
| `flush()`                       | `Unit`    | Flush decode queue |

---

## 2. Phone App - WiFi Module

### 2.1 WiFiStreamManager

WiFi TCP streaming management class.

#### Package Path

```kotlin
com.rokid.stream.sender.WiFiStreamManager
```

#### Constructor

```kotlin
class WiFiStreamManager(private val context: Context)
```

#### Constants

| Name                   | Value                | Description                    |
| ---------------------- | -------------------- | ------------------------------ |
| `SERVICE_TYPE`         | `_rokidstream._tcp.` | mDNS service type              |
| `SERVICE_NAME`         | `RokidStream`        | Service name                   |
| `STREAM_PORT`          | `18800`              | Stream port (Phone → Glasses)  |
| `REVERSE_PORT`         | `18801`              | Reverse port (Glasses → Phone) |
| `FRAME_TYPE_VIDEO`     | `0x01`               | Video frame type               |
| `FRAME_TYPE_CONTROL`   | `0x02`               | Control message type           |
| `FRAME_TYPE_HEARTBEAT` | `0x03`               | Heartbeat message type         |
| `CONNECT_TIMEOUT_MS`   | `10000`              | Connection timeout (ms)        |
| `READ_TIMEOUT_MS`      | `30000`              | Read timeout (ms)              |

#### Interfaces

##### ConnectionCallback

```kotlin
interface ConnectionCallback {
    fun onServiceDiscovered(serviceName: String, host: String, port: Int)
    fun onConnected()
    fun onDisconnected()
    fun onError(error: String)
}
```

##### StreamCallback

```kotlin
interface StreamCallback {
    fun onVideoFrameReceived(frameData: ByteArray, timestamp: Long, isKeyFrame: Boolean)
    fun onControlMessageReceived(message: String)
}
```

#### Methods

| Method                                                 | Description               |
| ------------------------------------------------------ | ------------------------- |
| `setConnectionCallback(callback)`                      | Set connection callback   |
| `setStreamCallback(callback)`                          | Set stream callback       |
| `startDiscovery()`                                     | Start service discovery   |
| `stopDiscovery()`                                      | Stop service discovery    |
| `connect(host: String, port: Int)`                     | Connect to specified host |
| `disconnect()`                                         | Disconnect                |
| `sendVideoFrame(data: ByteArray, isKeyFrame: Boolean)` | Send video frame          |
| `sendControlMessage(message: String)`                  | Send control message      |

---

### 2.2 RokidSDKManager

Rokid CXR-M SDK wrapper class.

#### Package Path

```kotlin
com.rokid.stream.sender.RokidSDKManager
```

#### Constructor

```kotlin
class RokidSDKManager(private val context: Context)
```

#### Constants

| Name                 | Value | Description  |
| -------------------- | ----- | ------------ |
| `STATE_DISCONNECTED` | `0`   | Disconnected |
| `STATE_SCANNING`     | `1`   | Scanning     |
| `STATE_CONNECTING`   | `2`   | Connecting   |
| `STATE_CONNECTED`    | `3`   | Connected    |

#### Interfaces

##### ConnectionCallback

```kotlin
interface ConnectionCallback {
    fun onDeviceFound(deviceId: String, deviceName: String)
    fun onConnected(deviceId: String)
    fun onDisconnected(reason: String)
    fun onConnectionFailed(error: String)
}
```

##### MessageCallback

```kotlin
interface MessageCallback {
    fun onMapMessageReceived(data: Map<String, Any>)
    fun onBytesReceived(data: ByteArray)
}
```

#### Methods

| Method                            | Return    | Description             |
| --------------------------------- | --------- | ----------------------- |
| `initialize()`                    | `Boolean` | Initialize SDK          |
| `setConnectionCallback(callback)` | `Unit`    | Set connection callback |
| `setMessageCallback(callback)`    | `Unit`    | Set message callback    |
| `startScan()`                     | `Unit`    | Start scanning          |
| `stopScan()`                      | `Unit`    | Stop scanning           |
| `connect(deviceId: String)`       | `Unit`    | Connect to device       |
| `disconnect()`                    | `Unit`    | Disconnect              |
| `sendBytes(data: ByteArray)`      | `Boolean` | Send byte data          |
| `sendMap(data: Map<String, Any>)` | `Boolean` | Send Map data           |
| `release()`                       | `Unit`    | Release resources       |

---

## 3. Phone App - BLE Module

### 3.1 BleAdvertiser

BLE advertising class for glasses device discovery.

#### Package Path

```kotlin
com.rokid.stream.sender.ble.BleAdvertiser
```

#### Constructor

```kotlin
class BleAdvertiser(private val context: Context)
```

#### Methods

| Method                     | Description           |
| -------------------------- | --------------------- |
| `startAdvertising()`       | Start BLE advertising |
| `stopAdvertising()`        | Stop BLE advertising  |
| `isAdvertising(): Boolean` | Check if advertising  |

---

## 4. Phone App - Streaming Activities

### 4.1 ModeSelectionActivity

Streaming mode selection Activity.

#### Package Path

```kotlin
com.rokid.stream.sender.streaming.ModeSelectionActivity
```

#### Features

- Select streaming direction (Phone → Glasses, Glasses → Phone)
- Select transport mode (BLE L2CAP, WiFi TCP)
- Permission request handling
- Navigate to corresponding streaming Activity

---

### 4.2 PhoneToGlassesActivity

Phone to glasses unidirectional streaming Activity.

### 4.3 GlassesToPhoneActivity

Glasses to phone unidirectional streaming Activity.

---

## 5. Phone App - Util Module

### 5.1 LocaleManager

Multi-language management class.

#### Package Path

```kotlin
com.rokid.stream.sender.util.LocaleManager
```

#### Methods

| Method                                          | Description          |
| ----------------------------------------------- | -------------------- |
| `applyLocale(context: Context): Context`        | Apply locale setting |
| `setLocale(context: Context, language: String)` | Set locale           |
| `getLocale(context: Context): String`           | Get current locale   |

---

### 5.2 LogManager

Log management class.

#### Package Path

```kotlin
com.rokid.stream.sender.util.LogManager
```

#### Methods

| Method                              | Description     |
| ----------------------------------- | --------------- |
| `log(tag: String, message: String)` | Record log      |
| `getLogs(): List<String>`           | Get all logs    |
| `exportLogs(): File`                | Export log file |
| `clearLogs()`                       | Clear logs      |

---

## 6. Glasses App - UI Module

### 6.1 GlassesScannerActivity

Glasses-side scanner Activity.

#### Package Path

```kotlin
com.rokid.stream.receiver.ui.GlassesScannerActivity
```

#### Constants

| Name                        | Value          | Description    |
| --------------------------- | -------------- | -------------- |
| `ROKID_STREAM_SERVICE_UUID` | `0000FFFF-...` | Service UUID   |
| `VIDEO_WIDTH`               | `240`          | Video width    |
| `VIDEO_HEIGHT`              | `240`          | Video height   |
| `MAX_FRAME_SIZE`            | `1,000,000`    | Max frame size |
| `SCAN_TIMEOUT_MS`           | `30000`        | Scan timeout   |

#### Features

- Scan available phone devices
- Display device list
- Connect to selected device
- Display received video stream

---

## 7. Enumerations

### 7.1 ConnectionMode

```kotlin
enum class ConnectionMode {
    L2CAP,     // BLE L2CAP connection
    WIFI,      // WiFi TCP connection
    ROKID_SDK  // Rokid CXR-M SDK connection
}
```

### 7.2 StreamDirection

```kotlin
enum class StreamDirection {
    PHONE_TO_GLASSES,   // Phone → Glasses
    GLASSES_TO_PHONE    // Glasses → Phone
}
```

### 7.3 TransportMode

```kotlin
enum class TransportMode {
    BLE_L2CAP,   // Standard BLE L2CAP
    ROKID_BLE,   // Rokid proprietary BLE
    WIFI_TCP     // WiFi TCP
}
```

---

## 8. Usage Examples

### 8.1 Establish BLE Connection and Stream

```kotlin
// Create connection manager
val connectionManager = ConnectionManager(context)

// Set callbacks
connectionManager.onConnected = { outputStream, inputStream ->
    // Connection successful, start streaming
    startStreaming(outputStream)
}

connectionManager.onError = { error ->
    Log.e(TAG, "Connection error: $error")
}

// Start scanning
connectionManager.startScan()

// Connect to discovered device
connectionManager.onScanResult = { device ->
    connectionManager.connect(device)
}
```

### 8.2 Using VideoEncoder

```kotlin
// Create encoder
val encoder = VideoEncoder(
    width = 240,
    height = 240,
    bitrate = 100_000,
    frameRate = 10
)

// Set encoding callback
encoder.onFrameEncoded = { frameData, isKeyFrame ->
    // Send encoded data
    connectionManager.sendFrame(frameData)
}

// Start encoder
encoder.start()

// Encode from camera callback
imageAnalyzer = ImageAnalysis.Analyzer { image ->
    encoder.encodeFrame(image)
    image.close()
}
```

### 8.3 Using VideoDecoder

```kotlin
// Create decoder
val decoder = VideoDecoder(width = 720, height = 720)

// Set output surface
decoder.setSurface(surfaceView.holder.surface)

// Start decoder
decoder.start(spsPpsData)

// Receive and decode data
thread {
    while (isRunning) {
        val frameData = connectionManager.receiveFrame()
        if (frameData != null) {
            decoder.decodeFrame(frameData)
        }
    }
}
```
