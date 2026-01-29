package com.rokid.stream.receiver.viewmodel

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.stream.receiver.ui.GlassesState
import com.rokid.stream.receiver.ui.ScannedDevice
import com.rokid.stream.receiver.util.LocaleManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * ViewModel for Glasses Scanner functionality
 * 
 * Handles BLE scanning, GATT connection, and video streaming state
 * following MVVM architecture pattern
 */
class GlassesScannerViewModel : ViewModel() {
    
    companion object {
        private const val TAG = "GlassesScannerVM"
        
        // UUID that the phone app advertises
        val ROKID_STREAM_SERVICE_UUID: UUID = UUID.fromString("0000FFFF-0000-1000-8000-00805F9B34FB")
        
        // Characteristic UUIDs (must match phone's BleAdvertiser)
        val CONNECTION_MODE_CHAR_UUID: UUID = UUID.fromString("0000FF01-0000-1000-8000-00805F9B34FB")
        val STREAM_DIRECTION_CHAR_UUID: UUID = UUID.fromString("0000FF02-0000-1000-8000-00805F9B34FB")
        val STREAM_DATA_CHAR_UUID: UUID = UUID.fromString("0000FF03-0000-1000-8000-00805F9B34FB")
        val LANGUAGE_CHAR_UUID: UUID = UUID.fromString("0000FF04-0000-1000-8000-00805F9B34FB")
        
        // Video parameters
        const val VIDEO_WIDTH = 240
        const val VIDEO_HEIGHT = 240
        const val MAX_FRAME_SIZE = 1_000_000  // 1MB
        
        // Scan timeout in milliseconds
        private const val SCAN_TIMEOUT_MS = 30000L
    }
    
    // UI State
    private val _state = MutableStateFlow(GlassesState.DEVICE_LIST)
    val state: StateFlow<GlassesState> = _state.asStateFlow()
    
    private val _scannedDevices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val scannedDevices: StateFlow<List<ScannedDevice>> = _scannedDevices.asStateFlow()
    
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()
    
    private val _connectedDeviceName = MutableStateFlow("")
    val connectedDeviceName: StateFlow<String> = _connectedDeviceName.asStateFlow()
    
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
    
    // Connection settings received from phone
    private val _connectionSettings = MutableStateFlow(ConnectionSettings())
    val connectionSettings: StateFlow<ConnectionSettings> = _connectionSettings.asStateFlow()
    
    // Bluetooth components (initialized from Activity)
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var bluetoothGatt: BluetoothGatt? = null
    
    // L2CAP and streaming
    private var l2capSocket: BluetoothSocket? = null
    @Volatile
    private var isStreaming = false
    
    // Internal state
    private var scanJob: Job? = null
    private val deviceMap = mutableMapOf<String, ScannedDevice>()
    
    // Callbacks for Activity to provide context-dependent functionality
    var onLanguageReceived: ((Byte) -> Unit)? = null
    var onL2capReady: ((Int) -> Unit)? = null  // PSM value
    var onSettingsReceived: ((ConnectionSettings) -> Unit)? = null
    
    /**
     * Initialize Bluetooth components
     */
    fun initBluetooth(adapter: BluetoothAdapter?) {
        bluetoothAdapter = adapter
        bleScanner = adapter?.bluetoothLeScanner
    }
    
    /**
     * Start BLE scanning for RokidStream devices
     */
    @SuppressLint("MissingPermission")
    fun startScan() {
        if (_isScanning.value) {
            Log.d(TAG, "Already scanning")
            return
        }
        
        if (bleScanner == null) {
            Log.e(TAG, "BLE Scanner not available")
            _errorMessage.value = "Bluetooth scanner not available"
            return
        }
        
        deviceMap.clear()
        _scannedDevices.value = emptyList()
        _isScanning.value = true
        
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
            bleScanner?.startScan(generalScanCallback)
            
            // Set scan timeout
            scanJob = viewModelScope.launch {
                delay(SCAN_TIMEOUT_MS)
                stopScan()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start scan", e)
            _isScanning.value = false
            _errorMessage.value = "Failed to start scan: ${e.message}"
        }
    }
    
    /**
     * Stop BLE scanning
     */
    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!_isScanning.value) return
        
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
    }
    
    /**
     * Connect to a scanned device
     */
    @SuppressLint("MissingPermission")
    fun connectToDevice(context: Context, device: ScannedDevice) {
        Log.d(TAG, "Connecting to device: ${device.name} (${device.address})")
        
        stopScan()
        
        _state.value = GlassesState.CONNECTING
        _connectedDeviceName.value = device.name.ifEmpty { device.address }
        
        val bluetoothDevice = bluetoothAdapter?.getRemoteDevice(device.address)
        
        if (bluetoothDevice == null) {
            Log.e(TAG, "Failed to get Bluetooth device")
            _state.value = GlassesState.DEVICE_LIST
            _errorMessage.value = "Failed to get Bluetooth device"
            return
        }
        
        bluetoothGatt = bluetoothDevice.connectGatt(
            context, 
            false, 
            gattCallback, 
            BluetoothDevice.TRANSPORT_LE
        )
    }
    
    /**
     * Disconnect from current device
     */
    @SuppressLint("MissingPermission")
    fun disconnect() {
        Log.d(TAG, "Disconnecting...")
        
        isStreaming = false
        
        try {
            l2capSocket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing L2CAP socket", e)
        }
        l2capSocket = null
        
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        
        _state.value = GlassesState.DEVICE_LIST
        _connectedDeviceName.value = ""
        _connectionSettings.value = ConnectionSettings()
    }
    
    /**
     * Clear error message
     */
    fun clearError() {
        _errorMessage.value = null
    }
    
    // Scan callbacks
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
            _errorMessage.value = "Scan failed with error: $errorCode"
        }
    }
    
    private val generalScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
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
        
        val scannedDevice = ScannedDevice(address, name, rssi)
        deviceMap[address] = scannedDevice
        
        // Sort by signal strength and update state
        _scannedDevices.value = deviceMap.values
            .toList()
            .sortedByDescending { it.rssi }
        
        Log.d(TAG, "Found device: $name ($address) RSSI: $rssi")
    }
    
    // GATT callback
    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "GATT Connected, discovering services...")
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "GATT Disconnected")
                    viewModelScope.launch(Dispatchers.Main) {
                        _state.value = GlassesState.DEVICE_LIST
                        _connectedDeviceName.value = ""
                    }
                    gatt.close()
                    bluetoothGatt = null
                }
            }
        }
        
        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                viewModelScope.launch(Dispatchers.Main) {
                    _state.value = GlassesState.DEVICE_LIST
                    _errorMessage.value = "Service discovery failed"
                }
                return
            }
            
            Log.d(TAG, "Services discovered, reading connection settings...")
            
            val service = gatt.getService(ROKID_STREAM_SERVICE_UUID)
            if (service == null) {
                Log.e(TAG, "RokidStream service not found")
                viewModelScope.launch(Dispatchers.Main) {
                    _state.value = GlassesState.DEVICE_LIST
                    _errorMessage.value = "RokidStream service not found"
                }
                return
            }
            
            val modeChar = service.getCharacteristic(CONNECTION_MODE_CHAR_UUID)
            if (modeChar != null) {
                gatt.readCharacteristic(modeChar)
            } else {
                handleSettingsComplete()
            }
        }
        
        @SuppressLint("MissingPermission")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt, 
            characteristic: BluetoothGattCharacteristic, 
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Characteristic read failed: $status")
                return
            }
            
            val currentSettings = _connectionSettings.value
            
            when (characteristic.uuid) {
                CONNECTION_MODE_CHAR_UUID -> {
                    val mode = characteristic.value?.getOrNull(0) ?: 0
                    _connectionSettings.value = currentSettings.copy(connectionMode = mode)
                    Log.d(TAG, "Connection mode: $mode")
                    
                    readNextCharacteristic(gatt, STREAM_DIRECTION_CHAR_UUID)
                }
                STREAM_DIRECTION_CHAR_UUID -> {
                    val direction = characteristic.value?.getOrNull(0) ?: 0
                    _connectionSettings.value = currentSettings.copy(streamDirection = direction)
                    Log.d(TAG, "Stream direction: $direction")
                    
                    readNextCharacteristic(gatt, LANGUAGE_CHAR_UUID)
                }
                LANGUAGE_CHAR_UUID -> {
                    val language = characteristic.value?.getOrNull(0) ?: 0
                    _connectionSettings.value = currentSettings.copy(languageCode = language)
                    Log.d(TAG, "Language code: $language")
                    
                    onLanguageReceived?.invoke(language)
                    
                    readNextCharacteristic(gatt, STREAM_DATA_CHAR_UUID)
                }
                STREAM_DATA_CHAR_UUID -> {
                    val data = characteristic.value
                    if (data != null && data.size >= 4) {
                        val psm = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).int
                        _connectionSettings.value = currentSettings.copy(l2capPsm = psm)
                        Log.d(TAG, "L2CAP PSM: $psm")
                        
                        onL2capReady?.invoke(psm)
                    }
                    handleSettingsComplete()
                }
            }
        }
    }
    
    @SuppressLint("MissingPermission")
    private fun readNextCharacteristic(gatt: BluetoothGatt, uuid: UUID) {
        val service = gatt.getService(ROKID_STREAM_SERVICE_UUID)
        val char = service?.getCharacteristic(uuid)
        if (char != null) {
            gatt.readCharacteristic(char)
        } else {
            if (uuid == STREAM_DATA_CHAR_UUID) {
                handleSettingsComplete()
            } else {
                // Skip to next
                val nextUuid = when (uuid) {
                    STREAM_DIRECTION_CHAR_UUID -> LANGUAGE_CHAR_UUID
                    LANGUAGE_CHAR_UUID -> STREAM_DATA_CHAR_UUID
                    else -> null
                }
                if (nextUuid != null) {
                    readNextCharacteristic(gatt, nextUuid)
                } else {
                    handleSettingsComplete()
                }
            }
        }
    }
    
    private fun handleSettingsComplete() {
        val settings = _connectionSettings.value
        
        val modeName = when (settings.connectionMode.toInt()) {
            1 -> "L2CAP"
            2 -> "WiFi"
            3 -> "Rokid SDK"
            else -> "Unknown"
        }
        
        val directionName = when (settings.streamDirection.toInt()) {
            1 -> "Phone → Glasses"
            2 -> "Glasses → Phone"
            else -> "Unknown"
        }
        
        Log.d(TAG, "Settings received: mode=$modeName, direction=$directionName")
        
        viewModelScope.launch(Dispatchers.Main) {
            _state.value = GlassesState.CONNECTED
            onSettingsReceived?.invoke(settings)
        }
    }
    
    /**
     * Update state to streaming
     */
    fun setStreaming(streaming: Boolean) {
        isStreaming = streaming
        _state.value = if (streaming) GlassesState.STREAMING else GlassesState.CONNECTED
    }
    
    override fun onCleared() {
        super.onCleared()
        stopScan()
        disconnect()
    }
}

/**
 * Connection settings received from phone
 */
data class ConnectionSettings(
    val connectionMode: Byte = 0,
    val streamDirection: Byte = 0,
    val languageCode: Byte = 0,
    val l2capPsm: Int = 0
)
