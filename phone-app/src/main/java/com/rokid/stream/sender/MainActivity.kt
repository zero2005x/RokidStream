package com.rokid.stream.sender

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.RadioGroup
import com.rokid.stream.sender.ble.BleAdvertiser
import com.rokid.stream.sender.util.LocaleManager

/**
 * MainActivity - Legacy Sender application for streaming camera video
 * 
 * This app supports two connection modes:
 * 1. BLE L2CAP - Direct Bluetooth Low Energy connection
 * 2. Rokid SDK (ARTC) - Wi-Fi based connection using Rokid CXR-M SDK
 * 
 * Features:
 * - Captures camera frames, encodes them as H.264 video, and transmits to receiver (glasses)
 * - Receives H.264 video from receiver (glasses camera) and displays on SurfaceView
 * 
 * Note: This is a legacy activity kept for backwards compatibility.
 * Use ModeSelectionActivity for the current implementation.
 */
class MainActivity : AppCompatActivity() {
    
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.applyLocale(newBase))
    }

    private lateinit var logView: TextView
    private lateinit var btnConnect: Button
    private lateinit var receivedVideoView: SurfaceView
    private lateinit var rgConnectionMode: RadioGroup
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    @Volatile
    private var l2capSocket: BluetoothSocket? = null
    
    // L2CAP socket for receiving reverse stream from glasses
    @Volatile
    private var reverseL2capSocket: BluetoothSocket? = null
    
    // Rokid SDK Manager for ARTC connection
    private var rokidSDKManager: RokidSDKManager? = null
    
    // WiFi Stream Manager for TCP connection
    private var wifiStreamManager: WiFiStreamManager? = null
    
    // Connection mode enum
    private enum class ConnectionMode { L2CAP, WIFI, ROKID_SDK }
    private var connectionMode = ConnectionMode.L2CAP
    
    // Stream direction enum
    private enum class StreamDirection { PHONE_TO_GLASSES, GLASSES_TO_PHONE }
    private var streamDirection = StreamDirection.PHONE_TO_GLASSES
    private lateinit var rgStreamDirection: RadioGroup
    
    // Atomic flag to track first frame for logging purposes
    private val isFirstFrame = AtomicBoolean(true)
    
    // Flag to indicate if streaming is active
    @Volatile
    private var isStreaming = false
    
    // Flag to prevent multiple connection attempts
    @Volatile
    private var isConnecting = false
    
    // Flag to prevent recursive stopStreaming calls
    @Volatile
    private var isStopping = false
    
    // Encoder lock to prevent race conditions during initialization
    private val encoderLock = Object()
    
    // Frame counter for periodic logging
    private var framesSent = 0
    
    // ===== Reverse stream (receiving from glasses) =====
    @Volatile
    private var isReceivingReverseStream = false
    private var framesReceived = 0
    private var framesDecoded = 0
    
    // Surface for displaying received video
    @Volatile
    private var receiveSurface: Surface? = null
    @Volatile
    private var isReceiveSurfaceValid = false
    @Volatile
    private var receiveSurfaceVersion = 0L
    private val receiveSurfaceLock = Object()

    companion object {
        const val TAG = "RokidSender"
        
        // Custom service UUID for Rokid streaming service
        val SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        
        // Characteristic UUID for reading PSM (Protocol/Service Multiplexer) value - phone -> glasses
        val PSM_CHAR_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        
        // Characteristic UUID for reading reverse PSM value - glasses -> phone
        val REVERSE_PSM_CHAR_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        
        // Video encoding parameters - OPTIMIZED FOR BLE L2CAP STREAMING
        // Based on: https://medium.com/@20x05zero/real-time-video-streaming-to-ar-glasses
        // BLE L2CAP practical bandwidth: ~600 KB/s (~4.8 Mbps), using 300 Kbps for safety margin
        const val VIDEO_WIDTH = 720
        const val VIDEO_HEIGHT = 720
        const val VIDEO_BITRATE = 300_000  // 300 Kbps - optimal for BLE L2CAP bandwidth
        const val VIDEO_FRAME_RATE = 10    // 10 FPS - stable streaming without buffer bloat
        const val VIDEO_I_FRAME_INTERVAL = 1  // Keyframe every 1 second for recovery
        const val FRAME_INTERVAL_MS = 100L  // ~10 FPS (1000ms / 10)
        const val MAX_FRAME_SIZE = 1_000_000  // 1MB for validation
        
        // Low latency constants
        const val ENCODER_TIMEOUT_US = 1000L  // 1ms encoder timeout
        const val MAX_PENDING_FRAMES = 2  // Minimal buffer to reduce latency
        
        // HEVC (H.265) support constants
        const val MIME_TYPE_HEVC = MediaFormat.MIMETYPE_VIDEO_HEVC  // "video/hevc"
        const val MIME_TYPE_AVC = MediaFormat.MIMETYPE_VIDEO_AVC   // "video/avc"
        const val HEVC_BITRATE_FACTOR = 0.7f  // 30% bitrate reduction for HEVC (same quality)
        
        // Reverse stream (glasses -> phone) video dimensions
        // MUST match glasses-app VIDEO_WIDTH/HEIGHT (240x240) for proper decoding
        const val REVERSE_VIDEO_WIDTH = 240
        const val REVERSE_VIDEO_HEIGHT = 240
        
        // Permission request codes
        const val PERMISSION_REQUEST_BLUETOOTH_CONNECT = 100
        
        /**
         * Check if device supports hardware H.265 (HEVC) encoding.
         * Only checks for hardware encoders (not software) for optimal performance.
         * MediaTek Dimensity 920 and similar chips should support hardware HEVC.
         */
        @JvmStatic
        fun isH265Supported(): Boolean {
            return try {
                val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
                for (codecInfo in codecList.codecInfos) {
                    if (!codecInfo.isEncoder) continue
                    
                    for (type in codecInfo.supportedTypes) {
                        if (type.equals(MIME_TYPE_HEVC, ignoreCase = true)) {
                            // Check if this is a hardware encoder (not software)
                            val isHardware = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                codecInfo.isHardwareAccelerated
                            } else {
                                // Heuristic: software codecs usually contain "OMX.google" or "c2.android"
                                val name = codecInfo.name.lowercase()
                                !name.contains("google") && !name.contains("c2.android")
                            }
                            
                            if (isHardware) {
                                Log.d(TAG, "✅ Hardware HEVC encoder found: ${codecInfo.name}")
                                return true
                            }
                        }
                    }
                }
                Log.d(TAG, "❌ No hardware HEVC encoder found, will use H.264")
                false
            } catch (e: Exception) {
                Log.e(TAG, "Error checking HEVC support: ${e.message}")
                false
            }
        }
    }
    
    // PSM values read from GATT
    private var sendPsm: Int = 0
    private var reversePsm: Int = 0
    private var readPsmCount = 0
    
    // BLE Advertiser for glasses discovery
    private var bleAdvertiser: BleAdvertiser? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        logView = findViewById(R.id.log_view)
        btnConnect = findViewById(R.id.btn_connect)
        receivedVideoView = findViewById(R.id.received_video_view)
        rgConnectionMode = findViewById(R.id.rg_connection_mode)
        rgStreamDirection = findViewById(R.id.rg_stream_direction)

        // Initialize Bluetooth adapter
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        
        // Initialize BLE Advertiser for glasses to discover this phone
        bleAdvertiser = BleAdvertiser(this)
        setupBleAdvertiserCallbacks()
        
        // Initialize Rokid SDK Manager
        rokidSDKManager = RokidSDKManager(this)
        setupRokidSDKCallbacks()
        
        // Initialize WiFi Stream Manager
        wifiStreamManager = WiFiStreamManager(this)
        setupWiFiCallbacks()
        
        // Connection mode selection
        rgConnectionMode.setOnCheckedChangeListener { _, checkedId ->
            connectionMode = when (checkedId) {
                R.id.rb_l2cap -> ConnectionMode.L2CAP
                R.id.rb_wifi -> ConnectionMode.WIFI
                R.id.rb_rokid_sdk -> ConnectionMode.ROKID_SDK
                else -> ConnectionMode.L2CAP
            }
            val modeName = when (connectionMode) {
                ConnectionMode.L2CAP -> "L2CAP"
                ConnectionMode.WIFI -> "WiFi"
                ConnectionMode.ROKID_SDK -> "Rokid SDK"
            }
            btnConnect.text = "Connect & Stream ($modeName)"
            log("Connection mode: $modeName")
        }
        
        // Stream direction selection
        rgStreamDirection.setOnCheckedChangeListener { _, checkedId ->
            streamDirection = when (checkedId) {
                R.id.rb_phone_to_glasses -> StreamDirection.PHONE_TO_GLASSES
                R.id.rb_glasses_to_phone -> StreamDirection.GLASSES_TO_PHONE
                else -> StreamDirection.PHONE_TO_GLASSES
            }
            val directionName = when (streamDirection) {
                StreamDirection.PHONE_TO_GLASSES -> "Phone→Glasses"
                StreamDirection.GLASSES_TO_PHONE -> "Glasses→Phone"
            }
            log("Stream direction: $directionName")
            
            // Update BLE Advertiser with new direction
            bleAdvertiser?.streamDirection = when (streamDirection) {
                StreamDirection.PHONE_TO_GLASSES -> BleAdvertiser.StreamDirectionType.PHONE_TO_GLASSES
                StreamDirection.GLASSES_TO_PHONE -> BleAdvertiser.StreamDirectionType.GLASSES_TO_PHONE
            }
        }
        
        // Setup surface callback for receiving video from glasses
        receivedVideoView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                synchronized(receiveSurfaceLock) {
                    receiveSurface = holder.surface
                    isReceiveSurfaceValid = true
                    receiveSurfaceVersion++
                    log("Receive surface created v$receiveSurfaceVersion")
                    receiveSurfaceLock.notifyAll()
                }
            }
            
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                synchronized(receiveSurfaceLock) {
                    receiveSurface = holder.surface
                    isReceiveSurfaceValid = true
                    log("Receive surface changed: ${width}x${height}")
                    receiveSurfaceLock.notifyAll()
                }
            }
            
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                synchronized(receiveSurfaceLock) {
                    isReceiveSurfaceValid = false
                    receiveSurface = null
                    log("Receive surface destroyed")
                }
            }
        })

        btnConnect.setOnClickListener {
            // Prevent multiple connection attempts
            if (isConnecting || isStreaming) {
                log("Already connecting or streaming, please wait...")
                return@setOnClickListener
            }
            
            if (checkPermissions()) {
                isConnecting = true
                btnConnect.isEnabled = false
                rgConnectionMode.isEnabled = false
                
                when (connectionMode) {
                    ConnectionMode.L2CAP -> startScan()
                    ConnectionMode.WIFI -> startWiFiConnection()
                    ConnectionMode.ROKID_SDK -> startRokidSDKConnection()
                }
            } else {
                // Request required permissions for Bluetooth, Camera, and Network
                requestPermissions(
                    arrayOf(
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_ADVERTISE,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.CAMERA
                    ),
                    1
                )
            }
        }
        
        // Language settings button
        findViewById<android.widget.ImageButton>(R.id.btn_language).setOnClickListener {
            val intent = android.content.Intent(this, com.rokid.stream.sender.ui.LanguageSelectionActivity::class.java)
            startActivity(intent)
        }
    }
    
    /**
     * Setup Rokid SDK callbacks
     */
    private fun setupRokidSDKCallbacks() {
        rokidSDKManager?.setConnectionCallback(object : RokidSDKManager.ConnectionCallback {
            override fun onDeviceFound(deviceId: String, deviceName: String) {
                log("SDK: Found device $deviceName ($deviceId)")
                // Auto-connect to first found device
                rokidSDKManager?.connect(deviceId)
            }
            
            override fun onConnected(deviceId: String) {
                log("SDK: Connected to $deviceId")
                isStreaming = true
                isConnecting = false
                runOnUiThread {
                    btnConnect.text = "Connected (SDK)"
                    startCamera()
                }
            }
            
            override fun onDisconnected(reason: String) {
                log("SDK: Disconnected - $reason")
                stopStreaming()
                runOnUiThread {
                    btnConnect.isEnabled = true
                    btnConnect.text = "Connect & Stream (Rokid SDK)"
                    rgConnectionMode.isEnabled = true
                }
            }
            
            override fun onConnectionFailed(error: String) {
                log("SDK: Connection failed - $error")
                isConnecting = false
                runOnUiThread {
                    btnConnect.isEnabled = true
                    rgConnectionMode.isEnabled = true
                }
            }
        })
        
        rokidSDKManager?.setMessageCallback(object : RokidSDKManager.MessageCallback {
            override fun onMapMessageReceived(data: Map<String, Any>) {
                log("SDK: Received message: ${data.keys}")
            }
            
            override fun onBytesReceived(data: ByteArray) {
                // Handle received video frames via SDK
                // This would be used for reverse streaming
            }
        })
    }
    
    /**
     * Setup WiFi stream manager callbacks
     */
    private fun setupWiFiCallbacks() {
        wifiStreamManager?.setConnectionCallback(object : WiFiStreamManager.ConnectionCallback {
            override fun onServiceDiscovered(serviceName: String, host: String, port: Int) {
                log("WiFi: Discovered $serviceName at $host:$port")
                // Auto-connect to discovered service
                wifiStreamManager?.connect()
            }
            
            override fun onConnected() {
                log("WiFi: Connected!")
                isStreaming = true
                isConnecting = false
                runOnUiThread {
                    btnConnect.text = "Connected (WiFi)"
                    startCamera()
                }
            }
            
            override fun onDisconnected() {
                log("WiFi: Disconnected")
                stopStreaming()
                runOnUiThread {
                    btnConnect.isEnabled = true
                    btnConnect.text = "Connect & Stream (WiFi)"
                    rgConnectionMode.isEnabled = true
                }
            }
            
            override fun onError(error: String) {
                log("WiFi: Error - $error")
                isConnecting = false
                runOnUiThread {
                    btnConnect.isEnabled = true
                    rgConnectionMode.isEnabled = true
                }
            }
        })
        
        wifiStreamManager?.setStreamCallback(object : WiFiStreamManager.StreamCallback {
            override fun onVideoFrameReceived(frameData: ByteArray, timestamp: Long, isKeyFrame: Boolean) {
                // Handle received video frames from glasses
                handleReceivedVideoFrame(frameData, isKeyFrame)
            }
            
            override fun onControlMessageReceived(message: String) {
                log("WiFi: Control message - $message")
            }
        })
    }
    
    /**
     * Setup BLE Advertiser callbacks for glasses discovery
     */
    @SuppressLint("MissingPermission")
    private fun setupBleAdvertiserCallbacks() {
        bleAdvertiser?.onDeviceConnected = { device ->
            log("BLE: Glasses connected via GATT - ${device.name ?: device.address}")
            runOnUiThread {
                // Update UI to show connected glasses
                btnConnect.isEnabled = false
            }
        }
        
        bleAdvertiser?.onDeviceDisconnected = { device ->
            log("BLE: Glasses disconnected - ${device.name ?: device.address}")
            stopStreaming()
            runOnUiThread {
                btnConnect.isEnabled = true
            }
        }
        
        // L2CAP connection callback - when glasses connect to our L2CAP server
        bleAdvertiser?.onL2capClientConnected = { outputStream, _ ->
            log("BLE: Glasses connected via L2CAP - starting video stream")
            l2capOutputStream = outputStream
            isStreaming = true
            runOnUiThread {
                startCamera()
            }
        }
        
        bleAdvertiser?.onL2capClientDisconnected = {
            log("BLE: Glasses L2CAP disconnected")
            l2capOutputStream = null
            stopStreaming()
        }
    }
    
    // Output stream for L2CAP video data (when phone is server)
    private var l2capOutputStream: java.io.OutputStream? = null
    
    /**
     * Start BLE advertising so glasses can discover this phone
     */
    private fun startBleAdvertising() {
        if (bleAdvertiser?.hasBluetoothPermissions() == true) {
            val started = bleAdvertiser?.startAdvertising() ?: false
            if (started) {
                log("BLE: Started advertising for glasses discovery")
            } else {
                log("BLE: Failed to start advertising")
            }
        } else {
            log("BLE: Missing Bluetooth permissions for advertising")
        }
    }
    
    /**
     * Stop BLE advertising
     */
    private fun stopBleAdvertising() {
        bleAdvertiser?.stopAdvertising()
        log("BLE: Stopped advertising")
    }
    
    /**
     * Start connection via WiFi
     */
    private fun startWiFiConnection() {
        log("Starting WiFi connection (discovering service)...")
        wifiStreamManager?.startDiscovery()
    }
    
    /**
     * Start connection via Rokid SDK
     */
    private fun startRokidSDKConnection() {
        log("Starting Rokid SDK connection...")
        if (rokidSDKManager?.initialize() == true) {
            rokidSDKManager?.startScan()
        } else {
            log("Failed to initialize Rokid SDK")
            isConnecting = false
            btnConnect.isEnabled = true
            rgConnectionMode.isEnabled = true
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Start BLE advertising when app is visible
        // This allows glasses to discover this phone
        if (checkPermissions()) {
            startBleAdvertising()
        }
        
        // Update BLE advertiser with current language setting
        bleAdvertiser?.languageCode = LocaleManager.getLanguageByteCode(this)
    }
    
    override fun onPause() {
        super.onPause()
        // Stop advertising when app is not visible
        stopBleAdvertising()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up resources when activity is destroyed
        stopBleAdvertising()
        stopStreaming()
        rokidSDKManager?.release()
        wifiStreamManager?.release()
    }

    /**
     * Stops all streaming operations and releases resources
     */
    private fun stopStreaming() {
        // Prevent recursive calls (e.g., onDisconnected -> stopStreaming -> disconnect -> onDisconnected)
        if (isStopping) return
        isStopping = true
        
        isStreaming = false
        isConnecting = false
        isReceivingReverseStream = false
        framesSent = 0
        framesReceived = 0
        framesDecoded = 0
        isFirstFrame.set(true)
        spsPpsData = null
        sendPsm = 0
        reversePsm = 0
        readPsmCount = 0
        
        // Stop connection based on mode
        when (connectionMode) {
            ConnectionMode.ROKID_SDK -> rokidSDKManager?.disconnect()
            ConnectionMode.WIFI -> wifiStreamManager?.disconnect()
            ConnectionMode.L2CAP -> { /* handled below */ }
        }
        
        try {
            synchronized(encoderLock) {
                videoEncoder?.stop()
                videoEncoder?.release()
                videoEncoder = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping encoder: ${e.message}")
        }
        try {
            l2capSocket?.close()
            l2capSocket = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing socket: ${e.message}")
        }
        try {
            reverseL2capSocket?.close()
            reverseL2capSocket = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing reverse socket: ${e.message}")
        }
        try {
            bluetoothGatt?.close()
            bluetoothGatt = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing GATT: ${e.message}")
        }
        
        // Reset stopping flag so stopStreaming can be called again later
        isStopping = false
        
        // Re-enable connect button
        runOnUiThread {
            btnConnect.isEnabled = true
        }
    }

    /**
     * Starts BLE scanning for Rokid receiver device
     */
    @SuppressLint("MissingPermission")
    private fun startScan() {
        log("Scanning for Rokid Receiver...")
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        
        // Configure scan settings for low latency discovery
        val settings = android.bluetooth.le.ScanSettings.Builder()
            .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        
        // Start scan without filters to see all devices, then filter manually
        scanner?.startScan(null, settings, scanCallback)
    }

    /**
     * BLE scan callback to find Rokid receiver device by service UUID
     */
    private val scanCallback = object : android.bluetooth.le.ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult?) {
            result?.let {
                // Check if the device advertises our service UUID
                val foundUuids = it.scanRecord?.serviceUuids
                if (foundUuids != null && foundUuids.contains(android.os.ParcelUuid(SERVICE_UUID))) {
                    log("Found Rokid Device: ${it.device.address}")
                    bluetoothAdapter?.bluetoothLeScanner?.stopScan(this)
                    connectGatt(it.device)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            log("Scan Failed with error code: $errorCode")
        }
    }

    /**
     * Initiates GATT connection to the discovered BLE device
     */
    @SuppressLint("MissingPermission")
    private fun connectGatt(device: BluetoothDevice) {
        log("Connecting GATT...")
        bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    /**
     * GATT callback handles connection state changes and service discovery
     */
    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    log("GATT Connected. Discovering services...")
                    // Request high priority connection for better throughput
                    gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    gatt?.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    log("GATT Disconnected")
                    stopStreaming()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Service discovery failed with status: $status")
                return
            }
            
            val service = gatt?.getService(SERVICE_UUID)
            val psmChar = service?.getCharacteristic(PSM_CHAR_UUID)
            if (psmChar != null) {
                log("Reading PSM characteristics...")
                readPsmCount = 0
                gatt.readCharacteristic(psmChar)
            } else {
                log("Service or PSM characteristic not found!")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Failed to read characteristic: ${characteristic.uuid}")
                return
            }
            
            when (characteristic.uuid) {
                PSM_CHAR_UUID -> {
                    // Parse PSM value from little-endian bytes
                    sendPsm = ByteBuffer.wrap(characteristic.value)
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        .int
                    log("Send PSM value received: $sendPsm")
                    readPsmCount++
                    
                    // Now read reverse PSM
                    val service = gatt.getService(SERVICE_UUID)
                    val reversePsmChar = service?.getCharacteristic(REVERSE_PSM_CHAR_UUID)
                    if (reversePsmChar != null) {
                        gatt.readCharacteristic(reversePsmChar)
                    } else {
                        log("Reverse PSM characteristic not found, proceeding with send only")
                        connectL2cap(gatt.device, sendPsm, null)
                    }
                }
                REVERSE_PSM_CHAR_UUID -> {
                    reversePsm = ByteBuffer.wrap(characteristic.value)
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        .int
                    log("Reverse PSM value received: $reversePsm")
                    readPsmCount++
                    
                    // Both PSMs read, connect L2CAP channels
                    connectL2cap(gatt.device, sendPsm, reversePsm)
                }
            }
        }
    }
    /**
     * Connects to the receiver via L2CAP channels for video streaming
     * L2CAP provides a reliable, connection-oriented channel over BLE
     */
    private fun connectL2cap(device: BluetoothDevice, sendPsm: Int, receivePsm: Int?) {
        Thread {
            try {
                // Runtime permission check for Android 12+ (API 31+)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) 
                        != PackageManager.PERMISSION_GRANTED) {
                        log("BLUETOOTH_CONNECT permission not granted!")
                        runOnUiThread {
                            requestPermissions(
                                arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                                PERMISSION_REQUEST_BLUETOOTH_CONNECT
                            )
                            btnConnect.isEnabled = true
                            rgConnectionMode.isEnabled = true
                        }
                        isConnecting = false
                        return@Thread
                    }
                }
                
                // Connect send channel (phone -> glasses)
                log("Connecting L2CAP send channel to PSM $sendPsm...")
                @Suppress("DEPRECATION")
                val socket = device.createInsecureL2capChannel(sendPsm)
                socket.connect()
                l2capSocket = socket
                isStreaming = true
                log("L2CAP Send Channel Connected!")
                
                // Connect receive channel if available (glasses -> phone)
                if (receivePsm != null && receivePsm > 0) {
                    log("Connecting L2CAP receive channel to PSM $receivePsm...")
                    try {
                        @Suppress("DEPRECATION")
                        val reverseSocket = device.createInsecureL2capChannel(receivePsm)
                        reverseSocket.connect()
                        reverseL2capSocket = reverseSocket
                        isReceivingReverseStream = true
                        log("L2CAP Receive Channel Connected!")
                        
                        // Start reverse stream receiver thread
                        Thread { handleReverseVideoStream(reverseSocket) }.start()
                    } catch (e: IOException) {
                        log("Reverse channel connection failed: ${e.message}")
                    }
                }
                
                isConnecting = false
                log("Starting video streaming...")
                
                // Start camera and video encoding on UI thread
                runOnUiThread { 
                    btnConnect.isEnabled = false  // Keep disabled while streaming
                    startCamera() 
                }
            } catch (e: IOException) {
                log("L2CAP Connection Failed: ${e.message}")
                isConnecting = false
                runOnUiThread { 
                    btnConnect.isEnabled = true
                    rgConnectionMode.isEnabled = true
                }
                stopStreaming()
            } catch (e: SecurityException) {
                log("Bluetooth permission denied: ${e.message}")
                isConnecting = false
                runOnUiThread { 
                    btnConnect.isEnabled = true
                    rgConnectionMode.isEnabled = true
                    // Request permission again
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                        requestPermissions(
                            arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                            PERMISSION_REQUEST_BLUETOOTH_CONNECT
                        )
                    }
                }
            }
        }.start()
    }

    // Video encoder instance (volatile for thread visibility)
    @Volatile
    private var videoEncoder: MediaCodec? = null
    
    // Last frame time for frame rate control
    private var lastFrameTime = 0L
    
    // SPS and PPS NAL units needed for decoder initialization
    @Volatile
    private var spsPpsData: ByteArray? = null
    
    // Queue for pending encoded frames to decouple encoder from Bluetooth I/O
    // MINIMAL buffer (2 frames) - prefer dropping frames over building up latency
    private val pendingFrames = java.util.concurrent.LinkedBlockingQueue<ByteArray>(MAX_PENDING_FRAMES)
    
    // Flag to track if sender thread is running
    @Volatile
    private var isSenderRunning = false

    /**
     * Initializes and starts the camera with image analysis for encoding
     */
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                
                // Configure preview for user feedback
                val preview = Preview.Builder()
                    .setTargetResolution(android.util.Size(VIDEO_WIDTH, VIDEO_HEIGHT))
                    .build()
                val viewFinder = findViewById<PreviewView>(R.id.view_finder)
                preview.setSurfaceProvider(viewFinder.surfaceProvider)

                // Configure image analysis for frame capture
                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(android.util.Size(VIDEO_WIDTH, VIDEO_HEIGHT))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()

                // Single thread executor for frame processing
                val executor = Executors.newSingleThreadExecutor()
                imageAnalysis.setAnalyzer(executor) { image ->
                    processFrame(image)
                }

                // Bind camera use cases to lifecycle
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )
                log("Camera started successfully")
            } catch (e: Exception) {
                log("Camera initialization failed: ${e.message}")
            }
        }, androidx.core.content.ContextCompat.getMainExecutor(this))
    }

    /**
     * Processes each camera frame: applies frame rate limiting, encodes, and streams
     */
    private fun processFrame(image: ImageProxy) {
        try {
            // Check if streaming is still active
            if (!isStreaming) {
                image.close()
                return
            }

            // Frame rate limiting
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastFrameTime < FRAME_INTERVAL_MS) {
                image.close()
                return
            }
            lastFrameTime = currentTime

            // Encode the frame
            encodeFrame(image)
        } finally {
            image.close()
        }
    }

    /**
     * Encodes a single camera frame using H.264 encoder
     */
    private fun encodeFrame(image: ImageProxy) {
        // Initialize encoder on first frame
        var encoder = videoEncoder
        if (encoder == null) {
            synchronized(encoderLock) {
                if (videoEncoder == null) {
                    encoder = initializeEncoder(image.width, image.height)
                    if (encoder == null) return
                    videoEncoder = encoder
                    
                    // Start encoder output drain thread
                    Thread { drainEncoderOutput() }.start()
                    
                    // Start sender thread based on connection mode
                    when (connectionMode) {
                        ConnectionMode.L2CAP -> {
                            // Check if we're server mode (glasses connected to us)
                            if (l2capOutputStream != null) {
                                Thread { sendFramesOverL2capServer() }.start()
                            } else {
                                // Client mode (we connected to glasses)
                                l2capSocket?.let { socket ->
                                    Thread { sendFramesOverBluetooth(socket) }.start()
                                }
                            }
                        }
                        ConnectionMode.WIFI -> {
                            Thread { sendFramesOverWiFi() }.start()
                        }
                        ConnectionMode.ROKID_SDK -> {
                            Thread { sendFramesOverSDK() }.start()
                        }
                    }
                } else {
                    encoder = videoEncoder
                }
            }
        }

        val safeEncoder = encoder ?: return

        try {
            // Request an input buffer from encoder
            val inputIndex = safeEncoder.dequeueInputBuffer(10000)  // 10ms timeout
            if (inputIndex >= 0) {
                val inputBuffer = safeEncoder.getInputBuffer(inputIndex) ?: return
                inputBuffer.clear()

                // Convert YUV_420_888 to NV12 and fill buffer
                val dataSize = fillNV12Buffer(image, inputBuffer)
                
                // Queue the input buffer for encoding
                val presentationTimeUs = System.nanoTime() / 1000
                safeEncoder.queueInputBuffer(inputIndex, 0, dataSize, presentationTimeUs, 0)

                if (isFirstFrame.compareAndSet(true, false)) {
                    log("First frame queued for encoding: ${image.width}x${image.height}")
                }
            }
        } catch (e: Exception) {
            log("Frame encoding error: ${e.message}")
        }
    }

    // Track which codec is currently active
    @Volatile
    private var activeCodecType: String = MIME_TYPE_AVC
    
    /**
     * Initializes video encoder with H.265 (HEVC) as primary codec, H.264 as fallback.
     * 
     * HEVC provides ~30-40% better compression at the same quality, reducing bandwidth
     * usage on BLE L2CAP while maintaining visual quality.
     * 
     * @param width Video frame width
     * @param height Video frame height
     * @return Configured MediaCodec encoder, or null if initialization fails
     */
    private fun initializeEncoder(width: Int, height: Int): MediaCodec? {
        // Try H.265 (HEVC) first if device supports hardware encoding
        if (isH265Supported()) {
            val hevcEncoder = tryInitializeEncoder(width, height, MIME_TYPE_HEVC)
            if (hevcEncoder != null) {
                return hevcEncoder
            }
            log("⚠️ H.265 initialization failed, falling back to H.264")
        }
        
        // Fallback to H.264 (AVC)
        return tryInitializeEncoder(width, height, MIME_TYPE_AVC)
    }
    
    /**
     * Attempts to initialize encoder with specified MIME type.
     * Includes all low-latency optimizations and MediaTek-specific configurations.
     * 
     * @param width Video frame width
     * @param height Video frame height  
     * @param mimeType MIME_TYPE_HEVC or MIME_TYPE_AVC
     * @return Configured MediaCodec encoder, or null if initialization fails
     */
    private fun tryInitializeEncoder(width: Int, height: Int, mimeType: String): MediaCodec? {
        try {
            val isHevc = mimeType == MIME_TYPE_HEVC
            val codecName = if (isHevc) "H.265 (HEVC)" else "H.264 (AVC)"
            log("Initializing $codecName encoder: ${width}x${height}")
            
            val encoder = MediaCodec.createEncoderByType(mimeType)
            val format = MediaFormat.createVideoFormat(mimeType, width, height)
            
            // Configure encoder format
            format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
            )
            
            // === BITRATE CONFIGURATION ===
            // HEVC is ~30-40% more efficient, so reduce bitrate to save bandwidth
            // Same visual quality at lower bitrate
            val effectiveBitrate = if (isHevc) {
                (VIDEO_BITRATE * HEVC_BITRATE_FACTOR).toInt()
            } else {
                VIDEO_BITRATE
            }
            format.setInteger(MediaFormat.KEY_BIT_RATE, effectiveBitrate)
            log("📊 Bitrate: $effectiveBitrate bps ($codecName)")
            
            format.setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FRAME_RATE)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VIDEO_I_FRAME_INTERVAL)
            
            // === CODEC PROFILE CONFIGURATION ===
            if (isHevc) {
                // HEVC Main Profile - widely supported, good compression
                format.setInteger(
                    MediaFormat.KEY_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
                )
                format.setInteger(
                    MediaFormat.KEY_LEVEL,
                    MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel31
                )
            } else {
                // AVC Baseline Profile - maximum compatibility
                format.setInteger(
                    MediaFormat.KEY_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
                )
                format.setInteger(
                    MediaFormat.KEY_LEVEL,
                    MediaCodecInfo.CodecProfileLevel.AVCLevel31
                )
            }
            
            // === LOW LATENCY OPTIMIZATIONS ===
            // Critical for real-time streaming to AR glasses
            
            // Enable low latency mode if supported (API 30+)
            // This is the most important flag for reducing encoding delay
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                log("🚀 Low latency mode enabled (API 30+)")
            }
            
            // Disable B-frames for zero reordering delay
            // B-frames require future frames, adding significant latency
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
            
            // Set real-time priority (0 = realtime, 1 = non-realtime)
            // Tells encoder to prioritize speed over power efficiency
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                format.setInteger(MediaFormat.KEY_PRIORITY, 0)  // 0 = realtime
            }
            
            // Set CBR (Constant Bit Rate) mode for consistent streaming
            // VBR can cause bitrate spikes that overwhelm BLE L2CAP
            format.setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
            )
            
            // Reduce complexity for faster encoding
            // 0 = minimum complexity = fastest encoding
            format.setInteger(MediaFormat.KEY_COMPLEXITY, 0)
            
            // === MEDIATEK-SPECIFIC OPTIMIZATIONS ===
            // MediaTek chips (Dimensity series) may buffer frames for analysis
            // These vendor-specific keys help reduce that buffering
            try {
                // Prepend SPS/PPS (H.264) or VPS/SPS/PPS (H.265) to each IDR frame
                // This helps decoder recover faster from packet loss
                format.setInteger("prepend-sps-pps-to-idr-frames", 1)
            } catch (e: Exception) {
                // Ignore if not supported
            }
            
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            
            activeCodecType = mimeType
            log("✅ $codecName encoder initialized successfully")
            return encoder
        } catch (e: Exception) {
            log("❌ Encoder initialization failed ($mimeType): ${e.message}")
            return null
        }
    }
    
    /**
     * Check if currently using H.265 (HEVC) codec
     */
    fun isUsingHevc(): Boolean = activeCodecType == MIME_TYPE_HEVC

    /**
     * Converts YUV_420_888 format to NV12 (YUV420 semi-planar) format
     * NV12 layout: Y plane followed by interleaved UV plane
     * 
     * @return Number of bytes written to buffer
     */
    private fun fillNV12Buffer(image: ImageProxy, buffer: ByteBuffer): Int {
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        var position = 0

        // Copy Y plane row by row
        // Handle row stride (may have padding at end of each row)
        if (yRowStride == width) {
            // No padding, copy entire Y plane at once
            val ySize = width * height
            yBuffer.position(0)
            val yBytes = ByteArray(minOf(ySize, yBuffer.remaining()))
            yBuffer.get(yBytes)
            buffer.put(yBytes)
            position += yBytes.size
        } else {
            // Has padding, copy row by row
            val rowBytes = ByteArray(width)
            for (row in 0 until height) {
                yBuffer.position(row * yRowStride)
                yBuffer.get(rowBytes, 0, minOf(width, yBuffer.remaining()))
                buffer.put(rowBytes)
                position += width
            }
        }

        // Copy UV plane (interleaved as NV12: UVUVUV...)
        val uvHeight = height / 2
        val uvWidth = width / 2

        if (uvPixelStride == 2 && uvRowStride == width) {
            // UV data is already semi-planar (common case), copy directly
            uBuffer.position(0)
            val uvSize = width * uvHeight
            val uvBytes = ByteArray(minOf(uvSize, uBuffer.remaining()))
            uBuffer.get(uvBytes)
            buffer.put(uvBytes)
            position += uvBytes.size
        } else {
            // Need to manually interleave U and V
            for (row in 0 until uvHeight) {
                for (col in 0 until uvWidth) {
                    val uvIndex = row * uvRowStride + col * uvPixelStride
                    
                    // Get U value
                    if (uvIndex < uBuffer.limit()) {
                        buffer.put(uBuffer.get(uvIndex))
                        position++
                    }
                    // Get V value
                    if (uvIndex < vBuffer.limit()) {
                        buffer.put(vBuffer.get(uvIndex))
                        position++
                    }
                }
            }
        }

        return position
    }

    /**
     * Drains encoded output from encoder and queues frames for sending
     * This runs in a separate thread to avoid blocking the encoder
     * Frames are queued for the Bluetooth sender thread to send asynchronously
     */
    private fun drainEncoderOutput() {
        val bufferInfo = MediaCodec.BufferInfo()

        log("Starting encoder output drain thread")
        var droppedFrames = 0

        try {
            while (isStreaming) {
                val encoder = videoEncoder ?: break
                
                val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000)  // 10ms timeout
                
                when {
                    outputIndex >= 0 -> {
                        val outputBuffer = encoder.getOutputBuffer(outputIndex)
                        if (outputBuffer != null) {
                            // Check for codec config data (SPS/PPS)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                // Store SPS/PPS for potential resync
                                val configData = ByteArray(bufferInfo.size)
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.get(configData)
                                spsPpsData = configData
                                log("Received codec config (SPS/PPS): ${bufferInfo.size} bytes")
                                
                                // Queue SPS/PPS with high priority
                                if (!pendingFrames.offer(configData)) {
                                    pendingFrames.poll()  // Remove oldest
                                    pendingFrames.offer(configData)
                                }
                            } else if (bufferInfo.size > 0) {
                                // Regular encoded frame
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                
                                val frameData = ByteArray(bufferInfo.size)
                                outputBuffer.get(frameData)
                                
                                // Check if this is a keyframe, prepend SPS/PPS if needed
                                val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                                if (isKeyFrame) {
                                    spsPpsData?.let { sps ->
                                        // Queue SPS/PPS before keyframe for decoder sync
                                        if (!pendingFrames.offer(sps)) {
                                            pendingFrames.poll()
                                            pendingFrames.offer(sps)
                                        }
                                    }
                                }
                                
                                // Queue the encoded frame (non-blocking)
                                if (!pendingFrames.offer(frameData)) {
                                    // Queue full, drop oldest frame
                                    pendingFrames.poll()
                                    pendingFrames.offer(frameData)
                                    droppedFrames++
                                    if (droppedFrames % 10 == 0) {
                                        Log.d(TAG, "Dropped $droppedFrames frames due to slow Bluetooth")
                                    }
                                }
                            }
                        }
                        
                        // CRITICAL: Always release output buffer immediately
                        encoder.releaseOutputBuffer(outputIndex, false)
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = encoder.outputFormat
                        log("Encoder output format changed: $newFormat")
                    }
                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // No output available, continue loop
                    }
                }
            }
        } catch (e: Exception) {
            log("Encoder drain error: ${e.message}")
        } finally {
            log("Encoder drain thread stopped, dropped $droppedFrames frames total")
        }
    }
    
    /**
     * Sends queued frames over Bluetooth socket
     * This runs in a separate thread to prevent blocking the encoder
     */
    private fun sendFramesOverBluetooth(socket: BluetoothSocket) {
        val outputStream = socket.outputStream
        val headerBuffer = ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        
        log("Starting Bluetooth sender thread")
        isSenderRunning = true
        
        try {
            while (isStreaming) {
                // Wait for frame with timeout
                val frameData = pendingFrames.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (frameData != null) {
                    sendFrame(outputStream, headerBuffer, frameData)
                    framesSent++
                    
                    // Log progress periodically
                    if (framesSent % 30 == 0) {
                        Log.d(TAG, "Sent $framesSent frames, pending: ${pendingFrames.size}, last size: ${frameData.size} bytes")
                    }
                }
            }
        } catch (e: IOException) {
            log("Bluetooth stream disconnected: ${e.message}")
        } catch (e: Exception) {
            log("Bluetooth sender error: ${e.message}")
        } finally {
            isSenderRunning = false
            log("Bluetooth sender thread stopped")
            cleanupResources()
        }
    }
    
    /**
     * Sends queued frames over L2CAP server socket (when phone hosts L2CAP server)
     * This is used when glasses connect to phone's L2CAP server
     */
    private fun sendFramesOverL2capServer() {
        val outputStream = l2capOutputStream ?: return
        val headerBuffer = ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        
        log("Starting L2CAP server sender thread")
        isSenderRunning = true
        
        try {
            while (isStreaming && l2capOutputStream != null) {
                val frameData = pendingFrames.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (frameData != null) {
                    sendFrame(outputStream, headerBuffer, frameData)
                    framesSent++
                    
                    if (framesSent % 30 == 0) {
                        Log.d(TAG, "L2CAP Server: Sent $framesSent frames, pending: ${pendingFrames.size}, last size: ${frameData.size} bytes")
                    }
                }
            }
        } catch (e: IOException) {
            log("L2CAP server stream disconnected: ${e.message}")
        } catch (e: Exception) {
            log("L2CAP server sender error: ${e.message}")
        } finally {
            isSenderRunning = false
            log("L2CAP server sender thread stopped")
        }
    }
    
    /**
     * Sends queued frames over WiFi TCP socket
     */
    private fun sendFramesOverWiFi() {
        log("Starting WiFi sender thread")
        isSenderRunning = true
        
        try {
            while (isStreaming && wifiStreamManager?.isConnected() == true) {
                val frameData = pendingFrames.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (frameData != null) {
                    // Detect keyframe by checking NAL unit type
                    val isKeyFrame = isKeyFrame(frameData)
                    wifiStreamManager?.sendVideoFrame(frameData, System.currentTimeMillis(), isKeyFrame)
                    framesSent++
                    
                    if (framesSent % 30 == 0) {
                        Log.d(TAG, "WiFi: Sent $framesSent frames, pending: ${pendingFrames.size}")
                    }
                }
            }
        } catch (e: Exception) {
            log("WiFi sender error: ${e.message}")
        } finally {
            isSenderRunning = false
            log("WiFi sender thread stopped")
        }
    }
    
    /**
     * Sends queued frames over Rokid SDK
     */
    private fun sendFramesOverSDK() {
        log("Starting SDK sender thread")
        isSenderRunning = true
        
        try {
            while (isStreaming && rokidSDKManager?.isConnected() == true) {
                val frameData = pendingFrames.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (frameData != null) {
                    val isKeyFrame = isKeyFrame(frameData)
                    rokidSDKManager?.sendVideoFrame(frameData, System.currentTimeMillis(), isKeyFrame)
                    framesSent++
                    
                    if (framesSent % 30 == 0) {
                        Log.d(TAG, "SDK: Sent $framesSent frames, pending: ${pendingFrames.size}")
                    }
                }
            }
        } catch (e: Exception) {
            log("SDK sender error: ${e.message}")
        } finally {
            isSenderRunning = false
            log("SDK sender thread stopped")
        }
    }
    
    /**
     * Check if frame is a keyframe by examining NAL unit type
     */
    private fun isKeyFrame(frameData: ByteArray): Boolean {
        if (frameData.size < 5) return false
        // Look for NAL unit start code and check type
        for (i in 0 until minOf(frameData.size - 4, 100)) {
            if (frameData[i] == 0x00.toByte() && frameData[i+1] == 0x00.toByte()) {
                val startCodeLen = if (frameData[i+2] == 0x01.toByte()) 3
                    else if (frameData[i+2] == 0x00.toByte() && frameData[i+3] == 0x01.toByte()) 4
                    else continue
                val nalType = (frameData[i + startCodeLen].toInt() and 0x1F)
                // IDR frame (type 5) or SPS (type 7) indicates keyframe
                if (nalType == 5 || nalType == 7) return true
            }
        }
        return false
    }

    /**
     * Sends a single frame over the Bluetooth socket
     * Format: [4-byte length][frame data]
     */
    private fun sendFrame(
        outputStream: java.io.OutputStream,
        headerBuffer: ByteBuffer,
        data: ByteArray
    ) {
        headerBuffer.clear()
        headerBuffer.putInt(data.size)
        outputStream.write(headerBuffer.array())
        outputStream.write(data)
        outputStream.flush()
    }

    /**
     * Cleans up all resources after streaming ends
     */
    private fun cleanupResources() {
        isStreaming = false
        isReceivingReverseStream = false
        
        // Clear pending frames queue
        pendingFrames.clear()
        
        // Stop and release encoder
        synchronized(encoderLock) {
            try {
                videoEncoder?.stop()
                videoEncoder?.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing encoder: ${e.message}")
            }
            videoEncoder = null
        }
        
        // Close reverse socket
        try {
            reverseL2capSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing reverse socket: ${e.message}")
        }
        reverseL2capSocket = null
        
        // Unbind camera to prevent "Camera unavailable" on next run
        runOnUiThread {
            try {
                val cameraProvider = ProcessCameraProvider.getInstance(this).get()
                cameraProvider.unbindAll()
                log("Camera unbound")
            } catch (e: Exception) {
                Log.e(TAG, "Error unbinding camera: ${e.message}")
            }
        }
    }
    
    // Queue for WiFi/SDK received frames
    private val receivedFrameQueue = java.util.concurrent.LinkedBlockingQueue<Pair<ByteArray, Boolean>>(30)
    @Volatile
    private var isWiFiReceiverRunning = false
    
    /**
     * Handle received video frame from WiFi or SDK (non-L2CAP modes)
     * This queues frames for the decoder thread
     */
    private fun handleReceivedVideoFrame(frameData: ByteArray, isKeyFrame: Boolean) {
        if (!receivedFrameQueue.offer(Pair(frameData, isKeyFrame))) {
            // Queue full, drop oldest
            receivedFrameQueue.poll()
            receivedFrameQueue.offer(Pair(frameData, isKeyFrame))
        }
        
        // Start decoder thread if not running
        if (!isWiFiReceiverRunning) {
            isWiFiReceiverRunning = true
            Thread { processReceivedFrames() }.start()
        }
    }
    
    /**
     * Process received frames from WiFi/SDK in a decoder thread
     */
    private fun processReceivedFrames() {
        val mimeType = "video/avc"
        var decoder: MediaCodec? = null
        var isDecoderConfigured = false
        var codecConfigData: ByteArray? = null
        val bufferInfo = MediaCodec.BufferInfo()
        
        log("WiFi/SDK receiver thread started")
        
        try {
            while (isStreaming && isWiFiReceiverRunning) {
                val framePair = receivedFrameQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                    ?: continue
                    
                val frameData = framePair.first
                val isKeyFrame = framePair.second
                
                framesReceived++
                
                // Check surface validity
                val surfaceValid: Boolean
                val currentSurface: Surface?
                synchronized(receiveSurfaceLock) {
                    surfaceValid = isReceiveSurfaceValid
                    currentSurface = if (surfaceValid) receiveSurface else null
                }
                
                if (currentSurface == null || !surfaceValid) {
                    if (reverseStartsWithCodecConfig(frameData)) {
                        codecConfigData = extractReverseCodecConfig(frameData)
                    }
                    continue
                }
                
                // Initialize decoder if needed
                if (decoder == null && currentSurface != null) {
                    if (reverseStartsWithCodecConfig(frameData)) {
                        codecConfigData = extractReverseCodecConfig(frameData)
                    }
                    
                    val configToUse = codecConfigData ?: if (isKeyFrame) frameData else null
                    if (configToUse != null) {
                        decoder = initializeReverseDecoder(mimeType, currentSurface, configToUse)
                        if (decoder != null) {
                            isDecoderConfigured = true
                            log("WiFi/SDK decoder initialized")
                        }
                    }
                    continue
                }
                
                // Feed frame to decoder
                if (decoder != null && isDecoderConfigured) {
                    decodeReverseFrame(decoder, frameData, bufferInfo)
                }
            }
        } catch (e: Exception) {
            log("WiFi/SDK receiver error: ${e.message}")
        } finally {
            decoder?.let { releaseDecoder(it) }
            isWiFiReceiverRunning = false
            log("WiFi/SDK receiver thread stopped")
        }
    }
    
    // ===== Reverse Stream Handling (Receiving video from glasses) =====
    
    /**
     * Detect codec type from frame data.
     * H.265 starts with VPS (NAL type 32), H.264 starts with SPS (NAL type 7)
     */
    private fun detectReverseCodecType(data: ByteArray): String {
        if (data.size < 5) return MIME_TYPE_AVC
        
        // Find NAL start code
        val startIndex = when {
            data.size > 4 && data[0] == 0.toByte() && data[1] == 0.toByte() &&
                    data[2] == 0.toByte() && data[3] == 1.toByte() -> 4
            data.size > 3 && data[0] == 0.toByte() && data[1] == 0.toByte() &&
                    data[2] == 1.toByte() -> 3
            else -> return MIME_TYPE_AVC
        }
        
        if (startIndex >= data.size) return MIME_TYPE_AVC
        
        val nalByte = data[startIndex].toInt()
        
        // H.265: NAL type is bits 1-6, VPS=32, SPS=33, PPS=34
        val hevcNalType = (nalByte shr 1) and 0x3F
        if (hevcNalType in 32..34) {
            log("🎬 Detected H.265 (HEVC) reverse stream, NAL type=$hevcNalType")
            return MIME_TYPE_HEVC
        }
        
        // H.264: NAL type is bits 0-4, SPS=7, PPS=8
        val avcNalType = nalByte and 0x1F
        if (avcNalType == 7 || avcNalType == 8) {
            log("🎬 Detected H.264 (AVC) reverse stream, NAL type=$avcNalType")
            return MIME_TYPE_AVC
        }
        
        return MIME_TYPE_AVC
    }
    
    /**
     * Handles incoming video stream from glasses
     */
    @SuppressLint("MissingPermission")
    private fun handleReverseVideoStream(socket: BluetoothSocket) {
        log("Starting reverse video stream receiver...")
        
        framesReceived = 0
        framesDecoded = 0
        
        // Auto-detect codec type from stream (will be set on first codec config frame)
        var detectedMimeType: String? = null
        var decoder: MediaCodec? = null
        var currentSurface: Surface? = null
        var currentSurfaceVersion = 0L
        var isDecoderConfigured = false
        
        val inputStream = socket.inputStream
        val headerBuffer = ByteArray(4)
        val bufferInfo = MediaCodec.BufferInfo()
        
        var codecConfigData: ByteArray? = null
        var lastLogTime = System.currentTimeMillis()
        
        // Wait for receive surface
        log("Waiting for receive surface...")
        synchronized(receiveSurfaceLock) {
            var waitCount = 0
            while (!isReceiveSurfaceValid && isReceivingReverseStream && waitCount < 50) {
                try {
                    receiveSurfaceLock.wait(100)
                    waitCount++
                } catch (e: InterruptedException) {
                    break
                }
            }
            if (isReceiveSurfaceValid) {
                currentSurface = receiveSurface
                currentSurfaceVersion = receiveSurfaceVersion
                log("Receive surface ready v$currentSurfaceVersion")
            }
        }

        try {
            while (isReceivingReverseStream) {
                if (!readFully(inputStream, headerBuffer, 4)) {
                    log("Reverse stream connection closed")
                    break
                }
                
                val length = ByteBuffer.wrap(headerBuffer)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .int

                if (length <= 0 || length > MAX_FRAME_SIZE) {
                    log("Invalid reverse frame length: $length")
                    continue
                }

                val frameData = ByteArray(length)
                if (!readFully(inputStream, frameData, length)) {
                    log("Failed to read reverse frame data")
                    break
                }
                
                framesReceived++
                
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastLogTime > 5000) {
                    log("Reverse: Received $framesReceived, Decoded $framesDecoded")
                    lastLogTime = currentTime
                }

                // Check surface validity
                val surfaceValid: Boolean
                val newSurface: Surface?
                val newVersion: Long
                synchronized(receiveSurfaceLock) {
                    surfaceValid = isReceiveSurfaceValid
                    newSurface = if (surfaceValid) receiveSurface else null
                    newVersion = receiveSurfaceVersion
                }
                
                if (newSurface == null || !surfaceValid) {
                    if (reverseStartsWithCodecConfig(frameData)) {
                        codecConfigData = extractReverseCodecConfig(frameData)
                        // Detect codec type from config data
                        if (detectedMimeType == null) {
                            detectedMimeType = detectReverseCodecType(frameData)
                        }
                    }
                    if (decoder != null) {
                        releaseDecoder(decoder)
                        decoder = null
                        isDecoderConfigured = false
                        currentSurface = null
                    }
                    continue
                }
                
                // Handle surface changes
                if (decoder != null && (newVersion != currentSurfaceVersion || newSurface != currentSurface)) {
                    releaseDecoder(decoder)
                    decoder = null
                    isDecoderConfigured = false
                }
                currentSurface = newSurface
                currentSurfaceVersion = newVersion

                // Initialize decoder if needed
                if (decoder == null && currentSurface != null && surfaceValid) {
                    if (reverseStartsWithCodecConfig(frameData)) {
                        codecConfigData = extractReverseCodecConfig(frameData)
                        // Detect codec type from config data
                        if (detectedMimeType == null) {
                            detectedMimeType = detectReverseCodecType(frameData)
                        }
                        log("Reverse stream codec config: ${codecConfigData?.size ?: 0} bytes (from ${frameData.size} bytes), type=$detectedMimeType")
                    }
                    
                    // Default to H.264 if not yet detected
                    val mimeType = detectedMimeType ?: MIME_TYPE_AVC
                    
                    Thread.sleep(50)
                    
                    val (stillValid, versionCheck) = synchronized(receiveSurfaceLock) { 
                        Pair(isReceiveSurfaceValid, receiveSurfaceVersion) 
                    }
                    if (!stillValid || versionCheck != currentSurfaceVersion) {
                        continue
                    }
                    
                    val surfaceForDecoder = currentSurface ?: continue
                    decoder = initializeReverseDecoder(mimeType, surfaceForDecoder, codecConfigData)
                    if (decoder != null) {
                        isDecoderConfigured = true
                        log("Reverse decoder initialized")
                    } else {
                        continue
                    }
                }

                if (decoder == null) continue

                try {
                    val (stillValidForDecode, versionForDecode) = synchronized(receiveSurfaceLock) { 
                        Pair(isReceiveSurfaceValid, receiveSurfaceVersion) 
                    }
                    if (!stillValidForDecode || versionForDecode != currentSurfaceVersion) {
                        releaseDecoder(decoder)
                        decoder = null
                        isDecoderConfigured = false
                        currentSurface = null
                        continue
                    }
                    
                    if (decodeReverseFrame(decoder, frameData, bufferInfo)) {
                        framesDecoded++
                    }
                } catch (e: IllegalStateException) {
                    releaseDecoder(decoder)
                    decoder = null
                    isDecoderConfigured = false
                    currentSurface = null
                } catch (e: Exception) {
                    releaseDecoder(decoder)
                    decoder = null
                    isDecoderConfigured = false
                }
            }
        } catch (e: Exception) {
            log("Reverse stream ended: ${e.message}")
        } finally {
            releaseDecoder(decoder)
            try { socket.close() } catch (e: Exception) {}
            log("Reverse stream connection closed")
        }
    }
    
    private fun readFully(inputStream: InputStream, buffer: ByteArray, length: Int): Boolean {
        var read = 0
        while (read < length) {
            val n = inputStream.read(buffer, read, length - read)
            if (n == -1) return false
            read += n
        }
        return true
    }
    
    /**
     * Check if frame data is PURE codec config (only VPS/SPS/PPS, no IDR slice)
     * Returns true ONLY for pure codec config, NOT for keyframes with prepended config.
     */
    private fun isReverseCodecConfig(data: ByteArray): Boolean {
        if (data.size < 5) return false
        
        // Check first NAL unit type
        val firstNalType = getReverseFirstNalType(data)
        if (firstNalType < 0) return false
        
        // Check if first NAL is codec config type
        val isHevcConfig = firstNalType in 32..34
        val isAvcConfig = firstNalType == 7 || firstNalType == 8
        
        if (!isHevcConfig && !isAvcConfig) return false
        
        // Now check if this is PURE codec config (no IDR frame after)
        // If the data contains an IDR slice, it's a keyframe with prepended config
        return !reverseContainsIdrSlice(data)
    }
    
    /**
     * Check if frame data starts with codec config (VPS/SPS/PPS)
     * Used to detect frames that can initialize the decoder
     */
    private fun reverseStartsWithCodecConfig(data: ByteArray): Boolean {
        if (data.size < 5) return false
        
        val firstNalType = getReverseFirstNalType(data)
        if (firstNalType < 0) return false
        
        // Check if first NAL is codec config type
        return firstNalType in 32..34 || firstNalType == 7 || firstNalType == 8
    }
    
    /**
     * Get the NAL unit type of the first NAL unit in the data
     */
    private fun getReverseFirstNalType(data: ByteArray): Int {
        val startIndex = when {
            data.size > 4 && data[0] == 0.toByte() && data[1] == 0.toByte() && 
                data[2] == 0.toByte() && data[3] == 1.toByte() -> 4
            data.size > 3 && data[0] == 0.toByte() && data[1] == 0.toByte() && 
                data[2] == 1.toByte() -> 3
            else -> return -1
        }
        
        if (startIndex >= data.size) return -1
        
        val nalByte = data[startIndex].toInt()
        
        // For HEVC: NAL type is bits 1-6 of first byte
        val hevcNalType = (nalByte shr 1) and 0x3F
        
        // Heuristic: if HEVC NAL type makes sense (0-40), use HEVC
        if (hevcNalType in 0..40) {
            return hevcNalType
        }
        
        // For AVC: NAL type is bits 0-4 of first byte
        return nalByte and 0x1F
    }
    
    /**
     * Check if the data contains an IDR slice (keyframe)
     */
    private fun reverseContainsIdrSlice(data: ByteArray): Boolean {
        var i = 0
        while (i < data.size - 4) {
            // Look for NAL start code
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                val startCodeLen: Int
                val nalIndex: Int
                
                when {
                    i + 3 < data.size && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte() -> {
                        startCodeLen = 4
                        nalIndex = i + 4
                    }
                    data[i + 2] == 1.toByte() -> {
                        startCodeLen = 3
                        nalIndex = i + 3
                    }
                    else -> {
                        i++
                        continue
                    }
                }
                
                if (nalIndex >= data.size) break
                
                val nalByte = data[nalIndex].toInt()
                
                // Check for H.265 IDR (NAL types 16-21 = IRAP)
                val hevcNalType = (nalByte shr 1) and 0x3F
                if (hevcNalType in 16..21) return true
                
                // Check for H.264 IDR (NAL type 5)
                val avcNalType = nalByte and 0x1F
                if (avcNalType == 5) return true
                
                i += startCodeLen
            } else {
                i++
            }
        }
        
        return false
    }
    
    /**
     * Extract pure codec config from data (remove IDR slice if present)
     */
    private fun extractReverseCodecConfig(data: ByteArray): ByteArray {
        if (!reverseContainsIdrSlice(data)) {
            return data
        }
        
        var i = 0
        var lastConfigEnd = 0
        
        while (i < data.size - 4) {
            // Look for NAL start code
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                val startCodeLen: Int
                val nalIndex: Int
                
                when {
                    i + 3 < data.size && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte() -> {
                        startCodeLen = 4
                        nalIndex = i + 4
                    }
                    data[i + 2] == 1.toByte() -> {
                        startCodeLen = 3
                        nalIndex = i + 3
                    }
                    else -> {
                        i++
                        continue
                    }
                }
                
                if (nalIndex >= data.size) break
                
                val nalByte = data[nalIndex].toInt()
                val hevcNalType = (nalByte shr 1) and 0x3F
                
                // If this is config NAL, remember this position
                if (hevcNalType in 32..34) {
                    // Continue to find next NAL
                }
                // If this is IDR, return data before it
                else if (hevcNalType in 16..21) {
                    return data.copyOfRange(0, i)
                }
                
                i += startCodeLen
            } else {
                i++
            }
        }
        
        return data
    }
    
    private fun initializeReverseDecoder(mimeType: String, surface: Surface, configData: ByteArray?): MediaCodec? {
        return try {
            val codecName = if (mimeType == MIME_TYPE_HEVC) "H.265 (HEVC)" else "H.264 (AVC)"
            log("Initializing $codecName reverse decoder: ${REVERSE_VIDEO_WIDTH}x${REVERSE_VIDEO_HEIGHT}")
            
            val decoder = MediaCodec.createDecoderByType(mimeType)
            val format = MediaFormat.createVideoFormat(mimeType, REVERSE_VIDEO_WIDTH, REVERSE_VIDEO_HEIGHT)
            
            // Extract pure codec config (remove IDR if present)
            configData?.let {
                val pureConfig = extractReverseCodecConfig(it)
                log("Codec config size: ${pureConfig.size} bytes (original: ${it.size} bytes)")
                format.setByteBuffer("csd-0", ByteBuffer.wrap(pureConfig))
            }
            
            // === LOW LATENCY OPTIMIZATIONS ===
            // Enable low latency mode if supported (API 30+)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            
            // Set real-time priority (0 = realtime)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                format.setInteger(MediaFormat.KEY_PRIORITY, 0)
            }
            
            decoder.configure(format, surface, null, 0)
            decoder.start()
            
            log("✅ $codecName reverse decoder initialized successfully")
            decoder
        } catch (e: Exception) {
            log("❌ Reverse decoder init failed: ${e.message}")
            null
        }
    }
    
    private fun decodeReverseFrame(decoder: MediaCodec, frameData: ByteArray, bufferInfo: MediaCodec.BufferInfo): Boolean {
        val inputIndex = decoder.dequeueInputBuffer(10000)
        if (inputIndex >= 0) {
            val inputBuffer = decoder.getInputBuffer(inputIndex)
            inputBuffer?.clear()
            inputBuffer?.put(frameData)
            
            val flags = if (isReverseCodecConfig(frameData)) {
                MediaCodec.BUFFER_FLAG_CODEC_CONFIG
            } else {
                0
            }
            
            decoder.queueInputBuffer(inputIndex, 0, frameData.size, System.nanoTime() / 1000, flags)
        } else {
            return false
        }

        return drainReverseDecoderOutput(decoder, bufferInfo)
    }
    
    private fun drainReverseDecoderOutput(decoder: MediaCodec, bufferInfo: MediaCodec.BufferInfo): Boolean {
        var rendered = false
        var outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000)
        
        while (true) {
            when {
                outputIndex >= 0 -> {
                    decoder.releaseOutputBuffer(outputIndex, true)
                    rendered = true
                    outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 0)
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 0)
                }
                else -> break
            }
        }
        
        return rendered
    }
    
    private fun releaseDecoder(decoder: MediaCodec?) {
        try {
            decoder?.stop()
            decoder?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing decoder: ${e.message}")
        }
    }

    /**
     * Checks if all required permissions are granted
     */
    private fun checkPermissions(): Boolean {
        val connectPermission = ActivityCompat.checkSelfPermission(
            this, Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
        val scanPermission = ActivityCompat.checkSelfPermission(
            this, Manifest.permission.BLUETOOTH_SCAN
        ) == PackageManager.PERMISSION_GRANTED
        val advertisePermission = ActivityCompat.checkSelfPermission(
            this, Manifest.permission.BLUETOOTH_ADVERTISE
        ) == PackageManager.PERMISSION_GRANTED
        val locationPermission = ActivityCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val cameraPermission = ActivityCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        return connectPermission && scanPermission && advertisePermission && locationPermission && cameraPermission
    }

    /**
     * Logs message to both logcat and UI TextView
     */
    private fun log(msg: String) {
        Log.d(TAG, msg)
        runOnUiThread {
            logView.append("$msg\n")
            // Auto-scroll to bottom
            val scrollView = logView.parent as? android.widget.ScrollView
            scrollView?.fullScroll(android.widget.ScrollView.FOCUS_DOWN)
        }
    }
}
