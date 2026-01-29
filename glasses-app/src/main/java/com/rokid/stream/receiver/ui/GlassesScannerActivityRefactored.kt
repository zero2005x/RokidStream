package com.rokid.stream.receiver.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface as ComposeSurface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.rokid.stream.receiver.ui.theme.RokidStreamTheme
import com.rokid.stream.receiver.util.LocaleManager
import com.rokid.stream.receiver.viewmodel.GlassesScannerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * Glasses Activity with Bluetooth Device Scanner
 * 
 * Refactored to use MVVM architecture with ViewModel.
 * 
 * This activity scans for phones running the RokidStream app
 * and allows the user to select which phone to connect to.
 * 
 * The phone controls all streaming settings (connection mode, direction).
 * The glasses just need to:
 * 1. Scan for available phones
 * 2. Select and connect to a phone
 * 3. Display the video stream
 */
class GlassesScannerActivityRefactored : ComponentActivity() {
    
    companion object {
        private const val TAG = "GlassesScanner"
        
        // Codec MIME types for H.265/H.264 support
        private const val MIME_TYPE_HEVC = MediaFormat.MIMETYPE_VIDEO_HEVC  // "video/hevc"
        private const val MIME_TYPE_AVC = MediaFormat.MIMETYPE_VIDEO_AVC    // "video/avc"
    }
    
    // ViewModel (using AndroidX ViewModel)
    private val viewModel: GlassesScannerViewModel by viewModels()
    
    // Bluetooth adapter
    private var bluetoothAdapter: BluetoothAdapter? = null
    
    // L2CAP and video components (Activity-specific, not in ViewModel)
    private var l2capSocket: BluetoothSocket? = null
    private var videoDecoder: MediaCodec? = null
    private var videoSurface: Surface? = null
    @Volatile
    private var isStreaming = false
    private var framesReceived = 0
    private var framesDecoded = 0
    
    // Codec detection for H.265/H.264 auto-detection
    private var detectedCodecType: String? = null  // Will be set from first frame
    
    // Permission launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            viewModel.startScan()
        } else {
            Log.w(TAG, "Bluetooth permissions not granted")
        }
    }
    
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.applyLocale(newBase))
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Force portrait orientation for Rokid glasses
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        
        // Initialize Bluetooth
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        viewModel.initBluetooth(bluetoothAdapter)
        
        // Setup ViewModel callbacks
        setupViewModelCallbacks()
        
        setContent {
            RokidStreamTheme {
                ComposeSurface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Collect state from ViewModel using StateFlow
                    val state by viewModel.state.collectAsState()
                    val scannedDevices by viewModel.scannedDevices.collectAsState()
                    val isScanning by viewModel.isScanning.collectAsState()
                    val connectedDeviceName by viewModel.connectedDeviceName.collectAsState()
                    
                    GlassesScannerScreen(
                        state = state,
                        scannedDevices = scannedDevices,
                        connectedDeviceName = connectedDeviceName,
                        isScanning = isScanning,
                        onStartScan = { checkPermissionsAndScan() },
                        onStopScan = { viewModel.stopScan() },
                        onDeviceSelected = { device -> 
                            viewModel.connectToDevice(this@GlassesScannerActivityRefactored, device) 
                        },
                        onDisconnect = { disconnect() },
                        videoView = {
                            // Video SurfaceView for H.264 decoding
                            AndroidView(
                                factory = { context ->
                                    SurfaceView(context).also { surfaceView ->
                                        surfaceView.layoutParams = ViewGroup.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.MATCH_PARENT
                                        )
                                        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                                            override fun surfaceCreated(holder: SurfaceHolder) {
                                                videoSurface = holder.surface
                                                Log.d(TAG, "Video surface created")
                                            }
                                            override fun surfaceChanged(
                                                holder: SurfaceHolder, 
                                                format: Int, 
                                                width: Int, 
                                                height: Int
                                            ) {
                                                videoSurface = holder.surface
                                                Log.d(TAG, "Video surface changed: ${width}x${height}")
                                            }
                                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                                videoSurface = null
                                                Log.d(TAG, "Video surface destroyed")
                                            }
                                        })
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    )
                }
            }
        }
        
        // Hide system UI for immersive experience
        window.decorView.post { hideSystemUI() }
        
        // Auto-start scanning
        checkPermissionsAndScan()
    }
    
    private fun setupViewModelCallbacks() {
        // Handle language changes from phone
        viewModel.onLanguageReceived = { languageCode ->
            LocaleManager.applyLanguageFromBle(this, languageCode)
        }
        
        // Handle L2CAP PSM received - connect and start streaming
        viewModel.onL2capReady = { psm ->
            val settings = viewModel.connectionSettings.value
            if (psm > 0 && settings.connectionMode.toInt() == 1) {
                connectL2capAndStream(psm)
            }
        }
        
        // Handle all settings received
        viewModel.onSettingsReceived = { settings ->
            Log.d(TAG, "All settings received: $settings")
        }
    }
    
    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.systemBars())
                controller.systemBarsBehavior = 
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }
    }
    
    private fun checkPermissionsAndScan() {
        val requiredPermissions = mutableListOf<String>()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
                requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
                requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
                requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        
        if (requiredPermissions.isNotEmpty()) {
            permissionLauncher.launch(requiredPermissions.toTypedArray())
        } else {
            viewModel.startScan()
        }
    }
    
    @SuppressLint("MissingPermission")
    private fun connectL2capAndStream(psm: Int) {
        val settings = viewModel.connectionSettings.value
        val device = bluetoothAdapter?.getRemoteDevice(
            viewModel.scannedDevices.value.firstOrNull()?.address ?: return
        ) ?: return
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Connecting L2CAP to PSM $psm...")
                val socket = device.createInsecureL2capChannel(psm)
                socket.connect()
                l2capSocket = socket
                isStreaming = true
                Log.d(TAG, "L2CAP connected!")
                
                viewModel.setStreaming(true)
                
                // Start receiving video
                receiveVideoStream(socket.inputStream)
            } catch (e: IOException) {
                Log.e(TAG, "L2CAP connection failed", e)
                viewModel.setStreaming(false)
            }
        }
    }
    
    private fun receiveVideoStream(inputStream: InputStream) {
        Log.d(TAG, "Starting video stream receiver...")
        
        // Wait for video surface to be ready
        var waitCount = 0
        while (videoSurface == null && waitCount < 50) {
            Thread.sleep(100)
            waitCount++
        }
        
        if (videoSurface == null) {
            Log.e(TAG, "Video surface not ready after 5 seconds")
            return
        }
        
        // Reset codec detection for new stream (decoder initialized on first frame)
        detectedCodecType = null
        
        val headerBuffer = ByteArray(4)
        val frameBuffer = ByteArray(GlassesScannerViewModel.MAX_FRAME_SIZE)
        
        try {
            while (isStreaming && l2capSocket?.isConnected == true) {
                // Read frame size header (4 bytes, little-endian to match sender)
                var bytesRead = 0
                while (bytesRead < 4) {
                    val read = inputStream.read(headerBuffer, bytesRead, 4 - bytesRead)
                    if (read < 0) throw IOException("End of stream")
                    bytesRead += read
                }
                
                val frameSize = ByteBuffer.wrap(headerBuffer).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
                if (frameSize <= 0 || frameSize > GlassesScannerViewModel.MAX_FRAME_SIZE) {
                    Log.w(TAG, "Invalid frame size: $frameSize")
                    continue
                }
                
                // Read frame data
                bytesRead = 0
                while (bytesRead < frameSize) {
                    val read = inputStream.read(frameBuffer, bytesRead, frameSize - bytesRead)
                    if (read < 0) throw IOException("End of stream")
                    bytesRead += read
                }
                
                framesReceived++
                
                // Initialize decoder on first frame (auto-detect H.264/H.265)
                if (videoDecoder == null) {
                    try {
                        val codecType = detectCodecType(frameBuffer, frameSize)
                        detectedCodecType = codecType
                        
                        val format = MediaFormat.createVideoFormat(
                            codecType,
                            GlassesScannerViewModel.VIDEO_WIDTH, 
                            GlassesScannerViewModel.VIDEO_HEIGHT
                        )
                        
                        // Enable low latency mode (API 30+)
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                        }
                        
                        // Set realtime priority (API 23+)
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                            format.setInteger(MediaFormat.KEY_PRIORITY, 0)  // 0 = realtime
                        }
                        
                        videoDecoder = MediaCodec.createDecoderByType(codecType)
                        videoDecoder?.configure(format, videoSurface, null, 0)
                        videoDecoder?.start()
                        
                        val codecName = if (codecType == MIME_TYPE_HEVC) "H.265/HEVC" else "H.264/AVC"
                        Log.d(TAG, "🎬 Video decoder started: $codecName with low latency mode")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to initialize decoder", e)
                        break
                    }
                }
                
                decodeFrame(frameBuffer, frameSize)
            }
        } catch (e: IOException) {
            Log.d(TAG, "Video stream ended: ${e.message}")
        } finally {
            stopDecoder()
        }
    }
    
    /**
     * Detect codec type from NAL unit header.
     * H.264: NAL type in bits 0-4 of first byte after start code
     * H.265: NAL type in bits 1-6 of first byte after start code
     */
    private fun detectCodecType(data: ByteArray, size: Int): String {
        if (size < 5) return MIME_TYPE_AVC  // Default to H.264
        
        // Find NAL header byte (after start code)
        val nalHeaderByte: Int = when {
            data[0] == 0.toByte() && data[1] == 0.toByte() && 
                data[2] == 0.toByte() && data[3] == 1.toByte() -> data[4].toInt() and 0xFF
            data[0] == 0.toByte() && data[1] == 0.toByte() && 
                data[2] == 1.toByte() -> data[3].toInt() and 0xFF
            else -> return MIME_TYPE_AVC
        }
        
        // H.264: NAL type in bits 0-4 (& 0x1F)
        val h264NalType = nalHeaderByte and 0x1F
        
        // H.265: NAL type in bits 1-6 (>> 1 & 0x3F)
        val h265NalType = (nalHeaderByte shr 1) and 0x3F
        
        // Check for H.265 NAL types (VPS=32, SPS=33, PPS=34, IDR_W_RADL=19, IDR_N_LP=20, etc.)
        if (h265NalType in 32..34 || h265NalType in 16..21) {
            Log.d(TAG, "🔍 Detected H.265/HEVC stream (NAL type=$h265NalType)")
            return MIME_TYPE_HEVC
        }
        
        // Check for H.264 NAL types (SPS=7, PPS=8, IDR=5, non-IDR=1)
        if (h264NalType in listOf(7, 8, 5, 1, 6, 9)) {
            Log.d(TAG, "🔍 Detected H.264/AVC stream (NAL type=$h264NalType)")
            return MIME_TYPE_AVC
        }
        
        // Default to H.264
        Log.d(TAG, "🔍 Unknown NAL type, defaulting to H.264/AVC")
        return MIME_TYPE_AVC
    }
    
    private fun decodeFrame(data: ByteArray, size: Int) {
        val decoder = videoDecoder ?: return
        
        // Check if still streaming before decode
        if (!isStreaming) return
        
        try {
            val inputIndex = decoder.dequeueInputBuffer(10000)
            if (inputIndex >= 0) {
                val inputBuffer = decoder.getInputBuffer(inputIndex) ?: return
                inputBuffer.clear()
                inputBuffer.put(data, 0, size)
                decoder.queueInputBuffer(inputIndex, 0, size, System.nanoTime() / 1000, 0)
            }
            
            val bufferInfo = MediaCodec.BufferInfo()
            var outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000)
            while (outputIndex >= 0 && isStreaming) {
                decoder.releaseOutputBuffer(outputIndex, true)
                framesDecoded++
                
                if (framesDecoded % 100 == 0) {
                    Log.d(TAG, "Frames received: $framesReceived, decoded: $framesDecoded")
                }
                
                outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 0)
            }
        } catch (e: IllegalStateException) {
            // Decoder was stopped/released - this is expected when surface is destroyed
            Log.d(TAG, "Decoder stopped: ${e.message}")
            isStreaming = false
        } catch (e: Exception) {
            Log.e(TAG, "Decode error", e)
        }
    }
    
    private fun stopDecoder() {
        try {
            videoDecoder?.stop()
            videoDecoder?.release()
            videoDecoder = null
            Log.d(TAG, "Decoder stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping decoder", e)
        }
    }
    
    private fun disconnect() {
        Log.d(TAG, "Disconnecting device")
        
        isStreaming = false
        
        try {
            l2capSocket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing L2CAP socket", e)
        }
        l2capSocket = null
        
        stopDecoder()
        viewModel.disconnect()
        
        // Restart scanning
        checkPermissionsAndScan()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        isStreaming = false
        stopDecoder()
        try {
            l2capSocket?.close()
        } catch (e: IOException) { 
            // Ignore
        }
    }
}
