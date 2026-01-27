package com.rokid.stream.receiver

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WiFiStreamManager - Manages WiFi-based video streaming via TCP sockets
 * 
 * Receiver side (glasses): Registers mDNS service, accepts connections, receives/sends video
 * 
 * Protocol:
 * - Registers "_rokidstream._tcp" service via mDNS
 * - Two TCP server sockets: one for receiving (phone→glasses), one for sending (glasses→phone)
 * - Frame format: [4-byte length][1-byte type][payload]
 *   - Type 0x01: Video frame (H.264)
 *   - Type 0x02: Control message (JSON)
 */
class WiFiStreamManager(private val context: Context) {
    
    companion object {
        private const val TAG = "WiFiStreamManager"
        const val SERVICE_TYPE = "_rokidstream._tcp."
        const val SERVICE_NAME = "RokidStream"
        
        // Port for phone → glasses streaming (we listen)
        const val STREAM_PORT = 18800
        // Port for glasses → phone streaming (we send)
        const val REVERSE_PORT = 18801
        
        // Frame types
        const val FRAME_TYPE_VIDEO: Byte = 0x01
        const val FRAME_TYPE_CONTROL: Byte = 0x02
        const val FRAME_TYPE_HEARTBEAT: Byte = 0x03
        
        const val READ_TIMEOUT_MS = 30000
    }
    
    interface ConnectionCallback {
        fun onServerStarted(port: Int)
        fun onClientConnected(clientAddress: String)
        fun onClientDisconnected()
        fun onError(error: String)
    }
    
    interface StreamCallback {
        fun onVideoFrameReceived(frameData: ByteArray, timestamp: Long, isKeyFrame: Boolean)
        fun onControlMessageReceived(message: String)
    }
    
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    
    private var receiveServerSocket: ServerSocket? = null
    private var sendServerSocket: ServerSocket? = null
    private var receiveClientSocket: Socket? = null
    private var sendClientSocket: Socket? = null
    
    private var receiveInputStream: DataInputStream? = null
    private var sendOutputStream: DataOutputStream? = null
    
    private val executor: ExecutorService = Executors.newFixedThreadPool(4)
    private val isRunning = AtomicBoolean(false)
    private val isClientConnected = AtomicBoolean(false)
    private val isReceiving = AtomicBoolean(false)
    
    private var connectionCallback: ConnectionCallback? = null
    private var streamCallback: StreamCallback? = null
    
    fun setConnectionCallback(callback: ConnectionCallback) {
        connectionCallback = callback
    }
    
    fun setStreamCallback(callback: StreamCallback) {
        streamCallback = callback
    }
    
    /**
     * Start the server and register mDNS service
     */
    fun startServer() {
        if (isRunning.getAndSet(true)) {
            Log.w(TAG, "Server already running")
            return
        }
        
        executor.execute {
            try {
                // Create server sockets
                receiveServerSocket = ServerSocket(STREAM_PORT).apply {
                    reuseAddress = true
                }
                sendServerSocket = ServerSocket(REVERSE_PORT).apply {
                    reuseAddress = true
                }
                
                Log.d(TAG, "Server started on ports $STREAM_PORT and $REVERSE_PORT")
                connectionCallback?.onServerStarted(STREAM_PORT)
                
                // Register mDNS service
                registerNsdService()
                
                // Accept connections
                acceptConnections()
                
            } catch (e: IOException) {
                Log.e(TAG, "Failed to start server: ${e.message}")
                connectionCallback?.onError("Server start failed: ${e.message}")
                stopServer()
            }
        }
    }
    
    private fun registerNsdService() {
        nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            port = STREAM_PORT
        }
        
        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service registered: ${serviceInfo.serviceName}")
            }
            
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Service registration failed: $errorCode")
                connectionCallback?.onError("mDNS registration failed: $errorCode")
            }
            
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service unregistered")
            }
            
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Service unregistration failed: $errorCode")
            }
        }
        
        nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }
    
    private fun acceptConnections() {
        // Accept receive connection (phone → glasses)
        executor.execute {
            try {
                while (isRunning.get()) {
                    val socket = receiveServerSocket?.accept() ?: break
                    socket.soTimeout = READ_TIMEOUT_MS
                    
                    // Close existing connection if any
                    receiveClientSocket?.close()
                    receiveInputStream?.close()
                    
                    receiveClientSocket = socket
                    receiveInputStream = DataInputStream(socket.getInputStream())
                    
                    val clientAddress = socket.inetAddress?.hostAddress ?: "unknown"
                    Log.d(TAG, "Receive client connected: $clientAddress")
                    
                    if (!isClientConnected.getAndSet(true)) {
                        connectionCallback?.onClientConnected(clientAddress)
                    }
                    
                    // Start receiving frames
                    startReceiving()
                }
            } catch (e: IOException) {
                if (isRunning.get()) {
                    Log.e(TAG, "Accept receive connection error: ${e.message}")
                }
            }
        }
        
        // Accept send connection (glasses → phone)
        executor.execute {
            try {
                while (isRunning.get()) {
                    val socket = sendServerSocket?.accept() ?: break
                    
                    // Close existing connection if any
                    sendClientSocket?.close()
                    sendOutputStream?.close()
                    
                    sendClientSocket = socket
                    sendOutputStream = DataOutputStream(socket.getOutputStream())
                    
                    Log.d(TAG, "Send client connected: ${socket.inetAddress?.hostAddress}")
                }
            } catch (e: IOException) {
                if (isRunning.get()) {
                    Log.e(TAG, "Accept send connection error: ${e.message}")
                }
            }
        }
    }
    
    private fun startReceiving() {
        if (isReceiving.getAndSet(true)) return
        
        executor.execute {
            val input = receiveInputStream ?: return@execute
            
            try {
                while (isRunning.get() && isClientConnected.get()) {
                    // Read frame header
                    val totalLength = input.readInt()
                    if (totalLength <= 0 || totalLength > 10_000_000) {
                        Log.w(TAG, "Invalid frame length: $totalLength")
                        continue
                    }
                    
                    val frameType = input.readByte()
                    
                    when (frameType) {
                        FRAME_TYPE_VIDEO -> {
                            val timestamp = input.readLong()
                            val isKeyFrame = input.readByte() == 1.toByte()
                            val payloadSize = totalLength - 10
                            
                            val frameData = ByteArray(payloadSize)
                            input.readFully(frameData)
                            
                            streamCallback?.onVideoFrameReceived(frameData, timestamp, isKeyFrame)
                        }
                        FRAME_TYPE_CONTROL -> {
                            val payloadSize = totalLength - 1
                            val messageBytes = ByteArray(payloadSize)
                            input.readFully(messageBytes)
                            val message = String(messageBytes, Charsets.UTF_8)
                            
                            streamCallback?.onControlMessageReceived(message)
                        }
                        FRAME_TYPE_HEARTBEAT -> {
                            // Heartbeat, ignore
                        }
                        else -> {
                            Log.w(TAG, "Unknown frame type: $frameType")
                            val skipSize = totalLength - 1
                            if (skipSize > 0) {
                                input.skipBytes(skipSize)
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                if (isClientConnected.get()) {
                    Log.e(TAG, "Receive error: ${e.message}")
                    handleClientDisconnected()
                }
            } finally {
                isReceiving.set(false)
            }
        }
    }
    
    /**
     * Send a video frame to the connected client (glasses → phone)
     */
    fun sendVideoFrame(frameData: ByteArray, timestamp: Long, isKeyFrame: Boolean) {
        if (!isClientConnected.get()) return
        
        executor.execute {
            try {
                val output = sendOutputStream ?: return@execute
                
                // Frame header: [4-byte total length][1-byte type][8-byte timestamp][1-byte keyframe flag]
                val headerSize = 10
                val totalLength = headerSize + frameData.size
                
                synchronized(output) {
                    output.writeInt(totalLength)
                    output.writeByte(FRAME_TYPE_VIDEO.toInt())
                    output.writeLong(timestamp)
                    output.writeByte(if (isKeyFrame) 1 else 0)
                    output.write(frameData)
                    output.flush()
                }
            } catch (e: IOException) {
                Log.e(TAG, "Send frame failed: ${e.message}")
            }
        }
    }
    
    /**
     * Send a control message (JSON string)
     */
    fun sendControlMessage(message: String) {
        if (!isClientConnected.get()) return
        
        executor.execute {
            try {
                val output = sendOutputStream ?: return@execute
                val messageBytes = message.toByteArray(Charsets.UTF_8)
                
                synchronized(output) {
                    output.writeInt(1 + messageBytes.size)
                    output.writeByte(FRAME_TYPE_CONTROL.toInt())
                    output.write(messageBytes)
                    output.flush()
                }
            } catch (e: IOException) {
                Log.e(TAG, "Send control message failed: ${e.message}")
            }
        }
    }
    
    private fun handleClientDisconnected() {
        if (isClientConnected.getAndSet(false)) {
            connectionCallback?.onClientDisconnected()
        }
    }
    
    fun stopServer() {
        isRunning.set(false)
        isClientConnected.set(false)
        isReceiving.set(false)
        
        // Unregister mDNS service
        try {
            registrationListener?.let { nsdManager?.unregisterService(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering service: ${e.message}")
        }
        
        // Close all sockets
        try {
            receiveInputStream?.close()
            sendOutputStream?.close()
            receiveClientSocket?.close()
            sendClientSocket?.close()
            receiveServerSocket?.close()
            sendServerSocket?.close()
        } catch (e: IOException) {
            Log.w(TAG, "Error closing sockets: ${e.message}")
        }
        
        receiveInputStream = null
        sendOutputStream = null
        receiveClientSocket = null
        sendClientSocket = null
        receiveServerSocket = null
        sendServerSocket = null
    }
    
    fun isClientConnected(): Boolean = isClientConnected.get()
    
    fun release() {
        stopServer()
        executor.shutdown()
    }
}
