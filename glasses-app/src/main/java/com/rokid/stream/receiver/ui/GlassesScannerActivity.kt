package com.rokid.stream.receiver.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Bundle
import android.os.ParcelUuid
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface as ComposeSurface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.rokid.stream.receiver.core.VideoEncoder
import com.rokid.stream.receiver.ui.theme.RokidStreamTheme
import com.rokid.stream.receiver.util.LocaleManager
import kotlinx.coroutines.*
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Glasses Activity with Bluetooth Device Scanner
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
class GlassesScannerActivity : ComponentActivity() {
    
    companion object {
        private const val TAG = "GlassesScanner"
        
        // UUID that the phone app advertises
        // This should match the UUID in the phone app's BLE advertisement
        val ROKID_STREAM_SERVICE_UUID: UUID = UUID.fromString("0000FFFF-0000-1000-8000-00805F9B34FB")
        
        // Characteristic UUIDs (must match phone's BleAdvertiser)
        val CONNECTION_MODE_CHAR_UUID: UUID = UUID.fromString("0000FF01-0000-1000-8000-00805F9B34FB")
        val STREAM_DIRECTION_CHAR_UUID: UUID = UUID.fromString("0000FF02-0000-1000-8000-00805F9B34FB")
        val STREAM_DATA_CHAR_UUID: UUID = UUID.fromString("0000FF03-0000-1000-8000-00805F9B34FB")
        val LANGUAGE_CHAR_UUID: UUID = UUID.fromString("0000FF04-0000-1000-8000-00805F9B34FB")
        
        // Video parameters - LOW LATENCY (must match phone encoder)
        private const val VIDEO_WIDTH = 240
        private const val VIDEO_HEIGHT = 240
        private const val MAX_FRAME_SIZE = 1_000_000  // 1MB
        private const val DECODER_TIMEOUT_US = 1000L  // 1ms timeout for low latency
        
        // Scan timeout in milliseconds
        private const val SCAN_TIMEOUT_MS = 30000L
        
        // Max retry count for service discovery
        private const val MAX_SERVICE_DISCOVERY_RETRIES = 3
        private const val SERVICE_DISCOVERY_RETRY_DELAY_MS = 500L
    }
    
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var isScanning = false
    private var scanJob: Job? = null
    
    // Service discovery retry counter
    private var serviceDiscoveryRetryCount = 0
    
    // Connection settings received from phone
    private var connectionMode: Byte = 0
    private var streamDirection: Byte = 0
    private var languageCode: Byte = 0
    private var l2capPsm: Int = 0
    
    // L2CAP and video components
    private var l2capSocket: BluetoothSocket? = null
    private var videoDecoder: MediaCodec? = null
    private var videoSurface: Surface? = null
    @Volatile
    private var isStreaming = false
    private var framesReceived = 0
    private var framesDecoded = 0
    
    // Glasses→Phone sending mode components
    private var videoEncoder: VideoEncoder? = null
    private var outputStream: OutputStream? = null
    private val isFirstFrame = AtomicBoolean(true)
    private var framesSent = 0
    private var lastFrameTime = 0L
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // State
    private val _state = mutableStateOf(GlassesState.DEVICE_LIST)
    private val _scannedDevices = mutableStateListOf<ScannedDevice>()
    private val _isScanning = mutableStateOf(false)
    private val _connectedDeviceName = mutableStateOf("")
    
    // Permission launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            startBleScan()
        } else {
            Log.w(TAG, "Bluetooth permissions not granted")
        }
    }
    
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleManager.applyLocale(newBase))
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Force portrait orientation for Rokid glasses
        // This ensures text top points upward on the glasses display
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        
        // Initialize Bluetooth
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bleScanner = bluetoothAdapter?.bluetoothLeScanner
        
        setContent {
            RokidStreamTheme {
                ComposeSurface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val state by _state
                    val scannedDevices = _scannedDevices.toList()
                    val isScanning by _isScanning
                    val connectedDeviceName by _connectedDeviceName
                    
                    GlassesScannerScreen(
                        state = state,
                        scannedDevices = scannedDevices,
                        connectedDeviceName = connectedDeviceName,
                        isScanning = isScanning,
                        onStartScan = { checkPermissionsAndScan() },
                        onStopScan = { stopBleScan() },
                        onDeviceSelected = { device -> connectToDevice(device) },
                        onDisconnect = { disconnectDevice() },
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
                                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                                                videoSurface = holder.surface
                                                Log.d(TAG, "Video surface changed: ${width}x${height}")
                                            }
                                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                                Log.d(TAG, "Video surface destroyed")
                                                videoSurface = null
                                                isStreaming = false
                                                stopDecoder()
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
        
        // Hide system UI for immersive experience on glasses (must be after setContent)
        window.decorView.post { hideSystemUI() }
        
        // Auto-start scanning when activity launches
        checkPermissionsAndScan()
    }
    
    /**
     * Hide system UI for immersive experience on glasses
     */
    private fun hideSystemUI() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            // Modern API (Android 11+)
            window.insetsController?.let { controller ->
                controller.hide(android.view.WindowInsets.Type.systemBars())
                controller.systemBarsBehavior = 
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            // Legacy API (Android 10 and below)
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }
    }
    
    private fun checkPermissionsAndScan() {
        val requiredPermissions = mutableListOf<String>()
        
        // Bluetooth permissions
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
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
        
        // Camera permission (for Glasses→Phone mode)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            requiredPermissions.add(Manifest.permission.CAMERA)
        }
        
        if (requiredPermissions.isNotEmpty()) {
            permissionLauncher.launch(requiredPermissions.toTypedArray())
        } else {
            startBleScan()
        }
    }
    
    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        if (isScanning) {
            Log.d(TAG, "Already scanning")
            return
        }
        
        if (bleScanner == null) {
            Log.e(TAG, "BLE Scanner not available")
            return
        }
        
        _scannedDevices.clear()
        _isScanning.value = true
        isScanning = true
        
        Log.d(TAG, "Starting BLE scan...")
        
        // Scan with filter for RokidStream service UUID
        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(ROKID_STREAM_SERVICE_UUID))
            .build()
        
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        
        try {
            bleScanner?.startScan(listOf(scanFilter), scanSettings, scanCallback)
            
            // Also start a general scan to find all nearby phones
            // (in case the phone doesn't advertise our UUID initially)
            bleScanner?.startScan(generalScanCallback)
            
            // Set scan timeout
            scanJob = scope.launch {
                delay(SCAN_TIMEOUT_MS)
                stopBleScan()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start scan", e)
            _isScanning.value = false
            isScanning = false
        }
    }
    
    @SuppressLint("MissingPermission")
    private fun stopBleScan() {
        if (!isScanning) return
        
        Log.d(TAG, "Stopping BLE scan")
        
        scanJob?.cancel()
        scanJob = null
        
        try {
            bleScanner?.stopScan(scanCallback)
            bleScanner?.stopScan(generalScanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop scan", e)
        }
        
        _isScanning.value = false
        isScanning = false
    }
    
    // Callback for filtered scan (RokidStream devices)
    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            addScannedDevice(result)
        }
        
        @SuppressLint("MissingPermission")
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { addScannedDevice(it) }
        }
        
        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Filtered scan failed with error: $errorCode")
        }
    }
    
    // Callback for general scan (all devices)
    private val generalScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // Only add devices that have a name (likely phones with apps)
            val deviceName = result.device.name
            if (!deviceName.isNullOrEmpty()) {
                addScannedDevice(result)
            }
        }
        
        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "General scan failed with error: $errorCode")
        }
    }
    
    @SuppressLint("MissingPermission")
    private fun addScannedDevice(result: ScanResult) {
        val device = result.device
        val address = device.address
        val name = device.name ?: ""
        val rssi = result.rssi
        
        // Check if device already exists
        val existingIndex = _scannedDevices.indexOfFirst { it.address == address }
        
        if (existingIndex >= 0) {
            // Update existing device
            _scannedDevices[existingIndex] = ScannedDevice(address, name, rssi)
        } else {
            // Add new device
            _scannedDevices.add(ScannedDevice(address, name, rssi))
            Log.d(TAG, "Found device: $name ($address) RSSI: $rssi")
        }
        
        // Sort by signal strength (strongest first)
        _scannedDevices.sortByDescending { it.rssi }
    }
    
    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: ScannedDevice) {
        Log.d(TAG, "Connecting to device: ${device.name} (${device.address})")
        
        stopBleScan()
        
        _state.value = GlassesState.CONNECTING
        _connectedDeviceName.value = device.name.ifEmpty { device.address }
        
        // Reset retry counter
        serviceDiscoveryRetryCount = 0
        
        // Get Bluetooth device
        val bluetoothDevice = bluetoothAdapter?.getRemoteDevice(device.address)
        
        if (bluetoothDevice == null) {
            Log.e(TAG, "Failed to get Bluetooth device")
            _state.value = GlassesState.DEVICE_LIST
            return
        }
        
        // Try to refresh GATT cache before connecting (using reflection)
        refreshDeviceCache(bluetoothDevice)
        
        // Connect via GATT
        bluetoothGatt = bluetoothDevice.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }
    
    /**
     * Attempts to refresh the device's GATT cache using reflection.
     * This is necessary because Android caches GATT services and may return stale data.
     */
    @SuppressLint("DiscouragedPrivateApi")
    private fun refreshDeviceCache(device: BluetoothDevice): Boolean {
        try {
            val refreshMethod = device.javaClass.getMethod("refresh")
            val result = refreshMethod.invoke(device) as? Boolean ?: false
            Log.d(TAG, "Device cache refresh: $result")
            return result
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh device cache: ${e.message}")
            return false
        }
    }
    
    /**
     * Attempts to refresh the GATT cache using reflection.
     * Called when service discovery returns cached/stale data.
     */
    @SuppressLint("DiscouragedPrivateApi")
    private fun refreshGattCache(gatt: BluetoothGatt): Boolean {
        try {
            val refreshMethod = gatt.javaClass.getMethod("refresh")
            val result = refreshMethod.invoke(gatt) as? Boolean ?: false
            Log.d(TAG, "GATT cache refresh: $result")
            return result
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh GATT cache: ${e.message}")
            return false
        }
    }
    
    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "GATT Connected, requesting high priority connection...")
                    // Request high priority connection for better throughput
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    // Request 2M PHY for higher data rate
                    gatt.setPreferredPhy(
                        BluetoothDevice.PHY_LE_2M_MASK,
                        BluetoothDevice.PHY_LE_2M_MASK,
                        BluetoothDevice.PHY_OPTION_NO_PREFERRED
                    )
                    // Refresh cache before discovering services
                    refreshGattCache(gatt)
                    // Add small delay to allow cache refresh to take effect
                    scope.launch {
                        delay(100)
                        gatt.discoverServices()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "GATT Disconnected")
                    scope.launch(Dispatchers.Main) {
                        _state.value = GlassesState.DEVICE_LIST
                        _connectedDeviceName.value = ""
                    }
                    bluetoothGatt?.close()
                    bluetoothGatt = null
                }
            }
        }
        
        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                scope.launch(Dispatchers.Main) { _state.value = GlassesState.DEVICE_LIST }
                return
            }
            
            // Log all discovered services for debugging
            val discoveredServices = gatt.services
            Log.d(TAG, "Services discovered (${discoveredServices.size} services):")
            discoveredServices.forEach { svc ->
                Log.d(TAG, "  - Service: ${svc.uuid}")
            }
            
            // Find our service
            val service = gatt.getService(ROKID_STREAM_SERVICE_UUID)
            if (service == null) {
                serviceDiscoveryRetryCount++
                Log.w(TAG, "RokidStream service not found (attempt $serviceDiscoveryRetryCount/$MAX_SERVICE_DISCOVERY_RETRIES)")
                
                // Retry service discovery with cache refresh
                if (serviceDiscoveryRetryCount < MAX_SERVICE_DISCOVERY_RETRIES) {
                    scope.launch {
                        Log.d(TAG, "Retrying service discovery after delay...")
                        delay(SERVICE_DISCOVERY_RETRY_DELAY_MS)
                        refreshGattCache(gatt)
                        delay(100)
                        gatt.discoverServices()
                    }
                } else {
                    Log.e(TAG, "RokidStream service not found after $MAX_SERVICE_DISCOVERY_RETRIES attempts")
                    scope.launch(Dispatchers.Main) { _state.value = GlassesState.DEVICE_LIST }
                }
                return
            }
            
            Log.d(TAG, "RokidStream service found, reading connection settings...")
            
            // Read connection mode characteristic
            val modeChar = service.getCharacteristic(CONNECTION_MODE_CHAR_UUID)
            if (modeChar != null) {
                gatt.readCharacteristic(modeChar)
            } else {
                Log.w(TAG, "Connection mode characteristic not found, using defaults")
                onSettingsReceived()
            }
        }
        
        @SuppressLint("MissingPermission")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Characteristic read failed: $status")
                return
            }
            
            when (characteristic.uuid) {
                CONNECTION_MODE_CHAR_UUID -> {
                    connectionMode = characteristic.value?.getOrNull(0) ?: 0
                    Log.d(TAG, "Connection mode: $connectionMode")
                    
                    // Read stream direction next
                    val service = gatt.getService(ROKID_STREAM_SERVICE_UUID)
                    val directionChar = service?.getCharacteristic(STREAM_DIRECTION_CHAR_UUID)
                    if (directionChar != null) {
                        gatt.readCharacteristic(directionChar)
                    } else {
                        onSettingsReceived()
                    }
                }
                STREAM_DIRECTION_CHAR_UUID -> {
                    streamDirection = characteristic.value?.getOrNull(0) ?: 0
                    Log.d(TAG, "Stream direction: $streamDirection")
                    
                    // Read language next
                    val service = gatt.getService(ROKID_STREAM_SERVICE_UUID)
                    val languageChar = service?.getCharacteristic(LANGUAGE_CHAR_UUID)
                    if (languageChar != null) {
                        gatt.readCharacteristic(languageChar)
                    } else {
                        onSettingsReceived()
                    }
                }
                LANGUAGE_CHAR_UUID -> {
                    languageCode = characteristic.value?.getOrNull(0) ?: 0
                    Log.d(TAG, "Language code: $languageCode")
                    
                    // Apply language from phone
                    LocaleManager.applyLanguageFromBle(this@GlassesScannerActivity, languageCode)
                    
                    // Read PSM next (for L2CAP connection)
                    val service = gatt.getService(ROKID_STREAM_SERVICE_UUID)
                    val psmChar = service?.getCharacteristic(STREAM_DATA_CHAR_UUID)
                    if (psmChar != null) {
                        gatt.readCharacteristic(psmChar)
                    } else {
                        Log.w(TAG, "PSM characteristic not found")
                        onSettingsReceived()
                    }
                }
                STREAM_DATA_CHAR_UUID -> {
                    // Parse PSM value from little-endian bytes
                    val data = characteristic.value
                    if (data != null && data.size >= 4) {
                        l2capPsm = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).int
                        Log.d(TAG, "L2CAP PSM: $l2capPsm")
                    }
                    onSettingsReceived()
                }
            }
        }
    }
    
    private fun onSettingsReceived() {
        val modeName = when (connectionMode.toInt()) {
            1 -> "L2CAP"
            2 -> "WiFi"
            3 -> "Rokid SDK"
            else -> "Unknown"
        }
        val directionName = when (streamDirection.toInt()) {
            1 -> "手機→眼鏡"
            2 -> "眼鏡→手機"
            3 -> "雙向"
            else -> "Unknown"
        }
        val langName = LocaleManager.getLanguageFromByteCode(languageCode).nativeName
        Log.d(TAG, "Settings received: mode=$modeName, direction=$directionName, language=$langName, psm=$l2capPsm")
        
        scope.launch(Dispatchers.Main) {
            _state.value = GlassesState.CONNECTED
            
            // Connect L2CAP and start streaming
            if (l2capPsm > 0 && connectionMode.toInt() == 1) {
                // L2CAP mode - connect and start receiving video
                connectL2capAndStream()
            } else {
                // No PSM or not L2CAP mode
                delay(500)
                _state.value = GlassesState.STREAMING
            }
        }
    }
    
    @SuppressLint("MissingPermission")
    private fun connectL2capAndStream() {
        val device = bluetoothGatt?.device ?: return
        
        Thread {
            try {
                Log.d(TAG, "Connecting L2CAP to PSM $l2capPsm...")
                val socket = device.createInsecureL2capChannel(l2capPsm)
                socket.connect()
                l2capSocket = socket
                isStreaming = true
                
                // Log L2CAP socket info
                val maxReceivePacketSize = socket.maxReceivePacketSize
                val maxTransmitPacketSize = socket.maxTransmitPacketSize
                Log.d(TAG, "L2CAP connected! MaxRx=$maxReceivePacketSize, MaxTx=$maxTransmitPacketSize")
                
                scope.launch(Dispatchers.Main) {
                    _state.value = GlassesState.STREAMING
                }
                
                // Choose mode based on stream direction
                when (streamDirection.toInt()) {
                    1 -> {
                        // Phone→Glasses: Glasses receives video
                        Log.d(TAG, "Mode: Phone→Glasses (receiving)")
                        receiveVideoStream(socket.inputStream)
                    }
                    2 -> {
                        // Glasses→Phone: Glasses sends video
                        Log.d(TAG, "Mode: Glasses→Phone (sending)")
                        outputStream = socket.outputStream
                        scope.launch(Dispatchers.Main) {
                            startCameraAndSend()
                        }
                    }
                    3 -> {
                        // Bidirectional: Currently just receive (TODO: implement bidirectional)
                        Log.d(TAG, "Mode: Bidirectional (receiving for now)")
                        receiveVideoStream(socket.inputStream)
                    }
                    else -> {
                        Log.w(TAG, "Unknown stream direction: $streamDirection, defaulting to receive")
                        receiveVideoStream(socket.inputStream)
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "L2CAP connection failed", e)
                scope.launch(Dispatchers.Main) {
                    _state.value = GlassesState.CONNECTED
                }
            }
        }.start()
    }
    
    private fun receiveVideoStream(rawInputStream: InputStream) {
        Log.d(TAG, "Starting video stream receiver...")
        
        // Wrap in BufferedInputStream for better L2CAP throughput
        val L2CAP_BUFFER_SIZE = 64 * 1024  // 64KB buffer
        val inputStream = BufferedInputStream(rawInputStream, L2CAP_BUFFER_SIZE)
        
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
        
        // Initialize decoder with LOW LATENCY settings
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, VIDEO_WIDTH, VIDEO_HEIGHT)
            
            // Enable low latency mode (API 30+)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            
            // Set realtime priority (API 23+)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                format.setInteger(MediaFormat.KEY_PRIORITY, 0)  // 0 = realtime
            }
            
            videoDecoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            videoDecoder?.configure(format, videoSurface, null, 0)
            videoDecoder?.start()
            Log.d(TAG, "Video decoder started with low latency mode")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize decoder", e)
            return
        }
        
        val headerBuffer = ByteArray(4)
        val frameBuffer = ByteArray(MAX_FRAME_SIZE)
        var lastLogTime = System.currentTimeMillis()
        var bytesReceivedTotal = 0L
        
        // Timing statistics for debugging
        var lastFrameReceiveTime = 0L
        var totalReceiveInterval = 0L
        var totalDecodeTime = 0L
        var frameCountForStats = 0
        
        Log.d(TAG, "Starting receive loop, isStreaming=$isStreaming, socket connected=${l2capSocket?.isConnected}")
        
        try {
            while (isStreaming && l2capSocket?.isConnected == true) {
                // Read frame size header (4 bytes, little-endian)
                val headerReadStart = System.currentTimeMillis()
                var bytesRead = 0
                while (bytesRead < 4) {
                    val read = inputStream.read(headerBuffer, bytesRead, 4 - bytesRead)
                    if (read < 0) throw IOException("End of stream")
                    bytesRead += read
                }
                val headerReadTime = System.currentTimeMillis() - headerReadStart
                if (headerReadTime > 500) {
                    Log.w(TAG, "⚠️ Header read took ${headerReadTime}ms")
                }
                
                val frameSize = ByteBuffer.wrap(headerBuffer).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
                if (frameSize <= 0 || frameSize > MAX_FRAME_SIZE) {
                    Log.w(TAG, "Invalid frame size: $frameSize, raw bytes: ${headerBuffer.contentToString()}")
                    continue
                }
                
                // Read frame data
                val dataReadStart = System.currentTimeMillis()
                bytesRead = 0
                while (bytesRead < frameSize) {
                    val read = inputStream.read(frameBuffer, bytesRead, frameSize - bytesRead)
                    if (read < 0) throw IOException("End of stream")
                    bytesRead += read
                }
                val dataReadTime = System.currentTimeMillis() - dataReadStart
                if (dataReadTime > 500) {
                    Log.w(TAG, "⚠️ Data read took ${dataReadTime}ms for $frameSize bytes")
                }
                
                framesReceived++
                bytesReceivedTotal += frameSize + 4
                
                // Log first 5 frames for debugging
                if (framesReceived <= 5) {
                    Log.d(TAG, "📦 Frame #$framesReceived: $frameSize bytes, headerWait=${headerReadTime}ms, dataWait=${dataReadTime}ms")
                }
                
                // Calculate receive interval
                val receiveTime = System.currentTimeMillis()
                if (lastFrameReceiveTime > 0) {
                    totalReceiveInterval += receiveTime - lastFrameReceiveTime
                }
                lastFrameReceiveTime = receiveTime
                
                // Decode frame with timing
                val decodeStart = System.nanoTime()
                decodeFrame(frameBuffer, frameSize)
                val decodeEnd = System.nanoTime()
                totalDecodeTime += (decodeEnd - decodeStart) / 1_000_000 // Convert to ms
                frameCountForStats++
                
                // Log every second with detailed timing
                val now = System.currentTimeMillis()
                if (now - lastLogTime >= 1000) {
                    val kbps = (bytesReceivedTotal * 8 / 1000).toInt()
                    val avgReceiveInterval = if (frameCountForStats > 1) totalReceiveInterval / (frameCountForStats - 1) else 0
                    val avgDecodeTime = if (frameCountForStats > 0) totalDecodeTime / frameCountForStats else 0
                    Log.d(TAG, "⏱️ Stats: recv=$framesReceived, dec=$framesDecoded, $kbps Kbps")
                    Log.d(TAG, "⏱️ Timing: avgRecvInterval=${avgReceiveInterval}ms, avgDecodeTime=${avgDecodeTime}ms, frameSize=$frameSize bytes")
                    
                    // Reset stats for next interval
                    lastLogTime = now
                    bytesReceivedTotal = 0
                    totalReceiveInterval = 0
                    totalDecodeTime = 0
                    frameCountForStats = 0
                }
            }
        } catch (e: IOException) {
            Log.d(TAG, "Video stream ended: ${e.message}")
        } finally {
            stopDecoder()
        }
    }
    
    // For render timing statistics
    @Volatile
    private var lastRenderTime = 0L
    @Volatile
    private var maxRenderInterval = 0L
    @Volatile  
    private var inputBufferMisses = 0
    @Volatile
    private var outputBufferMisses = 0
    
    /**
     * Check if data contains H.264 codec config NAL units (SPS or PPS)
     * NAL start code: 0x00 0x00 0x00 0x01
     * NAL type is in lower 5 bits of byte after start code
     * SPS = 7 (0x07, 0x27, 0x47, 0x67), PPS = 8 (0x08, 0x28, 0x48, 0x68)
     */
    private fun isCodecConfigNal(data: ByteArray): Boolean {
        if (data.size < 5) return false
        
        // Check for 4-byte start code: 0x00 0x00 0x00 0x01
        if (data[0] == 0.toByte() && data[1] == 0.toByte() && 
            data[2] == 0.toByte() && data[3] == 1.toByte()) {
            val nalType = data[4].toInt() and 0x1F
            return nalType == 7 || nalType == 8  // SPS or PPS
        }
        
        // Check for 3-byte start code: 0x00 0x00 0x01
        if (data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 1.toByte()) {
            val nalType = data[3].toInt() and 0x1F
            return nalType == 7 || nalType == 8
        }
        
        return false
    }
    
    private fun decodeFrame(data: ByteArray, size: Int) {
        val decoder = videoDecoder ?: return
        
        // Check if still streaming before decode
        if (!isStreaming) return
        
        try {
            // Get input buffer with minimal timeout
            val inputStart = System.nanoTime()
            val inputIndex = decoder.dequeueInputBuffer(DECODER_TIMEOUT_US)
            val inputWaitMs = (System.nanoTime() - inputStart) / 1_000_000
            
            if (inputIndex >= 0) {
                val inputBuffer = decoder.getInputBuffer(inputIndex) ?: return
                inputBuffer.clear()
                inputBuffer.put(data, 0, size)
                
                // Detect codec config (SPS/PPS) by checking NAL unit type
                // H.264 NAL start code: 0x00 0x00 0x00 0x01 followed by NAL header
                // NAL type is in lower 5 bits: SPS=7 (0x67/0x27), PPS=8 (0x68/0x28)
                val isCodecConfig = size >= 5 && isCodecConfigNal(data)
                val flags = if (isCodecConfig) {
                    // Log NAL header bytes for debugging
                    val headerBytes = if (size >= 8) {
                        data.take(8).map { String.format("%02X", it) }.joinToString(" ")
                    } else {
                        data.take(size).map { String.format("%02X", it) }.joinToString(" ")
                    }
                    Log.d(TAG, "🔧 Detected codec config (SPS/PPS): $size bytes, header: $headerBytes")
                    MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                } else {
                    // Log first frame's NAL type for debugging
                    if (framesReceived <= 3) {
                        val headerBytes = if (size >= 8) {
                            data.take(8).map { String.format("%02X", it) }.joinToString(" ")
                        } else {
                            data.take(size).map { String.format("%02X", it) }.joinToString(" ")
                        }
                        // Determine NAL type
                        val nalType = when {
                            size >= 5 && data[0] == 0.toByte() && data[1] == 0.toByte() && 
                                data[2] == 0.toByte() && data[3] == 1.toByte() -> data[4].toInt() and 0x1F
                            size >= 4 && data[0] == 0.toByte() && data[1] == 0.toByte() && 
                                data[2] == 1.toByte() -> data[3].toInt() and 0x1F
                            else -> -1
                        }
                        val nalTypeName = when (nalType) {
                            1 -> "Non-IDR slice"
                            5 -> "IDR slice (keyframe)"
                            6 -> "SEI"
                            7 -> "SPS"
                            8 -> "PPS"
                            9 -> "AUD"
                            else -> "Unknown"
                        }
                        Log.d(TAG, "📹 Frame ${framesReceived}: $size bytes, NAL type=$nalType ($nalTypeName), header: $headerBytes")
                    }
                    0
                }
                
                decoder.queueInputBuffer(inputIndex, 0, size, System.nanoTime() / 1000, flags)
            } else {
                inputBufferMisses++
                if (inputBufferMisses % 10 == 0) {
                    Log.w(TAG, "⚠️ Input buffer miss count: $inputBufferMisses (waited ${inputWaitMs}ms)")
                }
            }
            
            // Get output buffer and render IMMEDIATELY
            val bufferInfo = MediaCodec.BufferInfo()
            val outputStart = System.nanoTime()
            var outputIndex = decoder.dequeueOutputBuffer(bufferInfo, DECODER_TIMEOUT_US)
            val outputWaitMs = (System.nanoTime() - outputStart) / 1_000_000
            
            if (outputIndex < 0 && outputIndex != MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                outputBufferMisses++
                if (outputBufferMisses % 10 == 0) {
                    Log.w(TAG, "⚠️ Output buffer miss count: $outputBufferMisses (waited ${outputWaitMs}ms)")
                }
            }
            
            while (outputIndex >= 0 && isStreaming) {
                // Render immediately - use 'true' for immediate rendering without timestamp delay
                decoder.releaseOutputBuffer(outputIndex, true)
                framesDecoded++
                
                // Track render interval
                val now = System.currentTimeMillis()
                if (lastRenderTime > 0) {
                    val renderInterval = now - lastRenderTime
                    if (renderInterval > maxRenderInterval) {
                        maxRenderInterval = renderInterval
                    }
                    // Log if render interval is too high (should be ~100ms for 10 FPS)
                    if (renderInterval > 200) {
                        Log.w(TAG, "🔴 High render interval: ${renderInterval}ms (max: ${maxRenderInterval}ms)")
                    }
                }
                lastRenderTime = now
                
                if (framesDecoded % 100 == 0) {
                    Log.d(TAG, "✅ Frames received: $framesReceived, decoded: $framesDecoded, maxRenderInterval: ${maxRenderInterval}ms")
                    maxRenderInterval = 0 // Reset max
                }
                
                // Check for more frames without waiting
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
    
    // ==================== GLASSES→PHONE SENDING MODE ====================
    
    /**
     * Start camera capture and video encoding for Glasses→Phone mode
     */
    private fun startCameraAndSend() {
        Log.d(TAG, "Starting camera for Glasses→Phone mode")
        
        // DISABLED: Rokid glasses don't have standard Android cameras
        // CameraX can't detect Rokid's camera hardware correctly, causing:
        // - "Expected camera missing from device" errors
        // - Continuous retry loops consuming resources
        // - App instability
        //
        // For now, Glasses→Phone mode is not supported on Rokid glasses.
        // The glasses can only receive video from the phone.
        Log.w(TAG, "⚠️ Camera capture disabled on Rokid glasses - camera hardware not compatible with CameraX")
        Log.w(TAG, "⚠️ Glasses→Phone mode is not currently supported")
        return
        
        /*
        // Check camera permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Camera permission not granted")
            return
        }
        
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                bindCameraForSending(cameraProvider)
            } catch (e: Exception) {
                Log.e(TAG, "Camera init failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
        */
    }
    
    /**
     * Bind camera use cases for video sending
     * 
     * Note: Rokid glasses camera may not report standard LENS_FACING attribute,
     * so we use a custom CameraSelector that selects by camera ID instead.
     */
    private fun bindCameraForSending(cameraProvider: ProcessCameraProvider) {
        // Configure image analysis for frame capture
        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(android.util.Size(
                VideoEncoder.DEFAULT_WIDTH, 
                VideoEncoder.DEFAULT_HEIGHT
            ))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
        
        imageAnalysis.setAnalyzer(cameraExecutor) { image ->
            processFrameForSending(image)
        }
        
        // Get all available cameras
        val availableCameras = cameraProvider.availableCameraInfos
        Log.d(TAG, "Available cameras: ${availableCameras.size}")
        availableCameras.forEachIndexed { index, cameraInfo ->
            val lensFacing = try {
                cameraInfo.lensFacing
            } catch (e: Exception) {
                -1  // Unknown lens facing
            }
            Log.d(TAG, "Camera $index: lensFacing=$lensFacing")
        }
        
        // Try different camera selection strategies
        val cameraSelectorStrategies = listOf(
            // Strategy 1: Try front camera (standard)
            { CameraSelector.DEFAULT_FRONT_CAMERA },
            // Strategy 2: Try back camera (standard)
            { CameraSelector.DEFAULT_BACK_CAMERA },
            // Strategy 3: Use first available camera by ID (for Rokid glasses with non-standard lens facing)
            { 
                CameraSelector.Builder()
                    .addCameraFilter { cameras ->
                        // Return all cameras - just pick the first one
                        if (cameras.isNotEmpty()) {
                            listOf(cameras.first())
                        } else {
                            cameras
                        }
                    }
                    .build()
            },
            // Strategy 4: Accept any camera
            {
                CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_UNKNOWN)
                    .build()
            }
        )
        
        var cameraBindSuccess = false
        for ((index, selectorFactory) in cameraSelectorStrategies.withIndex()) {
            if (cameraBindSuccess) break
            
            try {
                val cameraSelector = selectorFactory()
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalysis)
                Log.d(TAG, "Camera bound successfully using strategy $index")
                cameraBindSuccess = true
            } catch (e: Exception) {
                Log.w(TAG, "Camera bind strategy $index failed: ${e.message}")
            }
        }
        
        if (!cameraBindSuccess) {
            Log.e(TAG, "Failed to bind any camera after trying all strategies")
        }
    }
    
    /**
     * Process camera frame: encode and queue for sending
     */
    private fun processFrameForSending(image: ImageProxy) {
        try {
            if (!isStreaming) {
                image.close()
                return
            }
            
            // Frame rate limiting (~30 FPS)
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastFrameTime < 33) {  // ~30 FPS
                image.close()
                return
            }
            lastFrameTime = currentTime
            
            // Initialize encoder on first frame
            if (isFirstFrame.compareAndSet(true, false)) {
                videoEncoder = VideoEncoder()
                if (!videoEncoder!!.start(image.width, image.height)) {
                    Log.e(TAG, "Encoder start failed")
                    image.close()
                    return
                }
                
                // Start sender thread
                Thread { sendVideoFrames() }.start()
                Log.d(TAG, "First frame: ${image.width}x${image.height}, encoder started")
            }
            
            // Encode the frame
            videoEncoder?.encodeFrame(image)
        } finally {
            image.close()
        }
    }
    
    /**
     * Sender thread: reads encoded frames and sends over L2CAP
     */
    private fun sendVideoFrames() {
        val headerBuffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        Log.d(TAG, "Video sender thread started")
        
        try {
            while (isStreaming) {
                val encoder = videoEncoder ?: break
                val frameData = encoder.getEncodedFrame(100) ?: continue
                val stream = outputStream ?: break
                
                try {
                    // Write frame size header (4 bytes, little-endian)
                    headerBuffer.clear()
                    headerBuffer.putInt(frameData.size)
                    stream.write(headerBuffer.array())
                    
                    // Write frame data
                    stream.write(frameData)
                    stream.flush()
                    
                    framesSent++
                    
                    if (framesSent % 30 == 0) {
                        Log.d(TAG, "Sent $framesSent frames, last size: ${frameData.size} bytes")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Send error: ${e.message}")
                    break
                }
            }
        } finally {
            Log.d(TAG, "Video sender thread stopped. Total frames sent: $framesSent")
        }
    }
    
    /**
     * Stop encoder and camera for sending mode
     */
    private fun stopEncoder() {
        try {
            videoEncoder?.stop()
            videoEncoder = null
            isFirstFrame.set(true)
            framesSent = 0
            
            // Unbind camera
            try {
                val cameraProvider = ProcessCameraProvider.getInstance(this).get()
                cameraProvider.unbindAll()
            } catch (e: Exception) {
                Log.e(TAG, "Error unbinding camera: ${e.message}")
            }
            
            Log.d(TAG, "Encoder stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping encoder", e)
        }
    }
    
    // ==================== END GLASSES→PHONE SENDING MODE ====================
    
    @SuppressLint("MissingPermission")
    private fun disconnectDevice() {
        Log.d(TAG, "Disconnecting device")
        
        isStreaming = false
        
        try {
            l2capSocket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing L2CAP socket", e)
        }
        l2capSocket = null
        outputStream = null
        
        stopDecoder()
        stopEncoder()
        
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        
        _state.value = GlassesState.DEVICE_LIST
        _connectedDeviceName.value = ""
        
        // Restart scanning
        checkPermissionsAndScan()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        isStreaming = false
        stopDecoder()
        stopEncoder()
        try {
            l2capSocket?.close()
        } catch (e: IOException) { }
        stopBleScan()
        cameraExecutor.shutdown()
        scope.cancel()
    }
}

/**
 * Placeholder composable for video view
 */
@androidx.compose.runtime.Composable
private fun VideoPlaceholder() {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        androidx.compose.material3.Text(
            text = "Video Stream",
            color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.5f)
        )
    }
}
