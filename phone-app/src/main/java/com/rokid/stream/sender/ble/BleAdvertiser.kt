package com.rokid.stream.sender.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * BLE Advertiser for Phone App
 * 
 * This class advertises the phone as a RokidStream device,
 * allowing glasses to discover and connect to it.
 * 
 * The phone broadcasts a specific UUID that the glasses scan for.
 */
class BleAdvertiser(private val context: Context) {
    
    companion object {
        private const val TAG = "BleAdvertiser"
        
        // Service UUID that glasses will scan for
        // This should match the UUID in GlassesScannerActivity
        val ROKID_STREAM_SERVICE_UUID: UUID = UUID.fromString("0000FFFF-0000-1000-8000-00805F9B34FB")
        
        // Characteristic UUIDs for data exchange
        val CONNECTION_MODE_CHAR_UUID: UUID = UUID.fromString("0000FF01-0000-1000-8000-00805F9B34FB")
        val STREAM_DIRECTION_CHAR_UUID: UUID = UUID.fromString("0000FF02-0000-1000-8000-00805F9B34FB")
        val STREAM_DATA_CHAR_UUID: UUID = UUID.fromString("0000FF03-0000-1000-8000-00805F9B34FB")
        val LANGUAGE_CHAR_UUID: UUID = UUID.fromString("0000FF04-0000-1000-8000-00805F9B34FB")
    }
    
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bleAdvertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var isAdvertising = false
    
    // L2CAP server socket for video streaming
    private var l2capServerSocket: BluetoothServerSocket? = null
    private var l2capClientSocket: BluetoothSocket? = null
    private var l2capPsm: Int = 0
    
    // Output stream for sending video data
    var videoOutputStream: OutputStream? = null
        private set
    
    // Input stream for receiving video data
    var videoInputStream: InputStream? = null
        private set
    
    // Connection settings (controlled by phone UI)
    var connectionMode: ConnectionModeType = ConnectionModeType.L2CAP
    var streamDirection: StreamDirectionType = StreamDirectionType.PHONE_TO_GLASSES
    var languageCode: Byte = 0  // Language code (0 = English)
    
    // Callbacks
    var onDeviceConnected: ((BluetoothDevice) -> Unit)? = null
    var onDeviceDisconnected: ((BluetoothDevice) -> Unit)? = null
    // Updated callback to provide both streams
    var onL2capClientConnected: ((OutputStream, InputStream) -> Unit)? = null
    var onL2capClientDisconnected: (() -> Unit)? = null
    
    enum class ConnectionModeType(val value: Byte) {
        L2CAP(0x01),
        WIFI(0x02),
        ROKID_SDK(0x03)
    }
    
    enum class StreamDirectionType(val value: Byte) {
        PHONE_TO_GLASSES(0x01),
        GLASSES_TO_PHONE(0x02)
    }
    
    init {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bleAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser
    }
    
    fun hasBluetoothPermissions(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) ==
                PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
    
    @SuppressLint("MissingPermission")
    fun startAdvertising(): Boolean {
        if (!hasBluetoothPermissions()) {
            Log.e(TAG, "Missing Bluetooth permissions")
            return false
        }
        
        if (bleAdvertiser == null) {
            Log.e(TAG, "BLE Advertiser not available")
            return false
        }
        
        if (isAdvertising) {
            Log.d(TAG, "Already advertising")
            return true
        }
        
        // Start L2CAP server first to get PSM
        startL2capServer()
        
        // Start GATT server
        startGattServer()
        
        // Advertise settings
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()
        
        // Advertise data with our service UUID
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(ROKID_STREAM_SERVICE_UUID))
            .build()
        
        // Scan response with additional data
        val scanResponse = AdvertiseData.Builder()
            .setIncludeTxPowerLevel(true)
            .build()
        
        try {
            bleAdvertiser?.startAdvertising(settings, data, scanResponse, advertiseCallback)
            Log.d(TAG, "Started advertising")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start advertising", e)
            return false
        }
    }
    
    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        if (!isAdvertising) return
        
        try {
            bleAdvertiser?.stopAdvertising(advertiseCallback)
            gattServer?.close()
            gattServer = null
            stopL2capServer()
            isAdvertising = false
            Log.d(TAG, "Stopped advertising")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop advertising", e)
        }
    }
    
    @SuppressLint("MissingPermission")
    private fun startL2capServer() {
        try {
            l2capServerSocket = bluetoothAdapter?.listenUsingInsecureL2capChannel()
            l2capPsm = l2capServerSocket?.psm ?: 0
            Log.d(TAG, "L2CAP server started on PSM: $l2capPsm")
            
            // Start accept thread
            Thread {
                acceptL2capConnections()
            }.start()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start L2CAP server", e)
        }
    }
    
    @SuppressLint("MissingPermission")
    private fun acceptL2capConnections() {
        while (isAdvertising || l2capServerSocket != null) {
            try {
                Log.d(TAG, "Waiting for L2CAP client connection...")
                val socket = l2capServerSocket?.accept() ?: break
                
                // Log L2CAP socket info
                val maxReceivePacketSize = socket.maxReceivePacketSize
                val maxTransmitPacketSize = socket.maxTransmitPacketSize
                Log.d(TAG, "L2CAP client connected: ${socket.remoteDevice?.name}")
                Log.d(TAG, "L2CAP MaxRx=$maxReceivePacketSize, MaxTx=$maxTransmitPacketSize")
                
                l2capClientSocket = socket
                videoOutputStream = socket.outputStream
                videoInputStream = socket.inputStream
                onL2capClientConnected?.invoke(socket.outputStream, socket.inputStream)
            } catch (e: IOException) {
                if (l2capServerSocket != null) {
                    Log.e(TAG, "L2CAP accept failed", e)
                }
                break
            }
        }
    }
    
    private fun stopL2capServer() {
        try {
            videoOutputStream = null
            videoInputStream = null
            l2capClientSocket?.close()
            l2capClientSocket = null
            l2capServerSocket?.close()
            l2capServerSocket = null
            l2capPsm = 0
            // Note: Don't invoke onL2capClientDisconnected here to avoid infinite recursion
            // The callback will be invoked from acceptL2capConnections when the socket is closed
            Log.d(TAG, "L2CAP server stopped")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to stop L2CAP server", e)
        }
    }
    
    @SuppressLint("MissingPermission")
    private fun startGattServer() {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
        
        // Create service with characteristics
        val service = BluetoothGattService(
            ROKID_STREAM_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
        
        // Connection mode characteristic (readable)
        val connectionModeChar = BluetoothGattCharacteristic(
            CONNECTION_MODE_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        service.addCharacteristic(connectionModeChar)
        
        // Stream direction characteristic (readable)
        val streamDirectionChar = BluetoothGattCharacteristic(
            STREAM_DIRECTION_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        service.addCharacteristic(streamDirectionChar)
        
        // Stream data characteristic (for L2CAP PSM exchange)
        val streamDataChar = BluetoothGattCharacteristic(
            STREAM_DATA_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or 
            BluetoothGattCharacteristic.PROPERTY_WRITE or
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(streamDataChar)
        
        // Language characteristic (readable by glasses)
        val languageChar = BluetoothGattCharacteristic(
            LANGUAGE_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        service.addCharacteristic(languageChar)
        
        gattServer?.addService(service)
        Log.d(TAG, "GATT server started with RokidStream service")
    }
    
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            isAdvertising = true
            Log.d(TAG, "Advertising started successfully with UUID: $ROKID_STREAM_SERVICE_UUID")
            Log.d(TAG, "L2CAP PSM: $l2capPsm")
        }
        
        override fun onStartFailure(errorCode: Int) {
            isAdvertising = false
            val errorMessage = when (errorCode) {
                ADVERTISE_FAILED_DATA_TOO_LARGE -> "Data too large"
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Too many advertisers"
                ADVERTISE_FAILED_ALREADY_STARTED -> "Already started"
                ADVERTISE_FAILED_INTERNAL_ERROR -> "Internal error"
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported"
                else -> "Unknown error ($errorCode)"
            }
            Log.e(TAG, "Advertising failed: $errorMessage")
        }
    }
    
    private val gattServerCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "★ Device connected: ${device.name ?: device.address}")
                    onDeviceConnected?.invoke(device)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Device disconnected: ${device.name ?: device.address}")
                    onDeviceDisconnected?.invoke(device)
                }
            }
        }
        
        @SuppressLint("MissingPermission")
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value = when (characteristic.uuid) {
                CONNECTION_MODE_CHAR_UUID -> byteArrayOf(connectionMode.value)
                STREAM_DIRECTION_CHAR_UUID -> byteArrayOf(streamDirection.value)
                LANGUAGE_CHAR_UUID -> byteArrayOf(languageCode)
                STREAM_DATA_CHAR_UUID -> {
                    // Return L2CAP PSM as 4-byte little-endian integer
                    ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(l2capPsm).array()
                }
                else -> byteArrayOf()
            }
            
            gattServer?.sendResponse(
                device,
                requestId,
                BluetoothGatt.GATT_SUCCESS,
                offset,
                value
            )
            
            Log.d(TAG, "Characteristic read: ${characteristic.uuid}, value: ${value.contentToString()}")
        }
        
        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            Log.d(TAG, "Characteristic write: ${characteristic.uuid}, value: ${value.contentToString()}")
            
            if (responseNeeded) {
                gattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    offset,
                    value
                )
            }
        }
    }
    
    /**
     * Update connection mode and notify connected devices
     */
    @SuppressLint("MissingPermission")
    fun updateConnectionMode(mode: ConnectionModeType) {
        connectionMode = mode
        // TODO: Notify connected devices of the change
        Log.d(TAG, "Connection mode updated: $mode")
    }
    
    /**
     * Update stream direction and notify connected devices
     */
    @SuppressLint("MissingPermission")
    fun updateStreamDirection(direction: StreamDirectionType) {
        streamDirection = direction
        // TODO: Notify connected devices of the change
        Log.d(TAG, "Stream direction updated: $direction")
    }
    
    /**
     * Update language code and notify connected devices
     */
    @SuppressLint("MissingPermission")
    fun updateLanguage(code: Byte) {
        languageCode = code
        // TODO: Notify connected devices of the change
        Log.d(TAG, "Language code updated: $code")
    }
}
