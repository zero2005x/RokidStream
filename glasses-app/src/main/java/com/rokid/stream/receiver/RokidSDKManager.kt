package com.rokid.stream.receiver

import android.content.Context
import android.util.Log

/**
 * RokidSDKManager - Manages Rokid CXR-M SDK connection and messaging for Receiver
 * 
 * This class provides an abstraction layer for the Rokid CXR-M SDK on the glasses side,
 * enabling connection from mobile devices via Wi-Fi/ARTC protocol
 * as an alternative to the BLE L2CAP method.
 * 
 * Note: On YodaOS-Sprite devices, this would typically use CXR-S SDK.
 * For Android-based glasses, CXR-M can be used in server mode.
 */
class RokidSDKManager(private val context: Context) {

    companion object {
        private const val TAG = "RokidSDKManager"
        
        // Connection states
        const val STATE_IDLE = 0
        const val STATE_ADVERTISING = 1
        const val STATE_CONNECTED = 2
    }

    // Current connection state
    @Volatile
    private var connectionState = STATE_IDLE
    
    // Connected client info
    private var connectedClientId: String? = null
    
    // Callback interfaces
    interface ConnectionCallback {
        fun onClientConnected(clientId: String)
        fun onClientDisconnected(reason: String)
        fun onAdvertisingStarted()
        fun onError(error: String)
    }
    
    interface MessageCallback {
        fun onMapMessageReceived(data: Map<String, Any>)
        fun onBytesReceived(data: ByteArray)
        fun onVideoFrameReceived(frameData: ByteArray, timestamp: Long, isKeyFrame: Boolean)
    }
    
    private var connectionCallback: ConnectionCallback? = null
    private var messageCallback: MessageCallback? = null
    
    /**
     * Initialize the Rokid SDK for receiver mode
     */
    fun initialize(): Boolean {
        return try {
            Log.d(TAG, "Initializing Rokid SDK for receiver...")
            
            // TODO: Replace with actual SDK initialization
            // For YodaOS-Sprite, this would be CXR-S SDK
            // For Android glasses, configure CXR-M in server mode
            
            Log.d(TAG, "Rokid SDK initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Rokid SDK: ${e.message}")
            false
        }
    }
    
    /**
     * Set connection callback
     */
    fun setConnectionCallback(callback: ConnectionCallback) {
        this.connectionCallback = callback
    }
    
    /**
     * Set message callback
     */
    fun setMessageCallback(callback: MessageCallback) {
        this.messageCallback = callback
    }
    
    /**
     * Start advertising/listening for incoming connections
     */
    fun startAdvertising() {
        if (connectionState != STATE_IDLE) {
            Log.w(TAG, "Cannot start advertising: current state = $connectionState")
            return
        }
        
        try {
            Log.d(TAG, "Starting advertising for mobile connections...")
            connectionState = STATE_ADVERTISING
            
            // TODO: Replace with actual SDK advertising
            // Configure device to accept incoming connections
            
            connectionCallback?.onAdvertisingStarted()
            
        } catch (e: Exception) {
            Log.e(TAG, "Advertising failed: ${e.message}")
            connectionState = STATE_IDLE
            connectionCallback?.onError("Advertising failed: ${e.message}")
        }
    }
    
    /**
     * Stop advertising
     */
    fun stopAdvertising() {
        if (connectionState == STATE_ADVERTISING) {
            try {
                Log.d(TAG, "Stopping advertising...")
                
                // TODO: Replace with actual SDK stop advertising
                
                connectionState = STATE_IDLE
            } catch (e: Exception) {
                Log.e(TAG, "Stop advertising error: ${e.message}")
            }
        }
    }
    
    /**
     * Disconnect current client
     */
    fun disconnectClient() {
        if (connectionState == STATE_CONNECTED) {
            try {
                Log.d(TAG, "Disconnecting client...")
                
                // TODO: Replace with actual SDK disconnect
                
                connectionState = STATE_IDLE
                val clientId = connectedClientId
                connectedClientId = null
                connectionCallback?.onClientDisconnected("Server disconnected client")
                
            } catch (e: Exception) {
                Log.e(TAG, "Disconnect error: ${e.message}")
            }
        }
    }
    
    /**
     * Send a Map message to connected mobile app
     */
    fun sendMessage(data: Map<String, Any>): Boolean {
        if (connectionState != STATE_CONNECTED) {
            Log.w(TAG, "Cannot send message: no client connected")
            return false
        }
        
        return try {
            Log.d(TAG, "Sending map message to client: ${data.keys}")
            
            // TODO: Replace with actual SDK send
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Send message failed: ${e.message}")
            false
        }
    }
    
    /**
     * Send binary data (e.g., video frames from glasses camera) to mobile app
     */
    fun sendBytes(data: ByteArray): Boolean {
        if (connectionState != STATE_CONNECTED) {
            Log.w(TAG, "Cannot send bytes: no client connected")
            return false
        }
        
        return try {
            // TODO: Replace with actual SDK send bytes
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Send bytes failed: ${e.message}")
            false
        }
    }
    
    /**
     * Send video frame from glasses camera to mobile app
     */
    fun sendVideoFrame(frameData: ByteArray, timestamp: Long, isKeyFrame: Boolean): Boolean {
        if (connectionState != STATE_CONNECTED) {
            return false
        }
        
        return try {
            // Create header for video frame
            val header = createFrameHeader(frameData.size, timestamp, isKeyFrame)
            val packet = header + frameData
            sendBytes(packet)
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Send video frame failed: ${e.message}")
            false
        }
    }
    
    /**
     * Parse received bytes to extract video frame
     */
    fun parseVideoFrame(data: ByteArray): Triple<ByteArray, Long, Boolean>? {
        if (data.size < 13) return null
        
        return try {
            val buffer = java.nio.ByteBuffer.wrap(data)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val frameSize = buffer.int
            val timestamp = buffer.long
            val isKeyFrame = buffer.get() == 1.toByte()
            
            if (data.size >= 13 + frameSize) {
                val frameData = data.copyOfRange(13, 13 + frameSize)
                Triple(frameData, timestamp, isKeyFrame)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parse video frame failed: ${e.message}")
            null
        }
    }
    
    /**
     * Create frame header for video streaming
     */
    private fun createFrameHeader(size: Int, timestamp: Long, isKeyFrame: Boolean): ByteArray {
        val header = java.nio.ByteBuffer.allocate(13)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.putInt(size)
        header.putLong(timestamp)
        header.put(if (isKeyFrame) 1 else 0)
        return header.array()
    }
    
    /**
     * Get current connection state
     */
    fun getConnectionState(): Int = connectionState
    
    /**
     * Check if a client is connected
     */
    fun isClientConnected(): Boolean = connectionState == STATE_CONNECTED
    
    /**
     * Get connected client ID
     */
    fun getConnectedClientId(): String? = connectedClientId
    
    /**
     * Handle incoming client connection (called by SDK callback)
     */
    internal fun onClientConnected(clientId: String) {
        Log.d(TAG, "Client connected: $clientId")
        connectionState = STATE_CONNECTED
        connectedClientId = clientId
        connectionCallback?.onClientConnected(clientId)
    }
    
    /**
     * Handle client disconnection (called by SDK callback)
     */
    internal fun onClientDisconnected(reason: String) {
        Log.d(TAG, "Client disconnected: $reason")
        connectionState = STATE_IDLE
        connectedClientId = null
        connectionCallback?.onClientDisconnected(reason)
    }
    
    /**
     * Handle incoming message (called by SDK callback)
     */
    internal fun onMessageReceived(data: Map<String, Any>) {
        messageCallback?.onMapMessageReceived(data)
    }
    
    /**
     * Handle incoming bytes (called by SDK callback)
     */
    internal fun onBytesReceived(data: ByteArray) {
        // Check if this is a video frame
        val videoFrame = parseVideoFrame(data)
        if (videoFrame != null) {
            messageCallback?.onVideoFrameReceived(videoFrame.first, videoFrame.second, videoFrame.third)
        } else {
            messageCallback?.onBytesReceived(data)
        }
    }
    
    /**
     * Release SDK resources
     */
    fun release() {
        try {
            disconnectClient()
            stopAdvertising()
            
            // TODO: Replace with actual SDK cleanup
            
            Log.d(TAG, "Rokid SDK released")
        } catch (e: Exception) {
            Log.e(TAG, "Release error: ${e.message}")
        }
    }
}
