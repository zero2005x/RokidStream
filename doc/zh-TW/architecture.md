# 系統架構設計

本文檔詳細說明 RokidStream 專案的系統架構、模組設計與資料流程。

## 1. 系統總覽

RokidStream 是一套手機與 Rokid AR 眼鏡之間的即時視訊串流系統，採用客戶端-伺服器架構。

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

## 2. 模組架構

### 2.1 Phone App 模組結構

```
phone-app/
└── src/main/java/com/rokid/stream/sender/
    ├── MainActivity.kt              # 主入口（Legacy）
    ├── RokidSDKManager.kt           # Rokid CXR-M SDK 管理
    ├── WiFiStreamManager.kt         # WiFi TCP 串流管理
    ├── ble/
    │   └── BleAdvertiser.kt         # BLE 廣播器
    ├── core/
    │   ├── ConnectionManager.kt     # 統一連線管理
    │   ├── VideoEncoder.kt          # H.264 編碼器
    │   └── VideoDecoder.kt          # H.264 解碼器
    ├── streaming/
    │   ├── ModeSelectionActivity.kt # 模式選擇主頁
    │   ├── PhoneToGlassesActivity.kt# 手機→眼鏡串流
    │   └── GlassesToPhoneActivity.kt# 眼鏡→手機串流
    ├── ui/
    │   ├── SettingsActivity.kt      # 設定頁面
    │   ├── LanguageSelectionActivity.kt # 語言選擇
    │   ├── StreamingScreen.kt       # Compose 串流畫面
    │   ├── StreamingViewModel.kt    # 串流 ViewModel
    │   └── theme/                   # Material Theme
    └── util/
        ├── LocaleManager.kt         # 多語系管理
        └── LogManager.kt            # 日誌管理
```

### 2.2 Glasses App 模組結構

```
glasses-app/
└── src/main/java/com/rokid/stream/receiver/
    ├── MainActivity.kt              # 主入口（Legacy）
    ├── RokidSDKManager.kt           # Rokid CXR-M SDK 管理
    ├── WiFiStreamManager.kt         # WiFi TCP 串流管理
    ├── core/
    │   └── VideoEncoder.kt          # H.264 編碼器
    ├── ui/
    │   ├── GlassesScannerActivity.kt# 掃描器主頁
    │   ├── GlassesScannerScreen.kt  # Compose 掃描畫面
    │   ├── ReceiverScreen.kt        # Compose 接收畫面
    │   ├── ReceiverViewModel.kt     # 接收 ViewModel
    │   └── theme/                   # Material Theme
    ├── viewmodel/
    │   └── ...                      # ViewModel 類別
    └── util/
        ├── LocaleManager.kt         # 多語系管理
        └── LogManager.kt            # 日誌管理
```

## 3. 核心元件設計

### 3.1 ConnectionManager

統一的 BLE 連線管理器，負責：

- BLE 設備掃描
- GATT 連線建立
- PSM (Protocol/Service Multiplexer) 發現
- L2CAP 通道管理

```kotlin
class ConnectionManager(private val context: Context) {
    // BLE 服務 UUID
    val SERVICE_UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")

    // PSM 特徵 UUID (手機 → 眼鏡)
    val PSM_CHAR_UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")

    // 反向 PSM 特徵 UUID (眼鏡 → 手機)
    val REVERSE_PSM_CHAR_UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")

    fun startScan()           // 開始掃描
    fun stopScan()            // 停止掃描
    fun connect(device)       // 連線設備
    fun disconnect()          // 斷開連線
}
```

### 3.2 VideoEncoder

低延遲 H.264 視訊編碼器：

| 參數       | 預設值   | 說明                |
| ---------- | -------- | ------------------- |
| 解析度     | 240×240  | 適合 BLE L2CAP 頻寬 |
| 位元率     | 100 Kbps | BLE 可穩定傳輸      |
| 幀率       | 10 FPS   | 平衡延遲與流暢度    |
| Profile    | Baseline | 最大相容性          |
| 關鍵幀間隔 | 3 秒     | 減少頻寬佔用        |

```kotlin
class VideoEncoder(
    private val width: Int = 240,
    private val height: Int = 240,
    private val bitrate: Int = 100_000,
    private val frameRate: Int = 10
) {
    fun start(): Boolean                    // 啟動編碼器
    fun stop()                              // 停止編碼器
    fun encodeFrame(imageProxy: ImageProxy) // 編碼影格
    fun getEncodedFrame(): ByteArray?       // 取得編碼後資料
}
```

### 3.3 VideoDecoder

低延遲 H.264 視訊解碼器：

```kotlin
class VideoDecoder(
    private val width: Int = 720,
    private val height: Int = 720
) {
    fun setSurface(surface: Surface)        // 設定輸出表面
    fun start(configData: ByteArray?): Boolean // 啟動解碼器
    fun stop()                              // 停止解碼器
    fun decodeFrame(frameData: ByteArray)   // 解碼影格
}
```

### 3.4 WiFiStreamManager

WiFi TCP 串流管理器，支援：

- mDNS/NSD 服務發現
- TCP Socket 連線
- 資料傳輸

```kotlin
class WiFiStreamManager(private val context: Context) {
    // 服務發現
    const val SERVICE_TYPE = "_rokidstream._tcp."
    const val STREAM_PORT = 18800      // 手機 → 眼鏡
    const val REVERSE_PORT = 18801     // 眼鏡 → 手機

    // 幀類型
    const val FRAME_TYPE_VIDEO = 0x01
    const val FRAME_TYPE_CONTROL = 0x02
    const val FRAME_TYPE_HEARTBEAT = 0x03
}
```

### 3.5 RokidSDKManager

Rokid CXR-M SDK 封裝層，提供：

- SDK 初始化
- 設備發現與連線
- 訊息收發

## 4. 串流模式

### 4.1 手機 → 眼鏡 (PhoneToGlasses)

```
┌──────────┐    ┌───────────┐    ┌────────┐    ┌────────────┐
│ Camera   │───►│ Encoder   │───►│ Socket │───►│ Decoder    │
│ (Phone)  │    │ (H.264)   │    │ (BLE/  │    │ (Glasses)  │
│          │    │           │    │  WiFi) │    │            │
└──────────┘    └───────────┘    └────────┘    └────────────┘

                    Phone                          Glasses
```

### 4.2 眼鏡 → 手機 (GlassesToPhone)

```
┌──────────┐    ┌───────────┐    ┌────────┐    ┌────────────┐
│ Camera   │───►│ Encoder   │───►│ Socket │───►│ Decoder    │
│ (Glasses)│    │ (H.264)   │    │ (BLE/  │    │ (Phone)    │
│          │    │           │    │  WiFi) │    │            │
└──────────┘    └───────────┘    └────────┘    └────────────┘

                   Glasses                          Phone
```

## 5. UI 架構

專案採用 Jetpack Compose 與傳統 View 混合架構：

### 5.1 Activity 結構

```
ModeSelectionActivity (主入口)
    ├── PhoneToGlassesActivity
    ├── GlassesToPhoneActivity
    └── SettingsActivity
        └── LanguageSelectionActivity
```

### 5.2 Compose 元件

- `StreamingScreen` - 串流主畫面
- `GlassesScannerScreen` - 設備掃描畫面
- `ReceiverScreen` - 接收顯示畫面
- `LogManagerScreen` - 日誌檢視畫面

## 6. 相依套件

### 6.1 AndroidX

| 套件               | 版本       | 用途            |
| ------------------ | ---------- | --------------- |
| core-ktx           | 1.12.0     | Kotlin 擴展     |
| appcompat          | 1.6.1      | 向下相容        |
| lifecycle          | 2.7.0      | 生命週期管理    |
| camera             | 1.3.0      | CameraX 相機    |
| compose-bom        | 2024.02.00 | Jetpack Compose |
| navigation-compose | 2.7.7      | Compose 導航    |

### 6.2 第三方

| 套件      | 版本   | 用途        |
| --------- | ------ | ----------- |
| lz4-java  | 1.8.0  | 資料壓縮    |
| retrofit2 | 2.9.0  | 網路請求    |
| okhttp3   | 4.9.3  | HTTP 客戶端 |
| gson      | 2.10.1 | JSON 處理   |

### 6.3 Rokid SDK

| 套件     | 版本  | 用途           |
| -------- | ----- | -------------- |
| client-m | 1.0.1 | CXR-M 連線 SDK |

## 7. 建置配置

### 7.1 Gradle 版本

- AGP: 8.3.0
- Kotlin: 2.0.21
- Gradle: 8.x

### 7.2 目標版本

| 配置       | Phone App | Glasses App |
| ---------- | --------- | ----------- |
| compileSdk | 34        | 34          |
| minSdk     | 29        | 29          |
| targetSdk  | 34        | 32          |

## 8. 設計原則

1. **模組化**：核心元件可跨 Activity 重用
2. **低延遲**：編碼參數針對即時串流最佳化
3. **多傳輸層**：抽象化傳輸層，支援 BLE 與 WiFi
4. **響應式 UI**：使用 Compose 與 ViewModel 實現 MVVM
