# RokidStream

<p align="center">
  <strong>手機與 Rokid AR 眼鏡之間的即時視訊串流應用</strong>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-brightgreen.svg" alt="Platform">
  <img src="https://img.shields.io/badge/Kotlin-2.0.21-blueviolet.svg" alt="Kotlin">
  <img src="https://img.shields.io/badge/Min%20SDK-29-blue.svg" alt="Min SDK">
  <img src="https://img.shields.io/badge/Target%20SDK-34-blue.svg" alt="Target SDK">
  <img src="https://img.shields.io/badge/License-MIT-yellow.svg" alt="License">
</p>

<p align="center">
  <a href="../../README.md">English</a>
</p>

---

## 背景與目的

RokidStream 實現 Android 手機與 Rokid AR 眼鏡之間的**即時 H.264 視訊串流**，透過 BLE L2CAP 或 WiFi TCP 傳輸。專為低延遲 AR 應用設計，可將手機相機畫面顯示在眼鏡上，或將眼鏡相機畫面傳回手機。

### 範圍定義

| 涵蓋範圍                  | 不涵蓋範圍        |
| ------------------------- | ----------------- |
| 手機 ↔ 眼鏡視訊串流       | 音訊串流          |
| BLE L2CAP / WiFi TCP 傳輸 | 雲端中繼 / WebRTC |
| H.264 Baseline 編碼       | HEVC / VP9 編碼器 |
| Rokid AR 眼鏡 (CXR-M SDK) | 其他品牌 AR 眼鏡  |

---

## ⚡ 快速開始（5 分鐘）

### 環境需求

| 工具           | 版本        | 檢查指令        |
| -------------- | ----------- | --------------- |
| Android Studio | Arctic Fox+ | `Help > About`  |
| JDK            | 17+         | `java -version` |
| Android SDK    | API 29+     | SDK Manager     |

### 建置與執行

```bash
# 1. Clone 專案
git clone https://github.com/your-org/RokidStream.git
cd RokidStream

# 2. 建置兩個 App (Debug)
./gradlew assembleDebug

# 3. 安裝手機端 App（透過 USB 連接手機）
adb -s <PHONE_SERIAL> install phone-app/build/outputs/apk/debug/phone-app-debug.apk

# 4. 安裝眼鏡端 App（透過 USB 連接眼鏡）
adb -s <GLASSES_SERIAL> install glasses-app/build/outputs/apk/debug/glasses-app-debug.apk
```

> **提示**：使用 `adb devices` 列出已連接裝置並取得序號。

### 首次執行

1. **手機端 App**：啟動 → 選擇「手機 → 眼鏡」→ 選擇「BLE L2CAP」→ 點擊連接
2. **眼鏡端 App**：啟動 → 自動掃描 → 從清單選擇手機
3. 連線成功後視訊串流自動開始

---

## 🏗️ 專案結構

```
RokidStream/
├── phone-app/                    # 手機端應用 (Sender)
│   └── src/main/java/.../sender/
│       ├── MainActivity.kt       # 舊版入口
│       ├── streaming/            # 串流 Activity
│       ├── core/                 # ConnectionManager, VideoEncoder/Decoder
│       ├── ble/                  # BLE 廣播器
│       ├── ui/                   # Compose 畫面、設定
│       └── util/                 # 語系、日誌
├── glasses-app/                  # 眼鏡端應用 (Receiver)
│   └── src/main/java/.../receiver/
│       ├── MainActivity.kt       # 舊版入口
│       ├── ui/                   # GlassesScannerActivity, Compose 畫面
│       ├── core/                 # VideoEncoder
│       └── util/                 # 語系、日誌
├── sender/                       # 共用 sender 模組
├── receiver/                     # 共用 receiver 模組
├── gradle/
│   └── libs.versions.toml        # 版本目錄
└── doc/                          # 文檔
```

### 模組概覽

| 模組          | 套件名稱                    | 說明                                                |
| ------------- | --------------------------- | --------------------------------------------------- |
| `phone-app`   | `com.rokid.stream.sender`   | 手機端主程式，可發送或接收視訊                      |
| `glasses-app` | `com.rokid.stream.receiver` | 眼鏡端程式，主要接收視訊（targetSdk=32 以相容眼鏡） |

### 核心元件

| 類別                     | 位置                       | 用途                                  |
| ------------------------ | -------------------------- | ------------------------------------- |
| `ConnectionManager`      | `phone-app/.../core/`      | BLE L2CAP 掃描、連線、I/O             |
| `VideoEncoder`           | `phone-app/.../core/`      | H.264 編碼（240×240, 100Kbps, 10FPS） |
| `VideoDecoder`           | `phone-app/.../core/`      | H.264 解碼至 Surface                  |
| `WiFiStreamManager`      | `phone-app/`               | mDNS 發現 + TCP 串流                  |
| `ModeSelectionActivity`  | `phone-app/.../streaming/` | 主入口，模式選擇                      |
| `GlassesScannerActivity` | `glasses-app/.../ui/`      | 眼鏡主入口，裝置掃描                  |

---

## 🔨 建置設定

### Gradle 版本

| 工具   | 版本   |
| ------ | ------ |
| AGP    | 8.3.0  |
| Kotlin | 2.0.21 |
| Gradle | 8.x    |
| JDK    | 17     |

### SDK 設定

| 設定          | phone-app | glasses-app |
| ------------- | --------- | ----------- |
| `compileSdk`  | 34        | 34          |
| `minSdk`      | 29        | 29          |
| `targetSdk`   | 34        | 32          |
| `versionCode` | 1         | 1           |

### 建置指令

```bash
# Debug 建置
./gradlew :phone-app:assembleDebug
./gradlew :glasses-app:assembleDebug

# Release 建置
./gradlew :phone-app:assembleRelease
./gradlew :glasses-app:assembleRelease

# 建置全部
./gradlew assembleDebug

# 清除後建置
./gradlew clean assembleDebug
```

### Build Types

| 類型      | Minify | ProGuard     | 說明           |
| --------- | ------ | ------------ | -------------- |
| `debug`   | ❌     | ❌           | 開發用，可除錯 |
| `release` | ❌     | 已設定但停用 | 正式版         |

> **注意**：ProGuard 規則在 `proguard-rules.pro` 中設定，但 `isMinifyEnabled = false`。

---

## 📱 必要權限

在 `AndroidManifest.xml` 中宣告：

| 權限                   | 用途                  | 執行時請求      |
| ---------------------- | --------------------- | --------------- |
| `CAMERA`               | 視訊擷取              | ✅ 是           |
| `BLUETOOTH_SCAN`       | 裝置搜尋              | ✅ 是 (API 31+) |
| `BLUETOOTH_CONNECT`    | BLE 連線              | ✅ 是 (API 31+) |
| `BLUETOOTH_ADVERTISE`  | BLE 廣播              | ✅ 是 (API 31+) |
| `ACCESS_FINE_LOCATION` | BLE/WiFi 掃描         | ✅ 是           |
| `NEARBY_WIFI_DEVICES`  | WiFi Direct (API 33+) | ✅ 是           |
| `INTERNET`             | 網路存取              | ❌ 否           |

### 新增權限

1. 加入 `AndroidManifest.xml`：

   ```xml
   <uses-permission android:name="android.permission.NEW_PERMISSION" />
   ```

2. 在 Activity 中執行時請求：
   ```kotlin
   // 參考 ModeSelectionActivity.kt 的權限請求模式
   ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.NEW_PERMISSION), REQUEST_CODE)
   ```

---

## 🧪 測試

### 單元測試

```bash
# 執行所有單元測試
./gradlew test

# 執行特定模組測試
./gradlew :phone-app:testDebugUnitTest
./gradlew :glasses-app:testDebugUnitTest
```

### 儀表測試

```bash
# 執行儀表測試（需連接裝置）
./gradlew connectedAndroidTest

# 特定模組
./gradlew :phone-app:connectedDebugAndroidTest
```

### 手動測試清單

- [ ] BLE L2CAP：手機 → 眼鏡串流
- [ ] BLE L2CAP：眼鏡 → 手機串流
- [ ] WiFi TCP：兩種模式
- [ ] 斷線後重連
- [ ] App 背景/前景切換
- [ ] 權限拒絕處理

---

## 🛠️ 常見開發任務

### 新增畫面（Compose）

1. 在 `ui/` 資料夾建立 Composable：

   ```kotlin
   // phone-app/.../ui/NewScreen.kt
   @Composable
   fun NewScreen(viewModel: NewViewModel = viewModel()) {
       // UI 程式碼
   }
   ```

2. 建立 ViewModel：

   ```kotlin
   // phone-app/.../ui/NewViewModel.kt
   class NewViewModel : ViewModel() {
       // 狀態與邏輯
   }
   ```

3. 在 Activity 或 NavHost 中加入導航

### 新增 Activity

1. 在適當套件中建立 Activity 類別
2. 在 `AndroidManifest.xml` 中註冊：
   ```xml
   <activity
       android:name=".package.NewActivity"
       android:exported="false"
       android:parentActivityName=".streaming.ModeSelectionActivity" />
   ```

### 修改視訊參數

編輯 `VideoEncoder.kt` 中的常數：

```kotlin
// phone-app/.../core/VideoEncoder.kt
const val DEFAULT_WIDTH = 240       // 解析度
const val DEFAULT_HEIGHT = 240
const val DEFAULT_BITRATE = 100_000 // 100 Kbps
const val DEFAULT_FRAME_RATE = 10   // 10 FPS
const val I_FRAME_INTERVAL = 3      // 每 3 秒一個關鍵幀
```

---

## ❓ FAQ 與疑難排解

### 建置問題

<details>
<summary><b>Gradle 同步失敗：Could not resolve com.rokid.cxr:client-m</b></summary>

確保有 Rokid Maven 倉庫的存取權限。檢查 `settings.gradle.kts` 中的倉庫設定：

```kotlin
maven { url = uri("https://maven.rokid.com/repository/...") }
```

</details>

<details>
<summary><b>JDK 版本不符錯誤</b></summary>

專案需要 JDK 17。在 Android Studio 中：

- `File > Settings > Build > Gradle > Gradle JDK` → 選擇 JDK 17
</details>

### 執行時問題

<details>
<summary><b>BLE 掃描找不到裝置</b></summary>

1. 確認已授予位置權限
2. 確認藍牙已開啟
3. 眼鏡端 App 必須正在執行並廣播
4. 檢查眼鏡是否已連接到其他手機
</details>

<details>
<summary><b>視訊幾秒後凍結</b></summary>

1. BLE 頻寬有限（約 100Kbps）- 低解析度是正常的
2. 檢查 `KEY_FRAME_REQUEST` 處理
3. 嘗試 WiFi TCP 模式以獲得更高頻寬
</details>

<details>
<summary><b>Android 12+ 上 L2CAP 連線失敗</b></summary>

確認已授予 `BLUETOOTH_CONNECT` 權限（API 31+ 必需）。

</details>

### ProGuard 問題

<details>
<summary><b>Release 版當掉但 Debug 版正常</b></summary>

在 `proguard-rules.pro` 中加入保留規則：

```proguard
-keep class com.rokid.** { *; }
-keep class com.squareup.** { *; }
```

</details>

---

## 📚 詳細文檔

| 文檔                             | 說明                           |
| -------------------------------- | ------------------------------ |
| [架構設計](architecture.md)      | 系統設計、模組結構、資料流     |
| [API 參考](api-reference.md)     | 核心類別、方法、回呼           |
| [通訊協定](protocol.md)          | BLE L2CAP / WiFi TCP 幀格式    |
| [開發者指南](developer-guide.md) | 常見開發任務、除錯、程式碼風格 |

### English Documentation

- [README (English)](../../README.md)
- [Architecture](../architecture.md)
- [API Reference](../api-reference.md)
- [Protocol](../protocol.md)
- [Developer Guide](../developer-guide.md)

---

## 📄 授權條款

本專案採用 [MIT License](../../LICENSE) 授權。

## 🤝 貢獻指南

1. Fork 本專案
2. 建立功能分支 (`git checkout -b feature/amazing-feature`)
3. 提交變更 (`git commit -m 'Add amazing feature'`)
4. 推送至分支 (`git push origin feature/amazing-feature`)
5. 開啟 Pull Request
