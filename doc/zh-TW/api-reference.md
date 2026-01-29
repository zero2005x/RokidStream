# API 參考文檔

本文檔說明 RokidStream 專案的核心類別、介面與公開 API。

## 1. Phone App - Core 模組

### 1.1 ConnectionManager

統一的 BLE L2CAP 連線管理類別。

#### 套件路徑

```kotlin
com.rokid.stream.sender.core.ConnectionManager
```

#### 建構子

```kotlin
class ConnectionManager(private val context: Context)
```

#### 常數

| 名稱                    | 值                                     | 說明             |
| ----------------------- | -------------------------------------- | ---------------- |
| `SERVICE_UUID`          | `6e400001-b5a3-f393-e0a9-e50e24dcca9e` | BLE 服務 UUID    |
| `PSM_CHAR_UUID`         | `6e400002-b5a3-f393-e0a9-e50e24dcca9e` | PSM 特徵 UUID    |
| `REVERSE_PSM_CHAR_UUID` | `6e400003-b5a3-f393-e0a9-e50e24dcca9e` | 反向 PSM UUID    |
| `MAX_FRAME_SIZE`        | `1,000,000`                            | 最大幀大小 (1MB) |

#### 屬性

| 名稱          | 類型      | 說明                |
| ------------- | --------- | ------------------- |
| `isConnected` | `Boolean` | 是否已連線 (只讀)   |
| `isScanning`  | `Boolean` | 是否正在掃描 (只讀) |

#### 回呼設定

```kotlin
// 掃描結果回呼
var onScanResult: ((BluetoothDevice) -> Unit)?

// 連線成功回呼
var onConnected: ((OutputStream?, InputStream?) -> Unit)?

// 斷線回呼
var onDisconnected: (() -> Unit)?

// 錯誤回呼
var onError: ((String) -> Unit)?

// 日誌回呼
var onLog: ((String) -> Unit)?
```

#### 方法

| 方法                               | 說明           |
| ---------------------------------- | -------------- |
| `startScan()`                      | 開始 BLE 掃描  |
| `stopScan()`                       | 停止 BLE 掃描  |
| `connect(device: BluetoothDevice)` | 連線至指定設備 |
| `disconnect()`                     | 斷開連線       |
| `sendFrame(data: ByteArray)`       | 傳送視訊幀     |
| `receiveFrame(): ByteArray?`       | 接收視訊幀     |

---

### 1.2 VideoEncoder

H.264 視訊編碼器類別。

#### 套件路徑

```kotlin
com.rokid.stream.sender.core.VideoEncoder
```

#### 建構子

```kotlin
class VideoEncoder(
    private val width: Int = DEFAULT_WIDTH,      // 240
    private val height: Int = DEFAULT_HEIGHT,    // 240
    private val bitrate: Int = DEFAULT_BITRATE,  // 100,000
    private val frameRate: Int = DEFAULT_FRAME_RATE // 10
)
```

#### 常數

| 名稱                 | 值        | 說明                  |
| -------------------- | --------- | --------------------- |
| `DEFAULT_WIDTH`      | `240`     | 預設寬度              |
| `DEFAULT_HEIGHT`     | `240`     | 預設高度              |
| `DEFAULT_BITRATE`    | `100,000` | 預設位元率 (100 Kbps) |
| `DEFAULT_FRAME_RATE` | `10`      | 預設幀率 (10 FPS)     |
| `I_FRAME_INTERVAL`   | `3`       | 關鍵幀間隔 (秒)       |
| `ENCODER_TIMEOUT_US` | `1000`    | 編碼器超時 (微秒)     |
| `MAX_PENDING_FRAMES` | `30`      | 最大待處理幀數        |

#### 屬性

| 名稱         | 類型         | 說明                |
| ------------ | ------------ | ------------------- |
| `spsPpsData` | `ByteArray?` | SPS/PPS 資料 (只讀) |
| `isRunning`  | `Boolean`    | 是否正在運行 (只讀) |

#### 回呼設定

```kotlin
// 編碼完成回呼
var onFrameEncoded: ((ByteArray, Boolean) -> Unit)?
// 參數: frameData, isKeyFrame
```

#### 方法

| 方法                                         | 回傳值       | 說明           |
| -------------------------------------------- | ------------ | -------------- |
| `start(actualWidth: Int, actualHeight: Int)` | `Boolean`    | 啟動編碼器     |
| `stop()`                                     | `Unit`       | 停止編碼器     |
| `encodeFrame(image: ImageProxy)`             | `Unit`       | 編碼相機影格   |
| `getEncodedFrame()`                          | `ByteArray?` | 取得編碼後資料 |
| `requestKeyFrame()`                          | `Unit`       | 請求產生關鍵幀 |

---

### 1.3 VideoDecoder

H.264 視訊解碼器類別。

#### 套件路徑

```kotlin
com.rokid.stream.sender.core.VideoDecoder
```

#### 建構子

```kotlin
class VideoDecoder(
    private val width: Int = DEFAULT_WIDTH,   // 720
    private val height: Int = DEFAULT_HEIGHT  // 720
)
```

#### 常數

| 名稱                 | 值     | 說明              |
| -------------------- | ------ | ----------------- |
| `DEFAULT_WIDTH`      | `720`  | 預設寬度          |
| `DEFAULT_HEIGHT`     | `720`  | 預設高度          |
| `DECODER_TIMEOUT_US` | `1000` | 解碼器超時 (微秒) |
| `MAX_FRAME_QUEUE`    | `10`   | 最大幀佇列長度    |

#### 屬性

| 名稱              | 類型         | 說明                    |
| ----------------- | ------------ | ----------------------- |
| `codecConfigData` | `ByteArray?` | 編解碼器配置資料 (只讀) |
| `isRunning`       | `Boolean`    | 是否正在運行 (只讀)     |

#### 方法

| 方法                            | 回傳值    | 說明         |
| ------------------------------- | --------- | ------------ |
| `setSurface(surface: Surface)`  | `Unit`    | 設定輸出表面 |
| `start(configData: ByteArray?)` | `Boolean` | 啟動解碼器   |
| `stop()`                        | `Unit`    | 停止解碼器   |
| `decodeFrame(data: ByteArray)`  | `Unit`    | 解碼視訊幀   |
| `flush()`                       | `Unit`    | 清空解碼佇列 |

---

## 2. Phone App - WiFi 模組

### 2.1 WiFiStreamManager

WiFi TCP 串流管理類別。

#### 套件路徑

```kotlin
com.rokid.stream.sender.WiFiStreamManager
```

#### 建構子

```kotlin
class WiFiStreamManager(private val context: Context)
```

#### 常數

| 名稱                   | 值                   | 說明                   |
| ---------------------- | -------------------- | ---------------------- |
| `SERVICE_TYPE`         | `_rokidstream._tcp.` | mDNS 服務類型          |
| `SERVICE_NAME`         | `RokidStream`        | 服務名稱               |
| `STREAM_PORT`          | `18800`              | 串流連接埠 (手機→眼鏡) |
| `REVERSE_PORT`         | `18801`              | 反向連接埠 (眼鏡→手機) |
| `FRAME_TYPE_VIDEO`     | `0x01`               | 視訊幀類型             |
| `FRAME_TYPE_CONTROL`   | `0x02`               | 控制訊息類型           |
| `FRAME_TYPE_HEARTBEAT` | `0x03`               | 心跳訊息類型           |
| `CONNECT_TIMEOUT_MS`   | `10000`              | 連線超時 (毫秒)        |
| `READ_TIMEOUT_MS`      | `30000`              | 讀取超時 (毫秒)        |

#### 介面

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

#### 方法

| 方法                                                   | 說明           |
| ------------------------------------------------------ | -------------- |
| `setConnectionCallback(callback)`                      | 設定連線回呼   |
| `setStreamCallback(callback)`                          | 設定串流回呼   |
| `startDiscovery()`                                     | 開始服務發現   |
| `stopDiscovery()`                                      | 停止服務發現   |
| `connect(host: String, port: Int)`                     | 連線至指定主機 |
| `disconnect()`                                         | 斷開連線       |
| `sendVideoFrame(data: ByteArray, isKeyFrame: Boolean)` | 傳送視訊幀     |
| `sendControlMessage(message: String)`                  | 傳送控制訊息   |

---

### 2.2 RokidSDKManager

Rokid CXR-M SDK 封裝類別。

#### 套件路徑

```kotlin
com.rokid.stream.sender.RokidSDKManager
```

#### 建構子

```kotlin
class RokidSDKManager(private val context: Context)
```

#### 常數

| 名稱                 | 值  | 說明   |
| -------------------- | --- | ------ |
| `STATE_DISCONNECTED` | `0` | 未連線 |
| `STATE_SCANNING`     | `1` | 掃描中 |
| `STATE_CONNECTING`   | `2` | 連線中 |
| `STATE_CONNECTED`    | `3` | 已連線 |

#### 介面

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

#### 方法

| 方法                              | 回傳值    | 說明           |
| --------------------------------- | --------- | -------------- |
| `initialize()`                    | `Boolean` | 初始化 SDK     |
| `setConnectionCallback(callback)` | `Unit`    | 設定連線回呼   |
| `setMessageCallback(callback)`    | `Unit`    | 設定訊息回呼   |
| `startScan()`                     | `Unit`    | 開始掃描       |
| `stopScan()`                      | `Unit`    | 停止掃描       |
| `connect(deviceId: String)`       | `Unit`    | 連線至設備     |
| `disconnect()`                    | `Unit`    | 斷開連線       |
| `sendBytes(data: ByteArray)`      | `Boolean` | 傳送位元組資料 |
| `sendMap(data: Map<String, Any>)` | `Boolean` | 傳送 Map 資料  |
| `release()`                       | `Unit`    | 釋放資源       |

---

## 3. Phone App - BLE 模組

### 3.1 BleAdvertiser

BLE 廣播類別，用於讓眼鏡端發現手機。

#### 套件路徑

```kotlin
com.rokid.stream.sender.ble.BleAdvertiser
```

#### 建構子

```kotlin
class BleAdvertiser(private val context: Context)
```

#### 方法

| 方法                       | 說明             |
| -------------------------- | ---------------- |
| `startAdvertising()`       | 開始 BLE 廣播    |
| `stopAdvertising()`        | 停止 BLE 廣播    |
| `isAdvertising(): Boolean` | 檢查是否正在廣播 |

---

## 4. Phone App - Streaming Activity

### 4.1 ModeSelectionActivity

串流模式選擇 Activity。

#### 套件路徑

```kotlin
com.rokid.stream.sender.streaming.ModeSelectionActivity
```

#### 功能

- 選擇串流方向（手機→眼鏡、眼鏡→手機）
- 選擇傳輸模式（BLE L2CAP、WiFi TCP）
- 權限請求處理
- 導航至對應的串流 Activity

---

### 4.2 PhoneToGlassesActivity

手機到眼鏡單向串流 Activity。

### 4.3 GlassesToPhoneActivity

眼鏡到手機單向串流 Activity。

---

## 5. Phone App - Util 模組

### 5.1 LocaleManager

多語系管理類別。

#### 套件路徑

```kotlin
com.rokid.stream.sender.util.LocaleManager
```

#### 方法

| 方法                                            | 說明         |
| ----------------------------------------------- | ------------ |
| `applyLocale(context: Context): Context`        | 套用語系設定 |
| `setLocale(context: Context, language: String)` | 設定語系     |
| `getLocale(context: Context): String`           | 取得目前語系 |

---

### 5.2 LogManager

日誌管理類別。

#### 套件路徑

```kotlin
com.rokid.stream.sender.util.LogManager
```

#### 方法

| 方法                                | 說明         |
| ----------------------------------- | ------------ |
| `log(tag: String, message: String)` | 記錄日誌     |
| `getLogs(): List<String>`           | 取得所有日誌 |
| `exportLogs(): File`                | 匯出日誌檔案 |
| `clearLogs()`                       | 清除日誌     |

---

## 6. Glasses App - UI 模組

### 6.1 GlassesScannerActivity

眼鏡端掃描器 Activity。

#### 套件路徑

```kotlin
com.rokid.stream.receiver.ui.GlassesScannerActivity
```

#### 常數

| 名稱                        | 值             | 說明       |
| --------------------------- | -------------- | ---------- |
| `ROKID_STREAM_SERVICE_UUID` | `0000FFFF-...` | 服務 UUID  |
| `VIDEO_WIDTH`               | `240`          | 視訊寬度   |
| `VIDEO_HEIGHT`              | `240`          | 視訊高度   |
| `MAX_FRAME_SIZE`            | `1,000,000`    | 最大幀大小 |
| `SCAN_TIMEOUT_MS`           | `30000`        | 掃描超時   |

#### 功能

- 掃描可用的手機設備
- 顯示設備清單
- 連線選擇的設備
- 顯示接收的視訊串流

---

## 7. 列舉類型

### 7.1 ConnectionMode

```kotlin
enum class ConnectionMode {
    L2CAP,     // BLE L2CAP 連線
    WIFI,      // WiFi TCP 連線
    ROKID_SDK  // Rokid CXR-M SDK 連線
}
```

### 7.2 StreamDirection

```kotlin
enum class StreamDirection {
    PHONE_TO_GLASSES,   // 手機 → 眼鏡
    GLASSES_TO_PHONE    // 眼鏡 → 手機
}
```

### 7.3 TransportMode

```kotlin
enum class TransportMode {
    BLE_L2CAP,   // 標準 BLE L2CAP
    ROKID_BLE,   // Rokid 專用 BLE
    WIFI_TCP     // WiFi TCP
}
```

---

## 8. 使用範例

### 8.1 建立 BLE 連線並串流

```kotlin
// 建立連線管理器
val connectionManager = ConnectionManager(context)

// 設定回呼
connectionManager.onConnected = { outputStream, inputStream ->
    // 連線成功，開始串流
    startStreaming(outputStream)
}

connectionManager.onError = { error ->
    Log.e(TAG, "Connection error: $error")
}

// 開始掃描
connectionManager.startScan()

// 連線至找到的設備
connectionManager.onScanResult = { device ->
    connectionManager.connect(device)
}
```

### 8.2 使用 VideoEncoder 編碼

```kotlin
// 建立編碼器
val encoder = VideoEncoder(
    width = 240,
    height = 240,
    bitrate = 100_000,
    frameRate = 10
)

// 設定編碼回呼
encoder.onFrameEncoded = { frameData, isKeyFrame ->
    // 傳送編碼後的資料
    connectionManager.sendFrame(frameData)
}

// 啟動編碼器
encoder.start()

// 從相機回呼中編碼
imageAnalyzer = ImageAnalysis.Analyzer { image ->
    encoder.encodeFrame(image)
    image.close()
}
```

### 8.3 使用 VideoDecoder 解碼

```kotlin
// 建立解碼器
val decoder = VideoDecoder(width = 720, height = 720)

// 設定輸出表面
decoder.setSurface(surfaceView.holder.surface)

// 啟動解碼器
decoder.start(spsPpsData)

// 接收並解碼資料
thread {
    while (isRunning) {
        val frameData = connectionManager.receiveFrame()
        if (frameData != null) {
            decoder.decodeFrame(frameData)
        }
    }
}
```
