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
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaCodecInfo

/**
 * MainActivity - Sender application for streaming camera video via Bluetooth L2CAP
 * 
 * This app captures camera frames, encodes them as H.264 video, and transmits
 * over Bluetooth Low Energy L2CAP channel to a receiver device (e.g., Rokid glasses).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private lateinit var btnConnect: Button
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    @Volatile
    private var l2capSocket: BluetoothSocket? = null
    
    // Atomic flag to track first frame for logging purposes
    private val isFirstFrame = AtomicBoolean(true)
    
    // Flag to indicate if streaming is active
    @Volatile
    private var isStreaming = false
    
    // Flag to prevent multiple connection attempts
    @Volatile
    private var isConnecting = false
    
    // Encoder lock to prevent race conditions during initialization
    private val encoderLock = Object()
    
    // Frame counter for periodic logging
    private var framesSent = 0

    companion object {
        const val TAG = "RokidSender"
        
        // Custom service UUID for Rokid streaming service
        val SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        
        // Characteristic UUID for reading PSM (Protocol/Service Multiplexer) value
        val PSM_CHAR_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        
        // Video encoding parameters (actual dimensions come from camera)
        // These are preferred dimensions, camera may provide different ones
        const val VIDEO_WIDTH = 720
        const val VIDEO_HEIGHT = 720
        const val VIDEO_BITRATE = 300_000  // 300 Kbps - reduced for BLE bandwidth
        const val VIDEO_FRAME_RATE = 10    // 10 FPS - reduced for BLE bandwidth
        const val VIDEO_I_FRAME_INTERVAL = 1  // Keyframe every 1 second
        const val FRAME_INTERVAL_MS = 100L  // ~10 FPS (1000ms / 10)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        logView = findViewById(R.id.log_view)
        btnConnect = findViewById(R.id.btn_connect)

        // Initialize Bluetooth adapter
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        btnConnect.setOnClickListener {
            // Prevent multiple connection attempts
            if (isConnecting || isStreaming) {
                log("Already connecting or streaming, please wait...")
                return@setOnClickListener
            }
            
            if (checkPermissions()) {
                isConnecting = true
                btnConnect.isEnabled = false
                startScan()
            } else {
                // Request required permissions for Bluetooth and Camera
                requestPermissions(
                    arrayOf(
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.CAMERA
                    ),
                    1
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up resources when activity is destroyed
        stopStreaming()
    }

    /**
     * Stops all streaming operations and releases resources
     */
    private fun stopStreaming() {
        isStreaming = false
        isConnecting = false
        framesSent = 0
        isFirstFrame.set(true)
        spsPpsData = null
        
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
            bluetoothGatt?.close()
            bluetoothGatt = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing GATT: ${e.message}")
        }
        
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
                log("Reading PSM characteristic...")
                gatt.readCharacteristic(psmChar)
            } else {
                log("Service or PSM characteristic not found!")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Failed to read PSM characteristic")
                return
            }
            
            if (characteristic.uuid == PSM_CHAR_UUID) {
                // Parse PSM value from little-endian bytes
                val psm = ByteBuffer.wrap(characteristic.value)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .int
                log("PSM value received: $psm")
                connectL2cap(gatt.device, psm)
            }
        }
    }
    /**
     * Connects to the receiver via L2CAP channel for video streaming
     * L2CAP provides a reliable, connection-oriented channel over BLE
     */
    @SuppressLint("MissingPermission")
    private fun connectL2cap(device: BluetoothDevice, psm: Int) {
        Thread {
            try {
                log("Connecting L2CAP to PSM $psm...")
                val socket = device.createInsecureL2capChannel(psm)
                socket.connect()
                l2capSocket = socket
                isStreaming = true
                log("L2CAP Connected! Starting video streaming...")
                isConnecting = false
                
                // Start camera and video encoding on UI thread
                runOnUiThread { 
                    btnConnect.isEnabled = false  // Keep disabled while streaming
                    startCamera() 
                }
            } catch (e: IOException) {
                log("L2CAP Connection Failed: ${e.message}")
                isConnecting = false
                runOnUiThread { btnConnect.isEnabled = true }
                stopStreaming()
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
    // Reduced size to 10 - prefer dropping frames over building up latency
    private val pendingFrames = java.util.concurrent.LinkedBlockingQueue<ByteArray>(10)
    
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
                    l2capSocket?.let { socket ->
                        Thread { drainEncoderOutput() }.start()
                        // Start separate Bluetooth sender thread
                        Thread { sendFramesOverBluetooth(socket) }.start()
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

    /**
     * Initializes the H.264 video encoder with appropriate settings for Bluetooth streaming
     */
    private fun initializeEncoder(width: Int, height: Int): MediaCodec? {
        try {
            log("Initializing H.264 encoder: ${width}x${height}")
            
            val encoder = MediaCodec.createEncoderByType("video/avc")
            val format = MediaFormat.createVideoFormat("video/avc", width, height)
            
            // Configure encoder format
            format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
            )
            format.setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FRAME_RATE)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VIDEO_I_FRAME_INTERVAL)
            
            // Set encoder profile for better compatibility
            format.setInteger(
                MediaFormat.KEY_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
            )
            format.setInteger(
                MediaFormat.KEY_LEVEL,
                MediaCodecInfo.CodecProfileLevel.AVCLevel31
            )
            
            // Enable low latency mode if supported (API 30+)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            
            // Set CBR (Constant Bit Rate) mode for consistent streaming
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
        val locationPermission = ActivityCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val cameraPermission = ActivityCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        return connectPermission && scanPermission && locationPermission && cameraPermission
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
