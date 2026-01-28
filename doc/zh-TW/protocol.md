# 通訊協定規範

本文檔詳細說明 RokidStream 系統中 BLE L2CAP 與 WiFi TCP 兩種通訊協定的規範。

## 1. BLE L2CAP 協定

### 1.1 概述

BLE L2CAP (Logical Link Control and Adaptation Protocol) 是藍牙 5.0 引入的面向連線的通道 (CoC)，提供可靠的資料傳輸。

### 1.2 服務發現

#### GATT 服務結構

```
Service: RokidStream Service
├── UUID: 6e400001-b5a3-f393-e0a9-e50e24dcca9e
│
├── Characteristic: PSM Value (Phone → Glasses)
│   ├── UUID: 6e400002-b5a3-f393-e0a9-e50e24dcca9e
│   ├── Properties: Read
│   └── Value: 2-byte Little-Endian PSM number
│
└── Characteristic: Reverse PSM Value (Glasses → Phone)
    ├── UUID: 6e400003-b5a3-f393-e0a9-e50e24dcca9e
    ├── Properties: Read
    └── Value: 2-byte Little-Endian PSM number
```

### 1.3 連線流程

```
    Phone (Sender)                     Glasses (Receiver)
         │                                    │
         │  1. BLE Scan                       │
         ├───────────────────────────────────►│
         │                                    │
         │  2. Advertise Response             │
         │◄───────────────────────────────────┤
         │                                    │
         │  3. GATT Connect                   │
         ├───────────────────────────────────►│
         │                                    │
         │  4. Service Discovery              │
         ├───────────────────────────────────►│
         │                                    │
         │  5. Read PSM Characteristic        │
         ├───────────────────────────────────►│
         │                                    │
         │  6. PSM Value (e.g., 0x0025)       │
         │◄───────────────────────────────────┤
         │                                    │
         │  7. L2CAP CoC Connect to PSM       │
         ├───────────────────────────────────►│
         │                                    │
         │  8. L2CAP Channel Established      │
         │◄───────────────────────────────────┤
         │                                    │
         │  9. Video Stream Data              │
         ├═══════════════════════════════════►│
         │                                    │
```

### 1.4 PSM (Protocol/Service Multiplexer)

PSM 是 L2CAP 通道的識別號，類似於 TCP 的連接埠號。

| 通道     | 用途            | PSM 來源                     |
| -------- | --------------- | ---------------------------- |
| 主通道   | 手機 → 眼鏡視訊 | PSM_CHAR_UUID 特徵值         |
| 反向通道 | 眼鏡 → 手機視訊 | REVERSE_PSM_CHAR_UUID 特徵值 |

### 1.5 幀格式

每個視訊幀使用以下格式傳輸：

```
┌─────────────────────────────────────────────────────┐
│                    Frame Header (8 bytes)            │
├──────────────┬───────────────┬──────────────────────┤
│ Frame Length │ Frame Type    │ Reserved              │
│ (4 bytes)    │ (1 byte)      │ (3 bytes)            │
│ Little-Endian│               │                       │
├──────────────┴───────────────┴──────────────────────┤
│                                                      │
│                    Payload                           │
│               (Frame Length bytes)                   │
│                                                      │
└─────────────────────────────────────────────────────┘
```

#### 欄位說明

| 欄位         | 偏移 | 大小     | 說明                     |
| ------------ | ---- | -------- | ------------------------ |
| Frame Length | 0    | 4 bytes  | 有效負載長度（不含標頭） |
| Frame Type   | 4    | 1 byte   | 幀類型（見下表）         |
| Reserved     | 5    | 3 bytes  | 保留，設為 0x00          |
| Payload      | 8    | Variable | H.264 NAL 單元資料       |

#### 幀類型

| 值   | 名稱              | 說明             |
| ---- | ----------------- | ---------------- |
| 0x01 | VIDEO_FRAME       | H.264 視訊幀     |
| 0x02 | CODEC_CONFIG      | SPS/PPS 配置資料 |
| 0x03 | KEY_FRAME_REQUEST | 請求產生關鍵幀   |
| 0x04 | HEARTBEAT         | 心跳訊息         |
| 0xFF | DISCONNECT        | 斷線通知         |

### 1.6 H.264 視訊幀結構

視訊幀包含完整的 H.264 NAL 單元：

```
┌────────────────────────────────────────────────────┐
│ NAL Start Code (4 bytes): 0x00 0x00 0x00 0x01      │
├────────────────────────────────────────────────────┤
│ NAL Unit Header (1 byte)                           │
│ ├── forbidden_zero_bit: 1 bit (must be 0)          │
│ ├── nal_ref_idc: 2 bits (reference importance)     │
│ └── nal_unit_type: 5 bits (see table below)        │
├────────────────────────────────────────────────────┤
│ NAL Unit Payload (Variable)                         │
│ └── RBSP (Raw Byte Sequence Payload)                │
└────────────────────────────────────────────────────┘
```

#### NAL 單元類型

| 值  | 名稱          | 說明               |
| --- | ------------- | ------------------ |
| 1   | Non-IDR Slice | P/B 幀切片         |
| 5   | IDR Slice     | I 幀（關鍵幀）切片 |
| 7   | SPS           | 序列參數集         |
| 8   | PPS           | 圖像參數集         |

### 1.7 SPS/PPS 處理

編碼器輸出的第一個幀通常包含 SPS 和 PPS，必須保存並在解碼前發送：

```kotlin
// 檢測 SPS/PPS
val nalType = (nalData[4].toInt() and 0x1F)
when (nalType) {
    7 -> saveSps(nalData)  // SPS
    8 -> savePps(nalData)  // PPS
    5 -> {                 // IDR (keyframe)
        // 在 IDR 前先發送 SPS/PPS
        sendSpsPps()
        sendFrame(nalData)
    }
    else -> sendFrame(nalData)
}
```

### 1.8 流量控制

BLE L2CAP 的有效吞吐量約為 80-150 Kbps（使用 2M PHY）。

#### 建議參數

| 參數       | 建議值   | 說明               |
| ---------- | -------- | ------------------ |
| 解析度     | 240×240  | 低解析度減少資料量 |
| 位元率     | 100 Kbps | 穩定傳輸上限       |
| 幀率       | 10 FPS   | 每幀約 100ms       |
| 關鍵幀間隔 | 3 秒     | 減少 IDR 頻率      |

---

## 2. WiFi TCP 協定

### 2.1 概述

WiFi TCP 模式使用 mDNS/NSD 進行服務發現，並透過 TCP Socket 傳輸視訊資料。

### 2.2 服務發現 (mDNS/NSD)

#### 服務註冊（眼鏡端）

```
Service Type: _rokidstream._tcp.
Service Name: RokidStream
Port: 18800 (主通道) / 18801 (反向通道)
```

#### TXT Records

| 鍵      | 值          | 說明     |
| ------- | ----------- | -------- |
| version | 1           | 協定版本 |
| device  | glasses     | 設備類型 |
| caps    | video,audio | 支援功能 |

### 2.3 連線流程

```
    Phone (Sender)                     Glasses (Receiver)
         │                                    │
         │                  1. Register NSD Service
         │                    ◄───────────────┤
         │                                    │
         │  2. Start NSD Discovery            │
         ├───────────────────────────────────►│
         │                                    │
         │  3. Service Found Callback          │
         │◄───────────────────────────────────┤
         │                                    │
         │  4. Resolve Service (get IP/Port)  │
         ├───────────────────────────────────►│
         │                                    │
         │  5. TCP Connect to Port 18800      │
         ├───────────────────────────────────►│
         │                                    │
         │  6. Connection Accepted             │
         │◄───────────────────────────────────┤
         │                                    │
         │  7. Video Stream (TCP)             │
         ├═══════════════════════════════════►│
         │                                    │
         │  8. (Optional) Reverse Stream      │
         │  9. TCP Connect to Port 18801      │
         │◄═══════════════════════════════════┤
         │                                    │
```

### 2.4 幀格式

WiFi TCP 使用與 BLE L2CAP 相似的幀格式，但增加了時間戳記：

```
┌─────────────────────────────────────────────────────┐
│                   Frame Header (16 bytes)            │
├──────────────┬───────────────┬──────────────────────┤
│ Frame Length │ Frame Type    │ Timestamp            │
│ (4 bytes)    │ (1 byte)      │ (8 bytes)           │
│ Little-Endian│               │ Milliseconds         │
├──────────────┼───────────────┼──────────────────────┤
│ Flags        │ Reserved      │                      │
│ (1 byte)     │ (2 bytes)     │                      │
├──────────────┴───────────────┴──────────────────────┤
│                                                      │
│                    Payload                           │
│               (Frame Length bytes)                   │
│                                                      │
└─────────────────────────────────────────────────────┘
```

#### 欄位說明

| 欄位         | 偏移 | 大小     | 說明         |
| ------------ | ---- | -------- | ------------ |
| Frame Length | 0    | 4 bytes  | 有效負載長度 |
| Frame Type   | 4    | 1 byte   | 幀類型       |
| Timestamp    | 5    | 8 bytes  | 毫秒時間戳記 |
| Flags        | 13   | 1 byte   | 旗標位元     |
| Reserved     | 14   | 2 bytes  | 保留         |
| Payload      | 16   | Variable | 資料內容     |

#### Flags 位元定義

| 位元 | 名稱             | 說明             |
| ---- | ---------------- | ---------------- |
| 0    | IS_KEY_FRAME     | 是否為關鍵幀     |
| 1    | HAS_CODEC_CONFIG | 是否包含 SPS/PPS |
| 2-7  | Reserved         | 保留             |

#### 幀類型

| 值   | 名稱      | 說明            |
| ---- | --------- | --------------- |
| 0x01 | VIDEO     | H.264 視訊幀    |
| 0x02 | CONTROL   | JSON 控制訊息   |
| 0x03 | HEARTBEAT | 心跳（每 5 秒） |
| 0x04 | ACK       | 確認回應        |

### 2.5 控制訊息格式 (JSON)

控制訊息使用 JSON 格式：

```json
{
  "type": "command",
  "command": "request_keyframe",
  "timestamp": 1706438400000
}
```

#### 支援的命令

| 命令               | 說明           |
| ------------------ | -------------- |
| `request_keyframe` | 請求產生關鍵幀 |
| `set_bitrate`      | 調整位元率     |
| `set_framerate`    | 調整幀率       |
| `set_resolution`   | 調整解析度     |
| `pause`            | 暫停串流       |
| `resume`           | 恢復串流       |
| `disconnect`       | 斷開連線       |

### 2.6 心跳機制

為維持連線活性，雙方每 5 秒發送心跳訊息：

```
Phone                                   Glasses
  │                                        │
  │──── Heartbeat (0x03) ─────────────────►│
  │                                        │
  │◄──── Heartbeat (0x03) ────────────────│
  │                                        │
  │  (5 seconds later)                     │
  │                                        │
  │──── Heartbeat (0x03) ─────────────────►│
  │                                        │
```

若連續 30 秒未收到心跳，視為連線中斷。

### 2.7 建議參數（WiFi 模式）

| 參數       | 建議值  | 說明       |
| ---------- | ------- | ---------- |
| 解析度     | 720×720 | 高解析度   |
| 位元率     | 2 Mbps  | 高品質     |
| 幀率       | 30 FPS  | 流暢       |
| TCP Buffer | 256 KB  | 緩衝區大小 |

---

## 3. 錯誤處理

### 3.1 BLE L2CAP 錯誤

| 錯誤代碼        | 說明               | 處理方式       |
| --------------- | ------------------ | -------------- |
| GATT_FAILURE    | GATT 操作失敗      | 重新連線       |
| L2CAP_FAILED    | L2CAP 通道建立失敗 | 重試或切換模式 |
| CONNECTION_LOST | 連線中斷           | 嘗試重新連線   |

### 3.2 WiFi TCP 錯誤

| 錯誤代碼          | 說明        | 處理方式         |
| ----------------- | ----------- | ---------------- |
| SERVICE_NOT_FOUND | 服務未發現  | 確認眼鏡端已啟動 |
| CONNECT_TIMEOUT   | 連線超時    | 檢查網路並重試   |
| SOCKET_CLOSED     | Socket 關閉 | 重新連線         |

---

## 4. 安全性考量

### 4.1 BLE 安全性

- 使用 BLE 4.2+ 的 LE Secure Connections
- L2CAP CoC 支援加密連線
- 建議啟用配對認證

### 4.2 WiFi 安全性

- 確保在信任的區域網路環境使用
- 未實作端對端加密（僅限開發/測試）
- 生產環境建議加入 TLS 層

---

## 5. 效能最佳化建議

### 5.1 BLE 模式

1. 使用 2M PHY 提高吞吐量
2. 調整 MTU 至最大值（通常 512）
3. 減少關鍵幀頻率
4. 使用 Baseline Profile 減少編碼複雜度

### 5.2 WiFi 模式

1. 使用 TCP_NODELAY 減少延遲
2. 調整 Socket 緩衝區大小
3. 考慮使用 UDP 進一步降低延遲
4. 實作自適應位元率控制 (ABR)

---

## 6. 協定版本

| 版本 | 日期       | 變更說明 |
| ---- | ---------- | -------- |
| 1.0  | 2026-01-28 | 初始版本 |
