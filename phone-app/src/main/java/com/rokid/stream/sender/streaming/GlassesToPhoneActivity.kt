package com.rokid.stream.sender.streaming

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.rokid.stream.sender.R
import com.rokid.stream.sender.ble.BleAdvertiser
import com.rokid.stream.sender.core.VideoDecoder
import com.rokid.stream.sender.util.LocaleManager
import java.io.InputStream

/**
 * GlassesToPhoneActivity - Receive video from glasses and display on phone
 * 
 * This activity handles:
 * - BLE advertising so glasses can discover the phone
 * - L2CAP connection from glasses
 * - Receiving H.264 video frames
 * - Decoding and displaying video
 * 
 * Architecture: Phone ADVERTISES, Glasses SCAN and connect
 */
class GlassesToPhoneActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "GlassesToPhone"
    }
    
    // UI components
    private lateinit var videoSurface: SurfaceView
    private lateinit var waitingOverlay: LinearLayout
    private lateinit var btnConnect: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvStats: TextView
    private lateinit var statusIndicator: View
    private lateinit var logView: TextView
    
    // Core components - Use BleAdvertiser instead of ConnectionManager
    private lateinit var bleAdvertiser: BleAdvertiser
    private lateinit var videoDecoder: VideoDecoder
    
    // State
    @Volatile
    private var isReceiving = false
    
    @Volatile
    private var surface: Surface? = null
    
    @Volatile
    private var isSurfaceValid = false
    
    private val surfaceLock = Object()
    
    // Input stream for receiving frames
    @Volatile
    private var inputStream: InputStream? = null
    
    // Stats
    private var framesReceived = 0
    private var lastStatsTime = 0L
    
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.applyLocale(newBase))
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_glasses_to_phone)
        
        initViews()
        initComponents()
        setupCallbacks()
        setupSurface()
    }
    
    private fun initViews() {
        videoSurface = findViewById(R.id.video_surface)
        waitingOverlay = findViewById(R.id.waiting_overlay)
        btnConnect = findViewById(R.id.btn_connect)
        btnStop = findViewById(R.id.btn_stop)
        tvStatus = findViewById(R.id.tv_status)
        tvStats = findViewById(R.id.tv_stats)
        statusIndicator = findViewById(R.id.status_indicator)
        logView = findViewById(R.id.log_view)
        
        findViewById<android.widget.ImageButton>(R.id.btn_back).setOnClickListener {
            finish()
        }
        
        btnConnect.setOnClickListener {
            startAdvertising()
        }
        
        btnStop.setOnClickListener {
            stopReceiving()
        }
    }
    
    private fun initComponents() {
        bleAdvertiser = BleAdvertiser(this)
        // Set direction: Glasses send TO Phone (phone receives)
        bleAdvertiser.streamDirection = BleAdvertiser.StreamDirectionType.GLASSES_TO_PHONE
        
        videoDecoder = VideoDecoder()
    }
    
    private fun setupCallbacks() {
        // When a device (glasses) connects via GATT
        bleAdvertiser.onDeviceConnected = { device ->
            log("眼鏡已連接: ${device.name ?: device.address}")
        }
        
        bleAdvertiser.onDeviceDisconnected = { device ->
            log("眼鏡已斷開: ${device.name ?: device.address}")
            // Update state and UI, don't call stopReceiving() to avoid recursion
            isReceiving = false
            inputStream = null
            runOnUiThread {
                updateConnectionStatus(false)
                btnConnect.isEnabled = true
                btnStop.isEnabled = false
                waitingOverlay.visibility = View.VISIBLE
            }
        }
        
        // When glasses connect via L2CAP and send data
        bleAdvertiser.onL2capClientConnected = { _, inStream ->
            log("L2CAP 連線建立，開始接收視訊...")
            inputStream = inStream
            isReceiving = true
            
            runOnUiThread {
                updateConnectionStatus(true)
                btnConnect.isEnabled = false
                btnStop.isEnabled = true
            }
            
            // Start receiver thread
            Thread { receiveFrames() }.start()
        }
        
        bleAdvertiser.onL2capClientDisconnected = {
            log("L2CAP 連線已斷開")
            // Only update UI and state, don't call stopReceiving() to avoid recursion
            isReceiving = false
            inputStream = null
            runOnUiThread {
                updateConnectionStatus(false)
                btnConnect.isEnabled = true
                btnStop.isEnabled = false
                waitingOverlay.visibility = View.VISIBLE
            }
        }
    }
    
    private fun setupSurface() {
        videoSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                synchronized(surfaceLock) {
                    surface = holder.surface
                    isSurfaceValid = true
                    log("Surface created")
                    surfaceLock.notifyAll()
                }
            }
            
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                synchronized(surfaceLock) {
                    surface = holder.surface
                    isSurfaceValid = true
                    log("Surface changed: ${width}x${height}")
                    surfaceLock.notifyAll()
                }
            }
            
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                synchronized(surfaceLock) {
                    isSurfaceValid = false
                    surface = null
                    log("Surface destroyed")
                }
            }
        })
    }
    
    private fun startAdvertising() {
        // Check permissions
        if (!bleAdvertiser.hasBluetoothPermissions()) {
            log("缺少藍牙權限")
            return
        }
        
        log("開始廣播 (UUID: 0000FFFF-...)，等待眼鏡連線...")
        log("請確保眼鏡端正在掃描")
        btnConnect.isEnabled = false
        tvStatus.text = "廣播中..."
        
        val started = bleAdvertiser.startAdvertising()
        if (started) {
            log("✓ 廣播啟動成功，等待眼鏡掃描...")
        } else {
            log("✗ 廣播啟動失敗")
            btnConnect.isEnabled = true
            tvStatus.text = "廣播失敗"
        }
    }
    
    private fun receiveFrames() {
        log("Receiver thread started")
        framesReceived = 0
        lastStatsTime = System.currentTimeMillis()
        
        // Wait for surface
        synchronized(surfaceLock) {
            var waitCount = 0
            while (!isSurfaceValid && isReceiving && waitCount < 50) {
                try {
                    surfaceLock.wait(100)
                    waitCount++
                } catch (e: InterruptedException) {
                    break
                }
            }
            
            if (isSurfaceValid) {
                videoDecoder.setSurface(surface)
            }
        }
        
        try {
            val stream = inputStream ?: return
            
            while (isReceiving) {
                // Read frame header (4 bytes for length)
                val headerBuffer = ByteArray(4)
                var bytesRead = 0
                while (bytesRead < 4 && isReceiving) {
                    val read = stream.read(headerBuffer, bytesRead, 4 - bytesRead)
                    if (read == -1) {
                        log("Connection closed")
                        break
                    }
                    bytesRead += read
                }
                
                if (bytesRead < 4) break
                
                // Parse frame length (little-endian to match glasses sender)
                val frameLength = java.nio.ByteBuffer.wrap(headerBuffer)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN).int
                
                if (frameLength <= 0 || frameLength > 1_000_000) {
                    log("Invalid frame length: $frameLength")
                    continue
                }
                
                // Read frame data
                val frameData = ByteArray(frameLength)
                bytesRead = 0
                while (bytesRead < frameLength && isReceiving) {
                    val read = stream.read(frameData, bytesRead, frameLength - bytesRead)
                    if (read == -1) break
                    bytesRead += read
                }
                
                if (bytesRead < frameLength) break
                
                framesReceived++
                
                // Initialize decoder if needed
                if (!videoDecoder.isRunning && videoDecoder.isCodecConfig(frameData)) {
                    videoDecoder.start(frameData)
                    runOnUiThread {
                        waitingOverlay.visibility = View.GONE
                    }
                }
                
                // Decode frame
                if (videoDecoder.isRunning) {
                    videoDecoder.decodeFrame(frameData)
                }
                
                // Update stats periodically
                updateStats()
            }
        } catch (e: Exception) {
            log("Receiver error: ${e.message}")
        } finally {
            log("Receiver thread stopped")
        }
    }
    
    private fun updateStats() {
        val now = System.currentTimeMillis()
        if (now - lastStatsTime >= 1000) {
            val (received, decoded) = videoDecoder.getStats()
            lastStatsTime = now
            
            runOnUiThread {
                tvStats.text = "Received: $framesReceived | Decoded: $decoded"
            }
        }
    }
    
    @SuppressLint("ResourceType")
    private fun updateConnectionStatus(connected: Boolean) {
        if (connected) {
            statusIndicator.setBackgroundColor(getColor(android.R.color.holo_green_dark))
            tvStatus.text = "已連線"
        } else {
            statusIndicator.setBackgroundColor(getColor(android.R.color.holo_red_dark))
            tvStatus.text = "未連線"
        }
    }
    
    private fun stopReceiving() {
        isReceiving = false
        framesReceived = 0
        
        videoDecoder.stop()
        bleAdvertiser.stopAdvertising()
        inputStream = null
        
        runOnUiThread {
            updateConnectionStatus(false)
            btnConnect.isEnabled = true
            btnStop.isEnabled = false
            waitingOverlay.visibility = View.VISIBLE
            tvStats.text = "Received: 0 | Decoded: 0"
        }
        
        log("Streaming stopped")
    }
    
    private fun log(message: String) {
        Log.d(TAG, message)
        runOnUiThread {
            logView.append("$message\n")
            val scrollView = logView.parent as? android.widget.ScrollView
            scrollView?.fullScroll(android.widget.ScrollView.FOCUS_DOWN)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopReceiving()
        bleAdvertiser.stopAdvertising()
    }
}
