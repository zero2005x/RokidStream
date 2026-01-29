# 開發者指南

本指南協助新加入的 Android 開發者在 30 分鐘內熟悉 RokidStream 開發。

---

## 目錄

1. [開發環境設定](#1-開發環境設定)
2. [專案導覽](#2-專案導覽)
3. [常見開發情境](#3-常見開發情境)
4. [除錯技巧](#4-除錯技巧)
5. [程式碼風格與慣例](#5-程式碼風格與慣例)

---

## 1. 開發環境設定

### 必要工具

| 工具           | 版本        | 下載連結                                                      |
| -------------- | ----------- | ------------------------------------------------------------- |
| Android Studio | Arctic Fox+ | [developer.android.com](https://developer.android.com/studio) |
| JDK            | 17          | Android Studio 內建                                           |
| Git            | 最新版      | [git-scm.com](https://git-scm.com/)                           |

### 首次設定

```bash
# 1. Clone 專案
git clone https://github.com/your-org/RokidStream.git
cd RokidStream

# 2. 在 Android Studio 開啟
# File > Open > 選擇 RokidStream 資料夾

# 3. 等待 Gradle 同步（首次可能需 2-5 分鐘）

# 4. 建置確認設定正確
./gradlew assembleDebug
```

### 環境變數（選用）

```bash
# 如果使用 Rokid Maven 倉庫，確保設定憑證
# 在 ~/.gradle/gradle.properties 中：
rokidMavenUser=your_username
rokidMavenPassword=your_password
```

---

## 2. 專案導覽

### 檔案位置對照表

| 任務                  | 位置                                                           |
| --------------------- | -------------------------------------------------------------- |
| 新增畫面（手機）      | `phone-app/src/main/java/.../sender/ui/`                       |
| 新增 Activity（手機） | `phone-app/src/main/java/.../sender/streaming/`                |
| 修改 BLE 連線邏輯     | `phone-app/src/main/java/.../sender/core/ConnectionManager.kt` |
| 修改視訊編碼          | `phone-app/src/main/java/.../sender/core/VideoEncoder.kt`      |
| 修改視訊解碼          | `phone-app/src/main/java/.../sender/core/VideoDecoder.kt`      |
| 新增 WiFi 串流功能    | `phone-app/src/main/java/.../sender/WiFiStreamManager.kt`      |
| 修改眼鏡 UI           | `glasses-app/src/main/java/.../receiver/ui/`                   |
| 新增依賴套件          | `gradle/libs.versions.toml` 或模組的 `build.gradle.kts`        |
| 修改權限              | `phone-app/src/main/AndroidManifest.xml`                       |
| 新增字串資源          | `phone-app/src/main/res/values/strings.xml`                    |

### 關鍵檔案快速參照

```
phone-app/
├── src/main/
│   ├── AndroidManifest.xml          # 權限、Activity 宣告
│   ├── java/.../sender/
│   │   ├── streaming/
│   │   │   ├── ModeSelectionActivity.kt   # ⭐ 主入口
│   │   │   ├── PhoneToGlassesActivity.kt  # 手機→眼鏡串流
│   │   │   └── GlassesToPhoneActivity.kt  # 眼鏡→手機串流
│   │   ├── core/
│   │   │   ├── ConnectionManager.kt       # ⭐ BLE L2CAP 邏輯
│   │   │   ├── VideoEncoder.kt            # ⭐ H.264 編碼
│   │   │   └── VideoDecoder.kt            # H.264 解碼
│   │   └── WiFiStreamManager.kt           # ⭐ WiFi TCP 邏輯
│   └── res/
│       └── values/
│           └── strings.xml                # 字串資源
└── build.gradle.kts                       # 依賴套件
```

---

## 3. 常見開發情境

### 情境 1：新增 API 端點

如果需要與後端伺服器整合：

```kotlin
// 1. 新增 Retrofit 介面（若不存在則建立）
// phone-app/.../api/RokidApi.kt

interface RokidApi {
    @GET("devices")
    suspend fun getDevices(): Response<List<Device>>

    @POST("stream/start")
    suspend fun startStream(@Body request: StreamRequest): Response<StreamResponse>
}

// 2. 建立資料類別
// phone-app/.../model/Device.kt
data class Device(
    val id: String,
    val name: String,
    val status: String
)

// 3. 在 ViewModel 中使用
class StreamingViewModel : ViewModel() {
    private val api = Retrofit.Builder()
        .baseUrl("https://api.example.com/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(RokidApi::class.java)

    fun fetchDevices() {
        viewModelScope.launch {
            val response = api.getDevices()
            // 處理回應
        }
    }
}
```

### 情境 2：新增畫面（Jetpack Compose）

```kotlin
// 1. 建立 Composable
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

// 2. 建立 ViewModel
// phone-app/.../ui/DeviceListViewModel.kt

class DeviceListViewModel : ViewModel() {
    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()

    fun loadDevices() {
        viewModelScope.launch {
            // 載入裝置
        }
    }
}

// 3. 加入導航（如果使用 NavHost）
// 在 NavHost 設定中：
composable("device_list") {
    DeviceListScreen(
        onDeviceSelected = { device ->
            navController.navigate("streaming/${device.id}")
        }
    )
}
```

### 情境 3：新增 Activity

```kotlin
// 1. 建立 Activity 類別
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

// 2. 在 AndroidManifest.xml 中註冊
// phone-app/src/main/AndroidManifest.xml

<activity
    android:name=".streaming.NewFeatureActivity"
    android:exported="false"
    android:parentActivityName=".streaming.ModeSelectionActivity"
    android:screenOrientation="portrait" />

// 3. 導航至該 Activity
// 從 ModeSelectionActivity 或其他 Activity：
startActivity(Intent(this, NewFeatureActivity::class.java))
```

### 情境 4：新增權限

```xml
<!-- 1. 在 AndroidManifest.xml 中宣告 -->
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

```kotlin
// 2. 執行時請求（在 Activity 中）
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
        // 權限已授予，繼續執行
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
                // 權限被拒絕，顯示說明
                Toast.makeText(this, "需要錄音權限", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
```

### 情境 5：修改視訊編碼參數

```kotlin
// 編輯：phone-app/.../core/VideoEncoder.kt

class VideoEncoder(
    private val width: Int = 240,      // 在此修改解析度
    private val height: Int = 240,
    private val bitrate: Int = 100_000, // 修改位元率（bps）
    private val frameRate: Int = 10     // 修改 FPS
) {
    companion object {
        const val I_FRAME_INTERVAL = 3  // 關鍵幀間隔（秒）
    }

    // WiFi 模式下可使用更高品質：
    // width = 720, height = 720, bitrate = 2_000_000, frameRate = 30
}
```

### 情境 6：新增依賴套件

```kotlin
// 方法 A：加入版本目錄（建議）
// gradle/libs.versions.toml

[versions]
newlib = "1.0.0"

[libraries]
new-library = { group = "com.example", name = "library", version.ref = "newlib" }

// 然後在 build.gradle.kts 中：
dependencies {
    implementation(libs.new.library)
}

// 方法 B：直接加入依賴
// phone-app/build.gradle.kts

dependencies {
    implementation("com.example:library:1.0.0")
}
```

---

## 4. 除錯技巧

### Logcat 過濾器

```
# BLE 連線日誌
tag:ConnectionManager

# 視訊編碼日誌
tag:VideoEncoder

# WiFi 串流日誌
tag:WiFiStreamManager

# 所有 RokidStream 日誌
package:com.rokid.stream
```

### 常用除錯指令

```bash
# 查看已連接裝置
adb devices

# 安裝並執行（手機）
adb -s <PHONE_SERIAL> install -r phone-app/build/outputs/apk/debug/phone-app-debug.apk
adb -s <PHONE_SERIAL> shell am start -n com.rokid.stream.sender/.streaming.ModeSelectionActivity

# 即時查看日誌
adb logcat -s ConnectionManager:V VideoEncoder:V

# 清除 App 資料
adb shell pm clear com.rokid.stream.sender
```

### BLE 問題除錯

1. **啟用 Bluetooth HCI Snoop Log**：
   - 設定 > 開發者選項 > 啟用 Bluetooth HCI snoop log
   - 重現問題
   - 匯出日誌：`adb pull /data/misc/bluetooth/logs/btsnoop_hci.log`

2. **檢查 BLE 連線狀態**：
   ```kotlin
   // 在 ConnectionManager.kt 中加入日誌
   Log.d(TAG, "BLE State: ${bluetoothAdapter.state}")
   Log.d(TAG, "GATT State: ${gatt?.connectionState}")
   ```

### 視訊問題除錯

1. **檢查編碼器輸出**：

   ```kotlin
   // 在 VideoEncoder.kt 編碼後加入
   Log.d(TAG, "Encoded frame: ${outputBuffer.remaining()} bytes, keyframe: $isKeyFrame")
   ```

2. **驗證 SPS/PPS**：
   ```kotlin
   // 檢查是否有發送 codec config
   Log.d(TAG, "SPS/PPS data: ${spsPpsData?.size} bytes")
   ```

---

## 5. 程式碼風格與慣例

### Kotlin 風格

- 函式和屬性使用 `camelCase`
- 類別和介面使用 `PascalCase`
- 常數使用 `SCREAMING_SNAKE_CASE`
- 優先使用 `val` 而非 `var`
- DTO 使用 data class

### 檔案組織

```kotlin
// 類別中宣告的順序：
class MyClass {
    // 1. Companion object（常數、工廠方法）
    companion object {
        const val TAG = "MyClass"
    }

    // 2. 屬性
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    // 3. 初始化區塊
    init {
        // ...
    }

    // 4. 公開方法
    fun doSomething() {
        // ...
    }

    // 5. 私有方法
    private fun helperMethod() {
        // ...
    }

    // 6. 內部類別
    sealed class State {
        object Idle : State()
        data class Connected(val device: BluetoothDevice) : State()
    }
}
```

### 命名慣例

| 類型       | 慣例                   | 範例                            |
| ---------- | ---------------------- | ------------------------------- |
| Activity   | `*Activity`            | `ModeSelectionActivity`         |
| Fragment   | `*Fragment`            | `StreamingFragment`             |
| Composable | `*Screen` 或描述性名稱 | `StreamingScreen`, `DeviceItem` |
| ViewModel  | `*ViewModel`           | `StreamingViewModel`            |
| 介面       | 描述性名稱，無前綴     | `ConnectionCallback`            |
| 回呼       | `on*`                  | `onConnected`, `onError`        |

### 文件註解

````kotlin
/**
 * 管理 BLE L2CAP 視訊串流連線。
 *
 * 使用方式：
 * ```
 * val manager = ConnectionManager(context)
 * manager.onConnected = { output, input -> startStreaming() }
 * manager.startScan()
 * ```
 *
 * @param context Application 或 Activity context
 * @see VideoEncoder
 */
class ConnectionManager(private val context: Context) {
    // ...
}
````

---

## 快速參照卡

### 建置指令

| 指令                                 | 說明               |
| ------------------------------------ | ------------------ |
| `./gradlew assembleDebug`            | 建置所有 Debug APK |
| `./gradlew :phone-app:assembleDebug` | 只建置手機端 App   |
| `./gradlew clean`                    | 清除建置輸出       |
| `./gradlew test`                     | 執行單元測試       |

### ADB 指令

| 指令                                         | 說明               |
| -------------------------------------------- | ------------------ |
| `adb devices`                                | 列出已連接裝置     |
| `adb install <apk>`                          | 安裝 APK           |
| `adb logcat -s TAG:V`                        | 依 tag 過濾 logcat |
| `adb shell am start -n <package>/<activity>` | 啟動 Activity      |

### 關鍵類別

| 類別                     | 用途            |
| ------------------------ | --------------- |
| `ConnectionManager`      | BLE L2CAP 連線  |
| `VideoEncoder`           | H.264 編碼      |
| `VideoDecoder`           | H.264 解碼      |
| `WiFiStreamManager`      | WiFi TCP 串流   |
| `ModeSelectionActivity`  | 手機端 App 入口 |
| `GlassesScannerActivity` | 眼鏡端 App 入口 |
