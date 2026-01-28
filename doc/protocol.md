# Communication Protocol Specification

This document provides detailed specifications for the BLE L2CAP and WiFi TCP communication protocols used in the RokidStream system.

## 1. BLE L2CAP Protocol

### 1.1 Overview

BLE L2CAP (Logical Link Control and Adaptation Protocol) is a Connection-oriented Channel (CoC) introduced in Bluetooth 5.0, providing reliable data transmission.

### 1.2 Service Discovery

#### GATT Service Structure

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

### 1.3 Connection Flow

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

PSM is the identifier for L2CAP channels, similar to TCP port numbers.

| Channel         | Purpose               | PSM Source                           |
| --------------- | --------------------- | ------------------------------------ |
| Main Channel    | Phone → Glasses video | PSM_CHAR_UUID characteristic         |
| Reverse Channel | Glasses → Phone video | REVERSE_PSM_CHAR_UUID characteristic |

### 1.5 Frame Format

Each video frame is transmitted using the following format:

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

#### Field Description

| Field        | Offset | Size     | Description                       |
| ------------ | ------ | -------- | --------------------------------- |
| Frame Length | 0      | 4 bytes  | Payload length (excluding header) |
| Frame Type   | 4      | 1 byte   | Frame type (see table below)      |
| Reserved     | 5      | 3 bytes  | Reserved, set to 0x00             |
| Payload      | 8      | Variable | H.264 NAL unit data               |

#### Frame Types

| Value | Name              | Description                |
| ----- | ----------------- | -------------------------- |
| 0x01  | VIDEO_FRAME       | H.264 video frame          |
| 0x02  | CODEC_CONFIG      | SPS/PPS configuration data |
| 0x03  | KEY_FRAME_REQUEST | Request key frame          |
| 0x04  | HEARTBEAT         | Heartbeat message          |
| 0xFF  | DISCONNECT        | Disconnect notification    |

### 1.6 H.264 Video Frame Structure

Video frames contain complete H.264 NAL units:

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

#### NAL Unit Types

| Value | Name          | Description               |
| ----- | ------------- | ------------------------- |
| 1     | Non-IDR Slice | P/B frame slice           |
| 5     | IDR Slice     | I-frame (key frame) slice |
| 7     | SPS           | Sequence Parameter Set    |
| 8     | PPS           | Picture Parameter Set     |

### 1.7 SPS/PPS Handling

The first frame output by the encoder typically contains SPS and PPS, which must be saved and sent before decoding:

```kotlin
// Detect SPS/PPS
val nalType = (nalData[4].toInt() and 0x1F)
when (nalType) {
    7 -> saveSps(nalData)  // SPS
    8 -> savePps(nalData)  // PPS
    5 -> {                 // IDR (keyframe)
        // Send SPS/PPS before IDR
        sendSpsPps()
        sendFrame(nalData)
    }
    else -> sendFrame(nalData)
}
```

### 1.8 Flow Control

BLE L2CAP effective throughput is approximately 80-150 Kbps (using 2M PHY).

#### Recommended Parameters

| Parameter          | Recommended Value | Description                 |
| ------------------ | ----------------- | --------------------------- |
| Resolution         | 240×240           | Low resolution reduces data |
| Bitrate            | 100 Kbps          | Stable transmission limit   |
| Frame Rate         | 10 FPS            | ~100ms per frame            |
| Key Frame Interval | 3 seconds         | Reduces IDR frequency       |

---

## 2. WiFi TCP Protocol

### 2.1 Overview

WiFi TCP mode uses mDNS/NSD for service discovery and TCP Socket for video data transmission.

### 2.2 Service Discovery (mDNS/NSD)

#### Service Registration (Glasses Side)

```
Service Type: _rokidstream._tcp.
Service Name: RokidStream
Port: 18800 (Main Channel) / 18801 (Reverse Channel)
```

#### TXT Records

| Key     | Value       | Description        |
| ------- | ----------- | ------------------ |
| version | 1           | Protocol version   |
| device  | glasses     | Device type        |
| caps    | video,audio | Supported features |

### 2.3 Connection Flow

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

### 2.4 Frame Format

WiFi TCP uses a frame format similar to BLE L2CAP, but with added timestamp:

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

#### Field Description

| Field        | Offset | Size     | Description           |
| ------------ | ------ | -------- | --------------------- |
| Frame Length | 0      | 4 bytes  | Payload length        |
| Frame Type   | 4      | 1 byte   | Frame type            |
| Timestamp    | 5      | 8 bytes  | Millisecond timestamp |
| Flags        | 13     | 1 byte   | Flag bits             |
| Reserved     | 14     | 2 bytes  | Reserved              |
| Payload      | 16     | Variable | Data content          |

#### Flags Bit Definition

| Bit | Name             | Description      |
| --- | ---------------- | ---------------- |
| 0   | IS_KEY_FRAME     | Is key frame     |
| 1   | HAS_CODEC_CONFIG | Contains SPS/PPS |
| 2-7 | Reserved         | Reserved         |

#### Frame Types

| Value | Name      | Description                 |
| ----- | --------- | --------------------------- |
| 0x01  | VIDEO     | H.264 video frame           |
| 0x02  | CONTROL   | JSON control message        |
| 0x03  | HEARTBEAT | Heartbeat (every 5 seconds) |
| 0x04  | ACK       | Acknowledgment response     |

### 2.5 Control Message Format (JSON)

Control messages use JSON format:

```json
{
  "type": "command",
  "command": "request_keyframe",
  "timestamp": 1706438400000
}
```

#### Supported Commands

| Command            | Description       |
| ------------------ | ----------------- |
| `request_keyframe` | Request key frame |
| `set_bitrate`      | Adjust bitrate    |
| `set_framerate`    | Adjust frame rate |
| `set_resolution`   | Adjust resolution |
| `pause`            | Pause streaming   |
| `resume`           | Resume streaming  |
| `disconnect`       | Disconnect        |

### 2.6 Heartbeat Mechanism

To maintain connection activity, both sides send heartbeat messages every 5 seconds:

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

If no heartbeat is received for 30 consecutive seconds, the connection is considered disconnected.

### 2.7 Recommended Parameters (WiFi Mode)

| Parameter  | Recommended Value | Description     |
| ---------- | ----------------- | --------------- |
| Resolution | 720×720           | High resolution |
| Bitrate    | 2 Mbps            | High quality    |
| Frame Rate | 30 FPS            | Smooth          |
| TCP Buffer | 256 KB            | Buffer size     |

---

## 3. Error Handling

### 3.1 BLE L2CAP Errors

| Error Code      | Description                        | Handling             |
| --------------- | ---------------------------------- | -------------------- |
| GATT_FAILURE    | GATT operation failed              | Reconnect            |
| L2CAP_FAILED    | L2CAP channel establishment failed | Retry or switch mode |
| CONNECTION_LOST | Connection lost                    | Attempt reconnection |

### 3.2 WiFi TCP Errors

| Error Code        | Description        | Handling                   |
| ----------------- | ------------------ | -------------------------- |
| SERVICE_NOT_FOUND | Service not found  | Confirm glasses is started |
| CONNECT_TIMEOUT   | Connection timeout | Check network and retry    |
| SOCKET_CLOSED     | Socket closed      | Reconnect                  |

---

## 4. Security Considerations

### 4.1 BLE Security

- Use BLE 4.2+ LE Secure Connections
- L2CAP CoC supports encrypted connections
- Pairing authentication recommended

### 4.2 WiFi Security

- Ensure use within trusted LAN environment
- End-to-end encryption not implemented (development/testing only)
- TLS layer recommended for production environments

---

## 5. Performance Optimization Recommendations

### 5.1 BLE Mode

1. Use 2M PHY to increase throughput
2. Adjust MTU to maximum value (typically 512)
3. Reduce key frame frequency
4. Use Baseline Profile to reduce encoding complexity

### 5.2 WiFi Mode

1. Use TCP_NODELAY to reduce latency
2. Adjust Socket buffer size
3. Consider using UDP for further latency reduction
4. Implement Adaptive Bitrate Control (ABR)

---

## 6. Protocol Version

| Version | Date       | Changes         |
| ------- | ---------- | --------------- |
| 1.0     | 2026-01-28 | Initial version |
