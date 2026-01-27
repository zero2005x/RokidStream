package com.rokid.stream.sender.core

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * ConnectionManager - Unified connection management for BLE L2CAP
 * 
 * Handles BLE scanning, GATT connection, PSM discovery, and L2CAP channel management.
 * Designed to be reused across all streaming activities.
 */
class ConnectionManager(private val context: Context) {
    
    companion object {
        private const val TAG = "ConnectionManager"
        
        // Custom service UUID for Rokid streaming service
        val SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        
        // Characteristic UUID for reading PSM (Protocol/Service Multiplexer) value - phone -> glasses
        val PSM_CHAR_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        
        // Characteristic UUID for reading reverse PSM value - glasses -> phone
        val REVERSE_PSM_CHAR_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        
        const val MAX_FRAME_SIZE = 1_000_000  // 1MB for validation
    }
    
    // Bluetooth
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    
    // GATT connection
    private var bluetoothGatt: BluetoothGatt? = null
    
    // L2CAP sockets
    @Volatile
    private var sendSocket: BluetoothSocket? = null
    @Volatile
    private var receiveSocket: BluetoothSocket? = null
    
    // PSM values
    private var sendPsm: Int = 0
    private var receivePsm: Int = 0
    
    // State
    @Volatile
    var isConnected = false
        private set
    
    @Volatile
    var isScanning = false
        private set
    
    // Callbacks
    var onScanResult: ((BluetoothDevice) -> Unit)? = null
    var onConnected: ((OutputStream?, InputStream?) -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onLog: ((String) -> Unit)? = null
    
    private fun log(message: String) {
        Log.d(TAG, message)
        onLog?.invoke(message)
    }
    
    /**
     * Start BLE scanning for Rokid receiver device
     */
    @SuppressLint("MissingPermission")
    fun startScan() {
        if (isScanning) {
            log("Already scanning")
            return
        }
        
        log("Scanning for Rokid Receiver...")
        isScanning = true
        
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        val settings = android.bluetooth.le.ScanSettings.Builder()
            .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        
        scanner?.startScan(null, settings, scanCallback)
    }
    
    /**
     * Stop BLE scanning
     */
    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!isScanning) return
        
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping scan: ${e.message}")
        }
        isScanning = false
    }
    
    /**
     * BLE scan callback
     */
    private val scanCallback = object : android.bluetooth.le.ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult?) {
            result?.let {
                val foundUuids = it.scanRecord?.serviceUuids
                if (foundUuids != null && foundUuids.contains(android.os.ParcelUuid(SERVICE_UUID))) {
                    log("Found Rokid Device: ${it.device.address}")
                    stopScan()
                    onScanResult?.invoke(it.device)
                    connectGatt(it.device)
                }
            }
        }
        
        override fun onScanFailed(errorCode: Int) {
            log("Scan Failed with error code: $errorCode")
            isScanning = false
            onError?.invoke("Scan failed: $errorCode")
        }
    }
    
    /**
     * Connect to device via GATT
     */
    @SuppressLint("MissingPermission")
    fun connectGatt(device: BluetoothDevice) {
        log("Connecting GATT...")
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }
    
    /**
     * GATT callback
     */
    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    log("GATT Connected. Discovering services...")
                    gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    gatt?.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    log("GATT Disconnected")
                    disconnect()
                    onDisconnected?.invoke()
                }
            }
        }
        
        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Service discovery failed: $status")
                onError?.invoke("Service discovery failed")
                return
            }
            
            val service = gatt?.getService(SERVICE_UUID)
            val psmChar = service?.getCharacteristic(PSM_CHAR_UUID)
            if (psmChar != null) {
                log("Reading PSM characteristics...")
                gatt.readCharacteristic(psmChar)
            } else {
                log("PSM characteristic not found!")
                onError?.invoke("PSM characteristic not found")
            }
        }
        
        @SuppressLint("MissingPermission")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Failed to read characteristic: ${characteristic.uuid}")
                return
            }
            
            when (characteristic.uuid) {
                PSM_CHAR_UUID -> {
                    sendPsm = ByteBuffer.wrap(characteristic.value)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .int
                    log("Send PSM: $sendPsm")
                    
                    // Try to read reverse PSM
                    val service = gatt.getService(SERVICE_UUID)
                    val reversePsmChar = service?.getCharacteristic(REVERSE_PSM_CHAR_UUID)
                    if (reversePsmChar != null) {
                        gatt.readCharacteristic(reversePsmChar)
                    } else {
                        // No reverse PSM, connect with send only
                        connectL2cap(gatt.device, sendPsm, null)
                    }
                }
                REVERSE_PSM_CHAR_UUID -> {
                    receivePsm = ByteBuffer.wrap(characteristic.value)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .int
                    log("Receive PSM: $receivePsm")
                    
                    // Connect both L2CAP channels
                    connectL2cap(gatt.device, sendPsm, receivePsm)
                }
            }
        }
    }
    
    /**
     * Connect L2CAP channels
     */
    @SuppressLint("MissingPermission")
    private fun connectL2cap(device: BluetoothDevice, sendPsm: Int, receivePsm: Int?) {
        Thread {
            try {
                // Connect send channel
                log("Connecting L2CAP send channel (PSM $sendPsm)...")
                val socket = device.createInsecureL2capChannel(sendPsm)
                socket.connect()
                sendSocket = socket
                log("L2CAP Send Channel Connected!")
                
                var receiveInputStream: InputStream? = null
                
                // Connect receive channel if available
                if (receivePsm != null && receivePsm > 0) {
                    log("Connecting L2CAP receive channel (PSM $receivePsm)...")
                    try {
                        val reverseSocket = device.createInsecureL2capChannel(receivePsm)
                        reverseSocket.connect()
                        receiveSocket = reverseSocket
                        receiveInputStream = reverseSocket.inputStream
                        log("L2CAP Receive Channel Connected!")
                    } catch (e: IOException) {
                        log("Reverse channel failed: ${e.message}")
                    }
                }
                
                isConnected = true
                onConnected?.invoke(socket.outputStream, receiveInputStream)
                
            } catch (e: IOException) {
                log("L2CAP Connection Failed: ${e.message}")
                onError?.invoke("L2CAP connection failed: ${e.message}")
                disconnect()
            }
        }.start()
    }
    
    /**
     * Get send output stream
     */
    fun getSendOutputStream(): OutputStream? = sendSocket?.outputStream
    
    /**
     * Get receive input stream
     */
    fun getReceiveInputStream(): InputStream? = receiveSocket?.inputStream
    
    /**
     * Send a video frame
     * Format: [4-byte length][frame data]
     */
    fun sendFrame(frameData: ByteArray): Boolean {
        val outputStream = sendSocket?.outputStream ?: return false
        return try {
            val header = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            header.putInt(frameData.size)
            outputStream.write(header.array())
            outputStream.write(frameData)
            outputStream.flush()
            true
        } catch (e: IOException) {
            Log.e(TAG, "Send frame failed: ${e.message}")
            false
        }
    }
    
    /**
     * Read a video frame from receive stream
     * @return Frame data or null if error/disconnected
     */
    fun readFrame(): ByteArray? {
        val inputStream = receiveSocket?.inputStream ?: return null
        val headerBuffer = ByteArray(4)
        
        return try {
            if (!readFully(inputStream, headerBuffer, 4)) {
                return null
            }
            
            val length = ByteBuffer.wrap(headerBuffer)
                .order(ByteOrder.LITTLE_ENDIAN)
                .int
            
            if (length <= 0 || length > MAX_FRAME_SIZE) {
                Log.w(TAG, "Invalid frame length: $length")
                return null
            }
            
            val frameData = ByteArray(length)
            if (!readFully(inputStream, frameData, length)) {
                return null
            }
            
            frameData
        } catch (e: IOException) {
            Log.e(TAG, "Read frame failed: ${e.message}")
            null
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
     * Disconnect all connections
     */
    @SuppressLint("MissingPermission")
    fun disconnect() {
        isConnected = false
        stopScan()
        
        try {
            sendSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing send socket: ${e.message}")
        }
        sendSocket = null
        
        try {
            receiveSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing receive socket: ${e.message}")
        }
        receiveSocket = null
        
        try {
            bluetoothGatt?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing GATT: ${e.message}")
        }
        bluetoothGatt = null
        
        sendPsm = 0
        receivePsm = 0
    }
    
    /**
     * Release all resources
     */
    fun release() {
        disconnect()
    }
}
