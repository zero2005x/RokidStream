package com.rokid.stream.receiver

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
import java.io.OutputStream
import java.util.*
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import android.widget.RadioGroup

/**
 * MainActivity - Bidirectional Receiver application for Rokid AR glasses
 * 
 * This app supports two connection modes:
 * 1. BLE L2CAP - Direct Bluetooth Low Energy connection
 * 2. Rokid SDK - Wi-Fi based connection using Rokid CXR-M/S SDK
 * 
 * Features:
 * - Receives H.264 video from phone and displays on SurfaceView
 * - Captures glasses camera, encodes H.264, and streams back to phone
 */
class MainActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private lateinit var surfaceView: SurfaceView
    private lateinit var previewView: PreviewView
    private lateinit var btnStreamBack: Button
    private lateinit var rgConnectionMode: RadioGroup
    
    // Bluetooth components
    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var gattServer: BluetoothGattServer? = null
    
    // L2CAP sockets for bidirectional streaming
    private var l2capServerSocket: BluetoothServerSocket? = null      // For receiving video FROM phone
    private var l2capSendServerSocket: BluetoothServerSocket? = null  // For sending video TO phone
    
    // Connected client socket for reverse streaming
    @Volatile
    private var reverseStreamSocket: BluetoothSocket? = null
    
    // Rokid SDK Manager
    private var rokidSDKManager: RokidSDKManager? = null
    
    // WiFi Stream Manager
    private var wifiStreamManager: WiFiStreamManager? = null
    
    // Connection mode enum
    private enum class ConnectionMode { L2CAP, WIFI, ROKID_SDK }
    private var connectionMode = ConnectionMode.L2CAP
    
    // Flag to control server lifecycle
    @Volatile
    private var isRunning = true
    
    // Flag for reverse streaming
    @Volatile
    private var isReverseStreaming = false

    companion object {
        const val TAG = "RokidReceiver"
        
        // Custom service UUID - must match sender
        val SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        
        // Characteristic UUID for PSM value (phone -> glasses) - must match sender
        val PSM_CHAR_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        
        // Characteristic UUID for reverse PSM value (glasses -> phone)
        val REVERSE_PSM_CHAR_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        
        // Video dimensions - must match sender
        const val VIDEO_WIDTH = 240
        const val VIDEO_HEIGHT = 240
        
        // Reverse stream encoding parameters
        const val VIDEO_BITRATE = 300_000  // 300 Kbps
        const val VIDEO_FRAME_RATE = 10    // 10 FPS
        const val VIDEO_I_FRAME_INTERVAL = 1
        const val FRAME_INTERVAL_MS = 100L
        
        // Maximum expected frame size (for validation)
        const val MAX_FRAME_SIZE = 1_000_000  // 1MB to handle larger frames
    }

    // L2CAP PSM values assigned by system
    private var l2capPsm: Int = 0           // For receiving from phone
    private var l2capReversePsm: Int = 0    // For sending to phone
    
    // Frame counters for debugging
    private var framesReceived = 0
    private var framesDecoded = 0
    private var framesSent = 0
    
    // Surface for video decoding - volatile for thread visibility
    @Volatile
    private var surface: Surface? = null
    
    // Flag to track if surface is valid (not destroyed)
    @Volatile
    private var isSurfaceValid = false
    
    // Surface version counter to detect surface changes during decoder init
    @Volatile
    private var surfaceVersion = 0L
    
    // Lock for surface operations
    private val surfaceLock = Object()
    
    // ===== Reverse streaming (glasses -> phone) components =====
    
    // Video encoder for reverse streaming
    @Volatile
    private var videoEncoder: MediaCodec? = null
    private val encoderLock = Object()
    
    // Atomic flag to track first frame for logging
    private val isFirstFrame = AtomicBoolean(true)
    
    // Last frame time for frame rate control
    private var lastFrameTime = 0L
    
    // SPS/PPS data for decoder initialization
    @Volatile
    private var spsPpsData: ByteArray? = null
    
    // Frame queue for reverse streaming
    private val pendingFrames = java.util.concurrent.LinkedBlockingQueue<ByteArray>(10)
    
    @Volatile
    private var isSenderRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        logView = findViewById(R.id.log_view)
        logView.movementMethod = android.text.method.ScrollingMovementMethod()
        
        surfaceView = findViewById(R.id.surface_view)
        previewView = findViewById(R.id.preview_view)
        btnStreamBack = findViewById(R.id.btn_stream_back)
        rgConnectionMode = findViewById(R.id.rg_connection_mode)
        
        // Setup SurfaceView for video display
        surfaceView.setZOrderMediaOverlay(true)
        
        // Keep screen on during streaming
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
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
            log("Connection mode: $modeName")
            
            // Restart server with new mode if permissions granted
            if (checkPermissions()) {
                stopServer()
                when (connectionMode) {
                    ConnectionMode.L2CAP -> startGattServer()
                    ConnectionMode.WIFI -> startWiFiServer()
                    ConnectionMode.ROKID_SDK -> startRokidSDKServer()
                }
            }
        }
        
        // Setup surface callback for video rendering
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                synchronized(surfaceLock) {
                    surface = holder.surface
                    isSurfaceValid = true
                    surfaceVersion++
                    log("Surface created v$surfaceVersion - ready for rendering")
                    surfaceLock.notifyAll()
                }
            }
            
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                synchronized(surfaceLock) {
                    surface = holder.surface
                    isSurfaceValid = true
                    log("Surface changed: ${width}x${height}")
                    surfaceLock.notifyAll()
                }
            }
            
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                synchronized(surfaceLock) {
                    isSurfaceValid = false
                    surface = null
                    log("Surface destroyed v$surfaceVersion - marked as invalid")
                }
            }
        })
        
        // Reverse stream button
        btnStreamBack.setOnClickListener {
            if (!isReverseStreaming) {
                when (connectionMode) {
                    ConnectionMode.L2CAP -> {
                        if (reverseStreamSocket != null) {
                            startReverseStreaming()
                        } else {
                            log("No client connected for reverse streaming")
                        }
                    }
                    ConnectionMode.WIFI -> {
                        if (wifiStreamManager?.isClientConnected() == true) {
                            startReverseStreamingWiFi()
                        } else {
                            log("No WiFi client connected for reverse streaming")
                        }
                    }
                    ConnectionMode.ROKID_SDK -> {
                        if (rokidSDKManager?.isClientConnected() == true) {
                            startReverseStreamingSDK()
                        } else {
                            log("No SDK client connected for reverse streaming")
                        }
                    }
                }
            } else {
                stopReverseStreaming()
            }
        }
        btnStreamBack.isEnabled = false  // Enable when client connects

        // Initialize Bluetooth adapter
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter

        // Start server if permissions are granted
        if (checkPermissions()) {
            when (connectionMode) {
                ConnectionMode.L2CAP -> startGattServer()
                ConnectionMode.WIFI -> startWiFiServer()
                ConnectionMode.ROKID_SDK -> startRokidSDKServer()
            }
        } else {
            requestPermissions(
                arrayOf(
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.CAMERA
                ),
                1
            )
        }
    }
    
    /**
     * Setup Rokid SDK callbacks for receiver mode
     */
    private fun setupRokidSDKCallbacks() {
        rokidSDKManager?.setConnectionCallback(object : RokidSDKManager.ConnectionCallback {
            override fun onClientConnected(clientId: String) {
                log("SDK: Client connected - $clientId")
                runOnUiThread {
                    btnStreamBack.isEnabled = true
                    btnStreamBack.text = "Start Streaming to Phone (SDK)"
                }
            }
            
            override fun onClientDisconnected(reason: String) {
                log("SDK: Client disconnected - $reason")
                runOnUiThread {
                    btnStreamBack.isEnabled = false
                    btnStreamBack.text = "Start Streaming to Phone"
                }
            }
            
            override fun onAdvertisingStarted() {
                log("SDK: Advertising started, waiting for connections...")
            }
            
            override fun onError(error: String) {
                log("SDK Error: $error")
            }
        })
        
        rokidSDKManager?.setMessageCallback(object : RokidSDKManager.MessageCallback {
            override fun onMapMessageReceived(data: Map<String, Any>) {
                log("SDK: Received message: ${data.keys}")
            }
            
            override fun onBytesReceived(data: ByteArray) {
                // Handle received video frames from mobile app via SDK
                // This would decode and display video
            }
            
            override fun onVideoFrameReceived(frameData: ByteArray, timestamp: Long, isKeyFrame: Boolean) {
                // Handle video frame - decode and display
                // TODO: Implement SDK video frame decoding
            }
        })
    }
    
    /**
     * Start Rokid SDK server mode
     */
    private fun startRokidSDKServer() {
        log("Starting Rokid SDK server...")
        if (rokidSDKManager?.initialize() == true) {
            rokidSDKManager?.startAdvertising()
        } else {
            log("Failed to initialize Rokid SDK")
        }
    }
    
    /**
     * Setup WiFi stream manager callbacks
     */
    private fun setupWiFiCallbacks() {
        wifiStreamManager?.setConnectionCallback(object : WiFiStreamManager.ConnectionCallback {
            override fun onServerStarted(port: Int) {
                log("WiFi: Server started on port $port")
                runOnUiThread {
                    findViewById<TextView>(R.id.status_text)?.text = "WiFi Server on port $port"
                }
            }
            
            override fun onClientConnected(clientAddress: String) {
                log("WiFi: Client connected from $clientAddress")
                runOnUiThread {
                    btnStreamBack.isEnabled = true
                    btnStreamBack.text = "Start Streaming to Phone (WiFi)"
                    findViewById<TextView>(R.id.status_text)?.text = "Connected: $clientAddress"
                }
            }
            
            override fun onClientDisconnected() {
                log("WiFi: Client disconnected")
                runOnUiThread {
                    btnStreamBack.isEnabled = false
                    btnStreamBack.text = "Start Streaming to Phone"
                    findViewById<TextView>(R.id.status_text)?.text = "Waiting for connection..."
                }
            }
            
            override fun onError(error: String) {
                log("WiFi Error: $error")
            }
        })
        
        wifiStreamManager?.setStreamCallback(object : WiFiStreamManager.StreamCallback {
            override fun onVideoFrameReceived(frameData: ByteArray, timestamp: Long, isKeyFrame: Boolean) {
                // Handle video frame - queue for decoding
                handleReceivedVideoFrame(frameData, isKeyFrame)
            }
            
            override fun onControlMessageReceived(message: String) {
                log("WiFi: Control message - $message")
            }
        })
    }
    
    /**
     * Start WiFi server mode
     */
    private fun startWiFiServer() {
        log("Starting WiFi server...")
        wifiStreamManager?.startServer()
    }
    
    // Queue for WiFi/SDK received frames
    private val receivedFrameQueue = java.util.concurrent.LinkedBlockingQueue<Pair<ByteArray, Boolean>>(30)
    @Volatile
    private var isWiFiReceiverRunning = false
    
    /**
     * Handle received video frame from WiFi or SDK
     */
    private fun handleReceivedVideoFrame(frameData: ByteArray, isKeyFrame: Boolean) {
        if (!receivedFrameQueue.offer(Pair(frameData, isKeyFrame))) {
            receivedFrameQueue.poll()
            receivedFrameQueue.offer(Pair(frameData, isKeyFrame))
        }
        
        if (!isWiFiReceiverRunning) {
            isWiFiReceiverRunning = true
            Thread { processReceivedFrames() }.start()
        }
    }
    
    /**
     * Process received frames from WiFi/SDK
     */
    private fun processReceivedFrames() {
        val mimeType = "video/avc"
        var decoder: MediaCodec? = null
        var isDecoderConfigured = false
        var codecConfigData: ByteArray? = null
        val bufferInfo = MediaCodec.BufferInfo()
        
        log("WiFi/SDK receiver thread started")
        
        try {
            while (isRunning && isWiFiReceiverRunning) {
                val framePair = receivedFrameQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                    ?: continue
                
                val frameData = framePair.first
                val isKeyFrame = framePair.second
                
                framesReceived++
                
                val surfaceValid: Boolean
                val currentSurface: Surface?
                synchronized(surfaceLock) {
                    surfaceValid = isSurfaceValid
                    currentSurface = if (surfaceValid) surface else null
                }
                
                if (currentSurface == null || !surfaceValid) {
                    if (isCodecConfig(frameData)) {
                        codecConfigData = frameData.copyOf()
                    }
                    continue
                }
                
                if (decoder == null && currentSurface != null) {
                    if (isCodecConfig(frameData)) {
                        codecConfigData = frameData.copyOf()
                    }
                    
                    val configToUse = codecConfigData ?: if (isKeyFrame) frameData else null
                    if (configToUse != null) {
                        decoder = initializeDecoder(mimeType, currentSurface, configToUse)
                        if (decoder != null) {
                            isDecoderConfigured = true
                            log("WiFi/SDK decoder initialized")
                        }
                    }
                    continue
                }
                
                if (decoder != null && isDecoderConfigured) {
                    decodeFrame(decoder, frameData, bufferInfo)
                }
            }
        } catch (e: Exception) {
            log("WiFi/SDK receiver error: ${e.message}")
        } finally {
            decoder?.let { 
                try { it.stop(); it.release() } catch (e: Exception) { }
            }
            isWiFiReceiverRunning = false
            log("WiFi/SDK receiver thread stopped")
        }
    }
    
    /**
     * Start reverse streaming via WiFi
     */
    private fun startReverseStreamingWiFi() {
        log("Starting reverse streaming via WiFi...")
        isReverseStreaming = true
        
        runOnUiThread {
            btnStreamBack.text = "Stop Streaming"
            previewView.visibility = android.view.View.VISIBLE
        }
        
        // Start camera for reverse streaming
        startCamera()
        
        // Start sender thread for WiFi
        Thread { sendFramesOverWiFi() }.start()
    }
    
    /**
     * Send frames over WiFi
     */
    private fun sendFramesOverWiFi() {
        log("WiFi sender thread started")
        isSenderRunning = true
        
        try {
            while (isReverseStreaming && wifiStreamManager?.isClientConnected() == true) {
                val frameData = pendingFrames.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (frameData != null) {
                    val isKeyFrame = isKeyFrame(frameData)
                    wifiStreamManager?.sendVideoFrame(frameData, System.currentTimeMillis(), isKeyFrame)
                    framesSent++
                    
                    if (framesSent % 30 == 0) {
                        Log.d(TAG, "WiFi: Sent $framesSent frames")
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
     * Check if frame is a keyframe
     */
    private fun isKeyFrame(frameData: ByteArray): Boolean {
        if (frameData.size < 5) return false
        for (i in 0 until minOf(frameData.size - 4, 100)) {
            if (frameData[i] == 0x00.toByte() && frameData[i+1] == 0x00.toByte()) {
                val startCodeLen = if (frameData[i+2] == 0x01.toByte()) 3
                    else if (frameData[i+2] == 0x00.toByte() && frameData[i+3] == 0x01.toByte()) 4
                    else continue
                val nalType = (frameData[i + startCodeLen].toInt() and 0x1F)
                if (nalType == 5 || nalType == 7) return true
            }
        }
        return false
    }

    /**
     * Start reverse streaming via Rokid SDK
     */
    private fun startReverseStreamingSDK() {
        log("Starting reverse streaming via Rokid SDK...")
        isReverseStreaming = true
        
        runOnUiThread {
            btnStreamBack.text = "Stop Streaming"
            previewView.visibility = android.view.View.VISIBLE
        }
        
        // Start camera for reverse streaming
        startCamera()
        
        // Note: In SDK mode, frames are sent via rokidSDKManager.sendVideoFrame()
        // The encoder output needs to be directed to SDK instead of L2CAP socket
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        isWiFiReceiverRunning = false
        stopReverseStreaming()
        stopServer()
        rokidSDKManager?.release()
        wifiStreamManager?.release()
    }

    /**
     * Stops the GATT server and closes all connections
     */
    @SuppressLint("MissingPermission")
    private fun stopServer() {
        // Stop WiFi server
        wifiStreamManager?.stopServer()
        
        try {
            bluetoothAdapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping advertising: ${e.message}")
        }
        try {
            gattServer?.close()
            gattServer = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing GATT server: ${e.message}")
        }
        try {
            l2capServerSocket?.close()
            l2capServerSocket = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing L2CAP socket: ${e.message}")
        }
        try {
            l2capSendServerSocket?.close()
            l2capSendServerSocket = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing reverse L2CAP socket: ${e.message}")
        }
        try {
            reverseStreamSocket?.close()
            reverseStreamSocket = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing reverse stream socket: ${e.message}")
        }
    }

    /**
     * Starts the GATT server, L2CAP servers, and BLE advertising
     */
    @SuppressLint("MissingPermission")
    private fun startGattServer() {
        log("Starting GATT Server...")
        
        // Step 1: Open L2CAP Server Socket for receiving video FROM phone
        try {
            l2capServerSocket = bluetoothAdapter?.listenUsingInsecureL2capChannel()
            l2capPsm = l2capServerSocket?.psm ?: 0
            log("L2CAP Receive Server started on PSM: $l2capPsm")
        } catch (e: IOException) {
            log("Failed to open L2CAP receive socket: ${e.message}")
            return
        }
        
        // Step 2: Open L2CAP Server Socket for sending video TO phone
        try {
            l2capSendServerSocket = bluetoothAdapter?.listenUsingInsecureL2capChannel()
            l2capReversePsm = l2capSendServerSocket?.psm ?: 0
            log("L2CAP Send Server started on PSM: $l2capReversePsm")
        } catch (e: IOException) {
            log("Failed to open L2CAP send socket: ${e.message}")
            return
        }

        // Step 3: Setup GATT Server to expose both PSM values
        gattServer = bluetoothManager?.openGattServer(this, gattServerCallback)
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        
        // Characteristic for phone->glasses PSM
        val psmChar = BluetoothGattCharacteristic(
            PSM_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        service.addCharacteristic(psmChar)
        
        // Characteristic for glasses->phone PSM (reverse streaming)
        val reversePsmChar = BluetoothGattCharacteristic(
            REVERSE_PSM_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        service.addCharacteristic(reversePsmChar)
        
        gattServer?.addService(service)
        
        // Step 4: Start BLE Advertising
        val advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        val settings = android.bluetooth.le.AdvertiseSettings.Builder()
            .setAdvertiseMode(android.bluetooth.le.AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = android.bluetooth.le.AdvertiseData.Builder()
            .addServiceUuid(android.os.ParcelUuid(SERVICE_UUID))
            .setIncludeDeviceName(false)
            .build()
            
        advertiser?.startAdvertising(settings, data, advertiseCallback)
        log("BLE Advertising started")
        
        // Step 5: Start thread to accept L2CAP connections for receiving
        Thread {
            acceptReceiveConnections()
        }.start()
        
        // Step 6: Start thread to accept L2CAP connections for sending (reverse)
        Thread {
            acceptSendConnections()
        }.start()
    }

    /**
     * Accepts incoming L2CAP connections for receiving video FROM phone
     */
    private fun acceptReceiveConnections() {
        while (isRunning) {
            try {
                log("Waiting for L2CAP receive connection...")
                val socket = l2capServerSocket?.accept()
                socket?.let { 
                    Thread { handleVideoStream(it) }.start()
                }
            } catch (e: IOException) {
                if (isRunning) {
                    log("Receive accept failed: ${e.message}")
                }
                break
            }
        }
    }
    
    /**
     * Accepts incoming L2CAP connections for sending video TO phone
     */
    private fun acceptSendConnections() {
        while (isRunning) {
            try {
                log("Waiting for L2CAP send connection (reverse stream)...")
                val socket = l2capSendServerSocket?.accept()
                socket?.let {
                    log("Reverse stream client connected: ${it.remoteDevice?.address}")
                    reverseStreamSocket = it
                    runOnUiThread {
                        btnStreamBack.isEnabled = true
                        btnStreamBack.text = "Start Streaming to Phone"
                    }
                }
            } catch (e: IOException) {
                if (isRunning) {
                    log("Send accept failed: ${e.message}")
                }
                break
            }
        }
    }

    /**
     * Handles incoming video stream from phone (same as before)
     */
    @SuppressLint("MissingPermission")
    private fun handleVideoStream(socket: BluetoothSocket) {
        log("Client Connected (receive): ${socket.remoteDevice?.address}")
        
        framesReceived = 0
        framesDecoded = 0
        
        val mimeType = "video/avc"
        var decoder: MediaCodec? = null
        var currentSurface: Surface? = null
        var currentSurfaceVersion = 0L
        var isDecoderConfigured = false
        
        val inputStream = socket.inputStream
        val headerBuffer = ByteArray(4)
        val bufferInfo = MediaCodec.BufferInfo()
        
        var codecConfigData: ByteArray? = null
        var lastLogTime = System.currentTimeMillis()
        
        log("Waiting for surface to be ready...")
        synchronized(surfaceLock) {
            var waitCount = 0
            while (!isSurfaceValid && isRunning && waitCount < 50) {
                try {
                    surfaceLock.wait(100)
                    waitCount++
                } catch (e: InterruptedException) {
                    break
                }
            }
            if (isSurfaceValid) {
                currentSurface = surface
                currentSurfaceVersion = surfaceVersion
                log("Surface ready v$currentSurfaceVersion")
            }
        }

        try {
            while (isRunning) {
                if (!readFully(inputStream, headerBuffer, 4)) {
                    log("Connection closed by sender")
                    break
                }
                
                val length = ByteBuffer.wrap(headerBuffer)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .int

                if (length <= 0 || length > MAX_FRAME_SIZE) {
                    log("Invalid frame length: $length, resetting...")
                    continue
                }

                val frameData = ByteArray(length)
                if (!readFully(inputStream, frameData, length)) {
                    log("Failed to read frame data")
                    break
                }
                
                framesReceived++
                
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastLogTime > 5000) {
                    log("Received: $framesReceived frames, Decoded: $framesDecoded frames")
                    lastLogTime = currentTime
                }

                val surfaceValid: Boolean
                val newSurface: Surface?
                val newVersion: Long
                synchronized(surfaceLock) {
                    surfaceValid = isSurfaceValid
                    newSurface = if (surfaceValid) surface else null
                    newVersion = surfaceVersion
                }
                
                if (newSurface == null || !surfaceValid) {
                    if (isCodecConfig(frameData)) {
                        codecConfigData = frameData.copyOf()
                        log("Stored codec config while waiting for surface: ${frameData.size} bytes")
                    }
                    if (decoder != null) {
                        log("Surface invalid - releasing decoder")
                        releaseDecoder(decoder)
                        decoder = null
                        isDecoderConfigured = false
                        currentSurface = null
                        currentSurfaceVersion = 0L
                    }
                    continue
                }
                
                if (decoder != null && (newVersion != currentSurfaceVersion || newSurface != currentSurface)) {
                    log("Surface changed v$currentSurfaceVersion -> v$newVersion - Reinitializing decoder")
                    releaseDecoder(decoder)
                    decoder = null
                    isDecoderConfigured = false
                }
                currentSurface = newSurface
                currentSurfaceVersion = newVersion

                if (decoder == null && currentSurface != null && surfaceValid) {
                    if (isCodecConfig(frameData)) {
                        codecConfigData = frameData.copyOf()
                        log("Received codec config (SPS/PPS): ${frameData.size} bytes")
                    }
                    
                    Thread.sleep(50)
                    
                    val (stillValid, versionCheck) = synchronized(surfaceLock) { 
                        Pair(isSurfaceValid, surfaceVersion) 
                    }
                    if (!stillValid || versionCheck != currentSurfaceVersion) {
                        log("Surface changed during init delay - aborting init")
                        continue
                    }
                    
                    val surfaceForDecoder = currentSurface ?: continue
                    decoder = initializeDecoder(mimeType, surfaceForDecoder, codecConfigData)
                    if (decoder != null) {
                        isDecoderConfigured = true
                        log("Decoder initialized successfully for surface v$currentSurfaceVersion")
                    } else {
                        log("Decoder initialization failed, waiting for next frame...")
                        continue
                    }
                }

                if (decoder == null) {
                    if (framesReceived % 30 == 0) {
                        log("No decoder available, skipped $framesReceived frames")
                    }
                    continue
                }

                try {
                    val (stillValidForDecode, versionForDecode) = synchronized(surfaceLock) { 
                        Pair(isSurfaceValid, surfaceVersion) 
                    }
                    if (!stillValidForDecode || versionForDecode != currentSurfaceVersion) {
                        log("Surface changed before decode - resetting decoder")
                        releaseDecoder(decoder)
                        decoder = null
                        isDecoderConfigured = false
                        currentSurface = null
                        currentSurfaceVersion = 0L
                        continue
                    }
                    
                    if (decodeFrame(decoder, frameData, bufferInfo)) {
                        framesDecoded++
                    }
                } catch (e: IllegalStateException) {
                    log("Surface released during decode: ${e.message} - Resetting decoder")
                    releaseDecoder(decoder)
                    decoder = null
                    isDecoderConfigured = false
                    currentSurface = null
                } catch (e: Exception) {
                    log("Decoder error: ${e.message} - Resetting decoder")
                    releaseDecoder(decoder)
                    decoder = null
                    isDecoderConfigured = false
                }
            }
        } catch (e: Exception) {
            log("Video stream ended: ${e.message}")
        } finally {
            releaseDecoder(decoder)
            try { socket.close() } catch (e: Exception) {}
            log("Receive connection closed")
        }
    }

    // ===== Reverse Streaming (Glasses -> Phone) =====

    /**
     * Starts camera and reverse streaming to phone
     */
    private fun startReverseStreaming() {
        if (reverseStreamSocket == null) {
            log("No client connected for reverse streaming")
            return
        }
        
        isReverseStreaming = true
        framesSent = 0
        isFirstFrame.set(true)
        
        runOnUiThread {
            btnStreamBack.text = "Stop Streaming"
            previewView.visibility = android.view.View.VISIBLE
        }
        
        // Start camera
        startCamera()
        
        // Start sender thread
        reverseStreamSocket?.let { socket ->
            Thread { sendFramesToPhone(socket) }.start()
        }
        
        log("Reverse streaming started")
    }
    
    /**
     * Stops reverse streaming
     */
    private fun stopReverseStreaming() {
        isReverseStreaming = false
        
        // Clear pending frames
        pendingFrames.clear()
        
        // Stop encoder
        synchronized(encoderLock) {
            try {
                videoEncoder?.stop()
                videoEncoder?.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing encoder: ${e.message}")
            }
            videoEncoder = null
        }
        
        // Unbind camera
        runOnUiThread {
            try {
                val cameraProvider = ProcessCameraProvider.getInstance(this).get()
                cameraProvider.unbindAll()
                btnStreamBack.text = "Start Streaming to Phone"
                previewView.visibility = android.view.View.GONE
            } catch (e: Exception) {
                Log.e(TAG, "Error unbinding camera: ${e.message}")
            }
        }
        
        log("Reverse streaming stopped")
    }
    
    /**
     * Initializes camera for reverse streaming
     * 
     * Note: Rokid glasses camera may not report standard LENS_FACING attribute,
     * so we use a fallback strategy to select any available camera.
     */
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                
                // Configure preview
                val preview = Preview.Builder()
                    .setTargetResolution(android.util.Size(VIDEO_WIDTH, VIDEO_HEIGHT))
                    .build()
                preview.setSurfaceProvider(previewView.surfaceProvider)

                // Configure image analysis for frame capture
                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(android.util.Size(VIDEO_WIDTH, VIDEO_HEIGHT))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()

                val executor = Executors.newSingleThreadExecutor()
                imageAnalysis.setAnalyzer(executor) { image ->
                    processFrame(image)
                }

                // Bind to lifecycle with fallback strategy for Rokid glasses
                cameraProvider.unbindAll()
                
                // Try different camera selection strategies
                val cameraSelectorStrategies = listOf(
                    // Strategy 1: Try back camera (standard)
                    { CameraSelector.DEFAULT_BACK_CAMERA },
                    // Strategy 2: Try front camera (standard)
                    { CameraSelector.DEFAULT_FRONT_CAMERA },
                    // Strategy 3: Use first available camera by ID (for Rokid glasses)
                    { 
                        CameraSelector.Builder()
                            .addCameraFilter { cameras ->
                                if (cameras.isNotEmpty()) listOf(cameras.first()) else cameras
                            }
                            .build()
                    }
                )
                
                var cameraBindSuccess = false
                for ((index, selectorFactory) in cameraSelectorStrategies.withIndex()) {
                    if (cameraBindSuccess) break
                    
                    try {
                        val cameraSelector = selectorFactory()
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            this,
                            cameraSelector,
                            preview,
                            imageAnalysis
                        )
                        log("Camera started for reverse streaming (strategy $index)")
                        cameraBindSuccess = true
                    } catch (e: Exception) {
                        Log.w(TAG, "Camera bind strategy $index failed: ${e.message}")
                    }
                }
                
                if (!cameraBindSuccess) {
                    log("Camera initialization failed: no camera available")
                }
            } catch (e: Exception) {
                log("Camera initialization failed: ${e.message}")
            }
        }, androidx.core.content.ContextCompat.getMainExecutor(this))
    }
    
    /**
     * Processes each camera frame for encoding
     */
    private fun processFrame(image: ImageProxy) {
        try {
            if (!isReverseStreaming) {
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

            encodeFrame(image)
        } finally {
            image.close()
        }
    }
    
    /**
     * Encodes a single camera frame
     */
    private fun encodeFrame(image: ImageProxy) {
        var encoder = videoEncoder
        if (encoder == null) {
            synchronized(encoderLock) {
                if (videoEncoder == null) {
                    encoder = initializeEncoder(image.width, image.height)
                    if (encoder == null) return
                    videoEncoder = encoder
                    
                    // Start encoder drain thread
                    Thread { drainEncoderOutput() }.start()
                } else {
                    encoder = videoEncoder
                }
            }
        }

        val safeEncoder = encoder ?: return

        try {
            val inputIndex = safeEncoder.dequeueInputBuffer(10000)
            if (inputIndex >= 0) {
                val inputBuffer = safeEncoder.getInputBuffer(inputIndex) ?: return
                inputBuffer.clear()

                val dataSize = fillNV12Buffer(image, inputBuffer)
                
                val presentationTimeUs = System.nanoTime() / 1000
                safeEncoder.queueInputBuffer(inputIndex, 0, dataSize, presentationTimeUs, 0)

                if (isFirstFrame.compareAndSet(true, false)) {
                    log("First frame queued for reverse encoding: ${image.width}x${image.height}")
                }
            }
        } catch (e: Exception) {
            log("Frame encoding error: ${e.message}")
        }
    }
    
    /**
     * Initializes H.264 encoder
     */
    private fun initializeEncoder(width: Int, height: Int): MediaCodec? {
        try {
            log("Initializing H.264 encoder: ${width}x${height}")
            
            val encoder = MediaCodec.createEncoderByType("video/avc")
            val format = MediaFormat.createVideoFormat("video/avc", width, height)
            
            format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
            )
            format.setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FRAME_RATE)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VIDEO_I_FRAME_INTERVAL)
            
            format.setInteger(
                MediaFormat.KEY_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
            )
            format.setInteger(
                MediaFormat.KEY_LEVEL,
                MediaCodecInfo.CodecProfileLevel.AVCLevel31
            )
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            
            format.setInteger(MediaFormat.KEY_BITRATE_MODE, 
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)

            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            
            log("Encoder initialized successfully")
            return encoder
        } catch (e: Exception) {
            log("Encoder initialization failed: ${e.message}")
            return null
        }
    }
    
    /**
     * Converts YUV_420_888 to NV12 format
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

        // Copy Y plane
        if (yRowStride == width) {
            val ySize = width * height
            yBuffer.position(0)
            val yBytes = ByteArray(minOf(ySize, yBuffer.remaining()))
            yBuffer.get(yBytes)
            buffer.put(yBytes)
            position += yBytes.size
        } else {
            val rowBytes = ByteArray(width)
            for (row in 0 until height) {
                yBuffer.position(row * yRowStride)
                yBuffer.get(rowBytes, 0, minOf(width, yBuffer.remaining()))
                buffer.put(rowBytes)
                position += width
            }
        }

        // Copy UV plane
        val uvHeight = height / 2
        val uvWidth = width / 2

        if (uvPixelStride == 2 && uvRowStride == width) {
            uBuffer.position(0)
            val uvSize = width * uvHeight
            val uvBytes = ByteArray(minOf(uvSize, uBuffer.remaining()))
            uBuffer.get(uvBytes)
            buffer.put(uvBytes)
            position += uvBytes.size
        } else {
            for (row in 0 until uvHeight) {
                for (col in 0 until uvWidth) {
                    val uvIndex = row * uvRowStride + col * uvPixelStride
                    
                    if (uvIndex < uBuffer.limit()) {
                        buffer.put(uBuffer.get(uvIndex))
                        position++
                    }
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
     * Drains encoder output and queues frames for sending
     */
    private fun drainEncoderOutput() {
        val bufferInfo = MediaCodec.BufferInfo()
        log("Starting encoder drain thread")
        var droppedFrames = 0

        try {
            while (isReverseStreaming) {
                val encoder = videoEncoder ?: break
                
                val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000)
                
                when {
                    outputIndex >= 0 -> {
                        val outputBuffer = encoder.getOutputBuffer(outputIndex)
                        if (outputBuffer != null) {
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                val configData = ByteArray(bufferInfo.size)
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.get(configData)
                                spsPpsData = configData
                                log("Received codec config (SPS/PPS): ${bufferInfo.size} bytes")
                                
                                if (!pendingFrames.offer(configData)) {
                                    pendingFrames.poll()
                                    pendingFrames.offer(configData)
                                }
                            } else if (bufferInfo.size > 0) {
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                
                                val frameData = ByteArray(bufferInfo.size)
                                outputBuffer.get(frameData)
                                
                                val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                                if (isKeyFrame) {
                                    spsPpsData?.let { sps ->
                                        if (!pendingFrames.offer(sps)) {
                                            pendingFrames.poll()
                                            pendingFrames.offer(sps)
                                        }
                                    }
                                }
                                
                                if (!pendingFrames.offer(frameData)) {
                                    pendingFrames.poll()
                                    pendingFrames.offer(frameData)
                                    droppedFrames++
                                    if (droppedFrames % 10 == 0) {
                                        Log.d(TAG, "Dropped $droppedFrames frames due to slow Bluetooth")
                                    }
                                }
                            }
                        }
                        
                        encoder.releaseOutputBuffer(outputIndex, false)
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = encoder.outputFormat
                        log("Encoder output format changed: $newFormat")
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
     * Sends frames to phone over reverse L2CAP channel
     */
    private fun sendFramesToPhone(socket: BluetoothSocket) {
        val outputStream = socket.outputStream
        val headerBuffer = ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        
        log("Starting reverse stream sender thread")
        isSenderRunning = true
        
        try {
            while (isReverseStreaming) {
                val frameData = pendingFrames.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (frameData != null) {
                    sendFrame(outputStream, headerBuffer, frameData)
                    framesSent++
                    
                    if (framesSent % 30 == 0) {
                        Log.d(TAG, "Reverse sent $framesSent frames, pending: ${pendingFrames.size}")
                    }
                }
            }
        } catch (e: IOException) {
            log("Reverse stream disconnected: ${e.message}")
        } catch (e: Exception) {
            log("Reverse sender error: ${e.message}")
        } finally {
            isSenderRunning = false
            log("Reverse sender thread stopped")
        }
    }
    
    /**
     * Sends a single frame with length prefix
     */
    private fun sendFrame(outputStream: OutputStream, headerBuffer: ByteBuffer, data: ByteArray) {
        headerBuffer.clear()
        headerBuffer.putInt(data.size)
        outputStream.write(headerBuffer.array())
        outputStream.write(data)
        outputStream.flush()
    }

    // ===== Helper Methods =====

    private fun readFully(inputStream: InputStream, buffer: ByteArray, length: Int): Boolean {
        var read = 0
        while (read < length) {
            val n = inputStream.read(buffer, read, length - read)
            if (n == -1) return false
            read += n
        }
        return true
    }

    private fun isCodecConfig(data: ByteArray): Boolean {
        if (data.size < 5) return false
        
        val startIndex = when {
            data.size > 4 && data[0] == 0.toByte() && data[1] == 0.toByte() && 
                data[2] == 0.toByte() && data[3] == 1.toByte() -> 4
            data.size > 3 && data[0] == 0.toByte() && data[1] == 0.toByte() && 
                data[2] == 1.toByte() -> 3
            else -> 0
        }
        
        if (startIndex >= data.size) return false
        
        val nalType = data[startIndex].toInt() and 0x1F
        return nalType == 7 || nalType == 8
    }

    private fun initializeDecoder(mimeType: String, surface: Surface, configData: ByteArray?): MediaCodec? {
        return try {
            log("Initializing H.264 decoder...")
            val decoder = MediaCodec.createDecoderByType(mimeType)
            val format = MediaFormat.createVideoFormat(mimeType, VIDEO_WIDTH, VIDEO_HEIGHT)
            
            configData?.let {
                format.setByteBuffer("csd-0", ByteBuffer.wrap(it))
            }
            
            decoder.configure(format, surface, null, 0)
            decoder.start()
            decoder
        } catch (e: Exception) {
            log("Decoder initialization failed: ${e.message}")
            null
        }
    }

    private fun decodeFrame(decoder: MediaCodec, frameData: ByteArray, bufferInfo: MediaCodec.BufferInfo): Boolean {
        val inputIndex = decoder.dequeueInputBuffer(10000)
        if (inputIndex >= 0) {
            val inputBuffer = decoder.getInputBuffer(inputIndex)
            inputBuffer?.clear()
            inputBuffer?.put(frameData)
            
            val flags = if (isCodecConfig(frameData)) {
                MediaCodec.BUFFER_FLAG_CODEC_CONFIG
            } else {
                0
            }
            
            decoder.queueInputBuffer(inputIndex, 0, frameData.size, System.nanoTime() / 1000, flags)
        } else {
            return false
        }

        return drainDecoderOutput(decoder, bufferInfo)
    }

    private fun drainDecoderOutput(decoder: MediaCodec, bufferInfo: MediaCodec.BufferInfo): Boolean {
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
                    val newFormat = decoder.outputFormat
                    Log.d(TAG, "Decoder output format changed: $newFormat")
                    outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 0)
                }
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    break
                }
                else -> {
                    break
                }
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

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic?
        ) {
            when (characteristic?.uuid) {
                PSM_CHAR_UUID -> {
                    val value = ByteBuffer.allocate(4)
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        .putInt(l2capPsm)
                        .array()
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
                    log("Sent receive PSM $l2capPsm to ${device?.address}")
                }
                REVERSE_PSM_CHAR_UUID -> {
                    val value = ByteBuffer.allocate(4)
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        .putInt(l2capReversePsm)
                        .array()
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
                    log("Sent reverse PSM $l2capReversePsm to ${device?.address}")
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            val state = if (newState == BluetoothProfile.STATE_CONNECTED) "connected" else "disconnected"
            log("GATT client ${device?.address} $state")
        }
    }

    private val advertiseCallback = object : android.bluetooth.le.AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: android.bluetooth.le.AdvertiseSettings?) {
            log("BLE Advertising started successfully")
        }
        
        override fun onStartFailure(errorCode: Int) {
            val errorMsg = when (errorCode) {
                ADVERTISE_FAILED_DATA_TOO_LARGE -> "Data too large"
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Too many advertisers"
                ADVERTISE_FAILED_ALREADY_STARTED -> "Already started"
                ADVERTISE_FAILED_INTERNAL_ERROR -> "Internal error"
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported"
                else -> "Unknown error"
            }
            log("BLE Advertising failed: $errorMsg (code: $errorCode)")
        }
    }

    private fun checkPermissions(): Boolean {
        val advertisePermission = ActivityCompat.checkSelfPermission(
            this, Manifest.permission.BLUETOOTH_ADVERTISE
        ) == PackageManager.PERMISSION_GRANTED
        val connectPermission = ActivityCompat.checkSelfPermission(
            this, Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
        val cameraPermission = ActivityCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        return advertisePermission && connectPermission && cameraPermission
    }

    private fun log(msg: String) {
        Log.d(TAG, msg)
        runOnUiThread {
            logView.append("$msg\n")
            val scrollView = logView.parent as? android.widget.ScrollView
            scrollView?.fullScroll(android.widget.ScrollView.FOCUS_DOWN)
        }
    }
}
