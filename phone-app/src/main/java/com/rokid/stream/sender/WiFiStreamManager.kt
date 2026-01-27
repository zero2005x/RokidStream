package com.rokid.stream.sender

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WiFiStreamManager - Manages WiFi-based video streaming via TCP sockets
 * 
 * Sender side: Discovers receiver service via mDNS/NSD, connects and streams video
 * 
 * Protocol:
 * - Uses mDNS to discover "_rokidstream._tcp" service
 * - Two TCP connections: one for phone→glasses, one for glasses→phone
 * - Frame format: [4-byte length][1-byte type][payload]
 *   - Type 0x01: Video frame (H.264)
 *   - Type 0x02: Control message (JSON)
 */
class WiFiStreamManager(private val context: Context) {
    
    companion object {
        private const val TAG = "WiFiStreamManager"
        const val SERVICE_TYPE = "_rokidstream._tcp."
        const val SERVICE_NAME = "RokidStream"
        
        // Port for phone → glasses streaming
        const val STREAM_PORT = 18800
        // Port for glasses → phone streaming (reverse)
        const val REVERSE_PORT = 18801
        
        // Frame types
        const val FRAME_TYPE_VIDEO: Byte = 0x01
        const val FRAME_TYPE_CONTROL: Byte = 0x02
        const val FRAME_TYPE_HEARTBEAT: Byte = 0x03
        
        // Connection timeout
        const val CONNECT_TIMEOUT_MS = 10000
        const val READ_TIMEOUT_MS = 30000
    }
    
    interface ConnectionCallback {
        fun onServiceDiscovered(serviceName: String, host: String, port: Int)
        fun onConnected()
        fun onDisconnected()
        fun onError(error: String)
    }
    
    interface StreamCallback {
        fun onVideoFrameReceived(frameData: ByteArray, timestamp: Long, isKeyFrame: Boolean)
        fun onControlMessageReceived(message: String)
    }
    
    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var resolveListener: NsdManager.ResolveListener? = null
    
    private var sendSocket: Socket? = null
    private var receiveSocket: Socket? = null
    private var sendOutputStream: DataOutputStream? = null
    private var receiveInputStream: DataInputStream? = null
    
    private val executor: ExecutorService = Executors.newFixedThreadPool(3)
    private val isConnected = AtomicBoolean(false)
    private val isReceiving = AtomicBoolean(false)
    
    private var connectionCallback: ConnectionCallback? = null
    private var streamCallback: StreamCallback? = null
    
    private var targetHost: String? = null
    private var targetPort: Int = STREAM_PORT
    
    fun setConnectionCallback(callback: ConnectionCallback) {
        connectionCallback = callback
    }
    
    fun setStreamCallback(callback: StreamCallback) {
        streamCallback = callback
    }
    
    /**
     * Start discovering receiver service via mDNS
     */
    fun startDiscovery() {
        nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "Service discovery started: $serviceType")
            }
            
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service found: ${serviceInfo.serviceName}")
                if (serviceInfo.serviceName.contains(SERVICE_NAME)) {
                    resolveService(serviceInfo)
                }
            }
            
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${serviceInfo.serviceName}")
            }
            
            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "Discovery stopped: $serviceType")
            }
            
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery start failed: $errorCode")
                connectionCallback?.onError("Discovery failed: $errorCode")
            }
            
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery stop failed: $errorCode")
            }
        }
        
        nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }
    
    private fun resolveService(serviceInfo: NsdServiceInfo) {
        resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Resolve failed: $errorCode")
                connectionCallback?.onError("Resolve failed: $errorCode")
            }
            
            override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                val host = resolvedInfo.host?.hostAddress ?: return
                val port = resolvedInfo.port
                Log.d(TAG, "Service resolved: $host:$port")
                
                targetHost = host
                targetPort = port
                
                connectionCallback?.onServiceDiscovered(resolvedInfo.serviceName, host, port)
            }
        }
        
        nsdManager?.resolveService(serviceInfo, resolveListener)
    }
    
    fun stopDiscovery() {
        try {
            discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping discovery: ${e.message}")
        }
    }
    
    /**
     * Connect directly to a known host:port (skip discovery)
     */
    fun connectDirect(host: String, port: Int = STREAM_PORT) {
        targetHost = host
        targetPort = port
        connect()
    }
    
    /**
     * Connect to the discovered or specified service
     */
    fun connect() {
        val host = targetHost ?: run {
            connectionCallback?.onError("No target host")
            return
        }
        
        executor.execute {
            try {
                // Connect send socket (phone → glasses)
                sendSocket = Socket().apply {
                    connect(InetSocketAddress(host, targetPort), CONNECT_TIMEOUT_MS)
                    soTimeout = READ_TIMEOUT_MS
                }
                sendOutputStream = DataOutputStream(sendSocket!!.getOutputStream())
                
                // Connect receive socket (glasses → phone)
                receiveSocket = Socket().apply {
                    connect(InetSocketAddress(host, REVERSE_PORT), CONNECT_TIMEOUT_MS)
                    soTimeout = READ_TIMEOUT_MS
                }
                receiveInputStream = DataInputStream(receiveSocket!!.getInputStream())
                
                isConnected.set(true)
                Log.d(TAG, "Connected to $host:$targetPort and :$REVERSE_PORT")
                connectionCallback?.onConnected()
                
                // Start receiving in background
                startReceiving()
                
            } catch (e: IOException) {
                Log.e(TAG, "Connection failed: ${e.message}")
                connectionCallback?.onError("Connection failed: ${e.message}")
                disconnect()
            }
        }
    }
    
    /**
     * Send a video frame to the receiver
     */
    fun sendVideoFrame(frameData: ByteArray, timestamp: Long, isKeyFrame: Boolean) {
        if (!isConnected.get()) return
        
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
                handleConnectionError()
            }
        }
    }
    
    /**
     * Send a control message (JSON string)
     */
    fun sendControlMessage(message: String) {
        if (!isConnected.get()) return
        
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
    
    private fun startReceiving() {
        if (isReceiving.getAndSet(true)) return
        
        executor.execute {
            val input = receiveInputStream ?: return@execute
            
            try {
                while (isConnected.get() && isReceiving.get()) {
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
                            // Skip unknown data
                            val skipSize = totalLength - 1
                            if (skipSize > 0) {
                                input.skipBytes(skipSize)
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                if (isConnected.get()) {
                    Log.e(TAG, "Receive error: ${e.message}")
                    handleConnectionError()
                }
            } finally {
                isReceiving.set(false)
            }
        }
    }
    
    private fun handleConnectionError() {
        if (isConnected.getAndSet(false)) {
            connectionCallback?.onDisconnected()
        }
    }
    
    fun disconnect() {
        isConnected.set(false)
        isReceiving.set(false)
        
        try {
            sendOutputStream?.close()
            receiveInputStream?.close()
            sendSocket?.close()
            receiveSocket?.close()
        } catch (e: IOException) {
            Log.w(TAG, "Error closing sockets: ${e.message}")
        }
        
        sendOutputStream = null
        receiveInputStream = null
        sendSocket = null
        receiveSocket = null
        
        connectionCallback?.onDisconnected()
    }
    
    fun isConnected(): Boolean = isConnected.get()
    
    fun release() {
        stopDiscovery()
        disconnect()
        executor.shutdown()
    }
}
