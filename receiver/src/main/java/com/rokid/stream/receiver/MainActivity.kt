package com.rokid.stream.receiver

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.io.IOException
import java.io.InputStream
import java.util.*
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteBuffer

/**
 * MainActivity - Receiver application for displaying video stream from Bluetooth
 * 
 * This app receives H.264 encoded video frames via Bluetooth Low Energy L2CAP channel
 * and decodes/displays them on a SurfaceView. Designed for Rokid glasses or similar devices.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private lateinit var surfaceView: SurfaceView
    
    // Bluetooth components
    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var gattServer: BluetoothGattServer? = null
    private var l2capServerSocket: BluetoothServerSocket? = null
    
    // Flag to control server lifecycle
    @Volatile
    private var isRunning = true

    companion object {
        const val TAG = "RokidReceiver"
        
        // Custom service UUID - must match sender
        val SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        
        // Characteristic UUID for PSM value - must match sender
        val PSM_CHAR_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        
        // Video dimensions - must match sender
        const val VIDEO_WIDTH = 720
        const val VIDEO_HEIGHT = 720
        
        // Maximum expected frame size (for validation)
        const val MAX_FRAME_SIZE = 1_000_000  // 1MB to handle larger frames
    }

    // L2CAP PSM (Protocol/Service Multiplexer) assigned by system
    private var l2capPsm: Int = 0
    
    // Frame counters for debugging
    private var framesReceived = 0
    private var framesDecoded = 0
    
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        logView = findViewById(R.id.log_view)
        // Enable scrolling for log view
        logView.movementMethod = android.text.method.ScrollingMovementMethod()
        surfaceView = findViewById(R.id.surface_view)
        
        // CRITICAL: Ensure SurfaceView is on top and visible for video rendering
        // setZOrderOnTop places surface above the window, but covers overlays
        // setZOrderMediaOverlay is better - shows video but allows overlays on top
        surfaceView.setZOrderMediaOverlay(true)
        
        // Keep screen on during streaming
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
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
                    // Don't increment version on change, only on create/destroy cycle
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

        // Initialize Bluetooth adapter
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter

        // Start GATT server if permissions are granted
        if (checkPermissions()) {
            startGattServer()
        } else {
            requestPermissions(
                arrayOf(
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT
                ),
                1
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up resources
        isRunning = false
        stopServer()
    }

    /**
     * Stops the GATT server and closes all connections
     */
    @SuppressLint("MissingPermission")
    private fun stopServer() {
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
    }

    /**
     * Starts the GATT server, L2CAP server, and BLE advertising
     */
    @SuppressLint("MissingPermission")
    private fun startGattServer() {
        log("Starting GATT Server...")
        
        // Step 1: Open L2CAP Server Socket for video data transfer
        try {
            // Using insecure channel for faster setup (suitable for video streaming)
            l2capServerSocket = bluetoothAdapter?.listenUsingInsecureL2capChannel()
            l2capPsm = l2capServerSocket?.psm ?: 0
            log("L2CAP Server started on PSM: $l2capPsm")
        } catch (e: IOException) {
            log("Failed to open L2CAP socket: ${e.message}")
            return
        }

        // Step 2: Setup GATT Server to expose PSM to connecting clients
        gattServer = bluetoothManager?.openGattServer(this, gattServerCallback)
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        
        // Characteristic to allow clients to read PSM value
        val psmChar = BluetoothGattCharacteristic(
            PSM_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        service.addCharacteristic(psmChar)
        gattServer?.addService(service)
        
        // Step 3: Start BLE Advertising so sender can find us
        val advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        val settings = android.bluetooth.le.AdvertiseSettings.Builder()
            .setAdvertiseMode(android.bluetooth.le.AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)  // Advertise indefinitely
            .build()
        val data = android.bluetooth.le.AdvertiseData.Builder()
            .addServiceUuid(android.os.ParcelUuid(SERVICE_UUID))
            .setIncludeDeviceName(false)  // Keep advertising packet small
            .build()
            
        advertiser?.startAdvertising(settings, data, advertiseCallback)
        log("BLE Advertising started")
        
        // Step 4: Start thread to accept L2CAP connections
        Thread {
            acceptConnections()
        }.start()
    }

    /**
     * Accepts incoming L2CAP connections in a loop
     */
    private fun acceptConnections() {
        while (isRunning) {
            try {
                log("Waiting for L2CAP client connection...")
                val socket = l2capServerSocket?.accept()
                socket?.let { 
                    // Handle each connection in a new thread
                    Thread { handleVideoStream(it) }.start()
                }
            } catch (e: IOException) {
                if (isRunning) {
                    log("Accept failed: ${e.message}")
                }
                break
            }
        }
    }



    /**
     * Handles incoming video stream from a connected client
     * Receives H.264 frames and decodes them for display
     */
    @SuppressLint("MissingPermission")
    private fun handleVideoStream(socket: BluetoothSocket) {
        log("Client Connected: ${socket.remoteDevice?.address}")
        
        // Reset counters for new connection
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
        
        // Buffer to accumulate codec config (SPS/PPS) data
        var codecConfigData: ByteArray? = null
        var lastLogTime = System.currentTimeMillis()
        
        // Wait for initial surface to be ready before processing frames
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
                // Step 1: Read frame header (4 bytes = frame length)
                if (!readFully(inputStream, headerBuffer, 4)) {
                    log("Connection closed by sender")
                    break
                }
                
                val length = ByteBuffer.wrap(headerBuffer)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .int

                // Validate frame length
                if (length <= 0 || length > MAX_FRAME_SIZE) {
                    log("Invalid frame length: $length, resetting...")
                    continue
                }

                // Step 2: Read frame data
                val frameData = ByteArray(length)
                if (!readFully(inputStream, frameData, length)) {
                    log("Failed to read frame data")
                    break
                }
                
                framesReceived++
                
                // Periodic logging for debugging
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastLogTime > 5000) {
                    log("Received: $framesReceived frames, Decoded: $framesDecoded frames")
                    lastLogTime = currentTime
                }

                // Step 3: Check if surface is valid and available
                val surfaceValid: Boolean
                val newSurface: Surface?
                val newVersion: Long
                synchronized(surfaceLock) {
                    surfaceValid = isSurfaceValid
                    newSurface = if (surfaceValid) surface else null
                    newVersion = surfaceVersion
                }
                
                if (newSurface == null || !surfaceValid) {
                    // No valid surface available, skip frame but store codec config
                    if (isCodecConfig(frameData)) {
                        codecConfigData = frameData.copyOf()
                        log("Stored codec config while waiting for surface: ${frameData.size} bytes")
                    }
                    // If we have a decoder but surface became invalid, release it
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
                
                // Check if surface version changed (surface was destroyed and recreated)
                if (decoder != null && (newVersion != currentSurfaceVersion || newSurface != currentSurface)) {
                    log("Surface changed v$currentSurfaceVersion -> v$newVersion - Reinitializing decoder")
                    releaseDecoder(decoder)
                    decoder = null
                    isDecoderConfigured = false
                }
                currentSurface = newSurface
                currentSurfaceVersion = newVersion

                // Step 4: Initialize decoder if needed
                if (decoder == null && currentSurface != null && surfaceValid) {
                    // Check if this is codec config data
                    if (isCodecConfig(frameData)) {
                        codecConfigData = frameData.copyOf()
                        log("Received codec config (SPS/PPS): ${frameData.size} bytes")
                    }
                    
                    // Wait a moment for surface to stabilize (debounce rapid create/destroy)
                    Thread.sleep(50)
                    
                    // Double-check surface is still valid AND version hasn't changed
                    val (stillValid, versionCheck) = synchronized(surfaceLock) { 
                        Pair(isSurfaceValid, surfaceVersion) 
                    }
                    if (!stillValid || versionCheck != currentSurfaceVersion) {
                        log("Surface changed during init delay - aborting init")
                        continue
                    }
                    
                    // Capture surface in local val for safe use
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

                // Skip frame if no decoder available
                if (decoder == null) {
                    if (framesReceived % 30 == 0) {
                        log("No decoder available, skipped $framesReceived frames")
                    }
                    continue
                }

                // Step 5: Decode and render the frame
                try {
                    // Check surface is still valid and version matches before decoding
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
                    // Surface was released during decode
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
            log("Connection closed")
        }
    }

    /**
     * Reads exactly 'length' bytes from input stream into buffer
     * @return true if successful, false if EOF reached
     */
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
     * Checks if the NAL unit is codec configuration data (SPS or PPS)
     * NAL unit type is in the lower 5 bits of the first byte after start code
     */
    private fun isCodecConfig(data: ByteArray): Boolean {
        if (data.size < 5) return false
        
        // Find NAL unit type (skip start code 0x00 0x00 0x00 0x01 or 0x00 0x00 0x01)
        val startIndex = when {
            data.size > 4 && data[0] == 0.toByte() && data[1] == 0.toByte() && 
                data[2] == 0.toByte() && data[3] == 1.toByte() -> 4
            data.size > 3 && data[0] == 0.toByte() && data[1] == 0.toByte() && 
                data[2] == 1.toByte() -> 3
            else -> 0
        }
        
        if (startIndex >= data.size) return false
        
        val nalType = data[startIndex].toInt() and 0x1F
        // NAL type 7 = SPS, 8 = PPS
        return nalType == 7 || nalType == 8
    }

    /**
     * Initializes the H.264 decoder
     */
    private fun initializeDecoder(mimeType: String, surface: Surface, configData: ByteArray?): MediaCodec? {
        return try {
            log("Initializing H.264 decoder...")
            val decoder = MediaCodec.createDecoderByType(mimeType)
            val format = MediaFormat.createVideoFormat(mimeType, VIDEO_WIDTH, VIDEO_HEIGHT)
            
            // Provide codec config data if available (helps decoder initialize faster)
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

    /**
     * Decodes a single H.264 frame and renders it to surface
     * @return true if frame was decoded and rendered
     */
    private fun decodeFrame(decoder: MediaCodec, frameData: ByteArray, bufferInfo: MediaCodec.BufferInfo): Boolean {
        // Queue input buffer
        val inputIndex = decoder.dequeueInputBuffer(10000)  // 10ms timeout
        if (inputIndex >= 0) {
            val inputBuffer = decoder.getInputBuffer(inputIndex)
            inputBuffer?.clear()
            inputBuffer?.put(frameData)
            
            // Determine if this is codec config
            val flags = if (isCodecConfig(frameData)) {
                MediaCodec.BUFFER_FLAG_CODEC_CONFIG
            } else {
                0
            }
            
            decoder.queueInputBuffer(inputIndex, 0, frameData.size, System.nanoTime() / 1000, flags)
        } else {
            return false
        }

        // Drain output buffers and render
        return drainDecoderOutput(decoder, bufferInfo)
    }

    /**
     * Drains all available output buffers from decoder
     * @return true if any frame was rendered
     */
    private fun drainDecoderOutput(decoder: MediaCodec, bufferInfo: MediaCodec.BufferInfo): Boolean {
        var rendered = false
        var outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000)
        
        while (true) {
            when {
                outputIndex >= 0 -> {
                    // Release buffer with render=true to display on surface
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

    /**
     * Safely releases the decoder
     */
    private fun releaseDecoder(decoder: MediaCodec?) {
        try {
            decoder?.stop()
            decoder?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing decoder: ${e.message}")
        }
    }

    /**
     * GATT server callback to handle characteristic read requests
     */
    private val gattServerCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic?
        ) {
            if (characteristic?.uuid == PSM_CHAR_UUID) {
                // Send PSM value as little-endian 32-bit integer
                val value = ByteBuffer.allocate(4)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .putInt(l2capPsm)
                    .array()
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
                log("Sent PSM $l2capPsm to ${device?.address}")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            val state = if (newState == BluetoothProfile.STATE_CONNECTED) "connected" else "disconnected"
            log("GATT client ${device?.address} $state")
        }
    }

    /**
     * BLE advertising callback
     */
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

    /**
     * Checks if required Bluetooth permissions are granted
     */
    private fun checkPermissions(): Boolean {
        val advertisePermission = ActivityCompat.checkSelfPermission(
            this, Manifest.permission.BLUETOOTH_ADVERTISE
        ) == PackageManager.PERMISSION_GRANTED
        val connectPermission = ActivityCompat.checkSelfPermission(
            this, Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
        return advertisePermission && connectPermission
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
