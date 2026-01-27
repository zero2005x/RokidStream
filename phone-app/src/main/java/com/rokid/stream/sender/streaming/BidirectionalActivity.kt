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
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.rokid.stream.sender.R
import com.rokid.stream.sender.ble.BleAdvertiser
import com.rokid.stream.sender.core.VideoDecoder
import com.rokid.stream.sender.core.VideoEncoder
import com.rokid.stream.sender.util.LocaleManager
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * BidirectionalActivity - Simultaneous bidirectional video streaming
 * 
 * This activity handles:
 * - Sending phone camera video to glasses (encoding + transmission)
 * - Receiving glasses camera video on phone (reception + decoding)
 * - Both directions operate concurrently
 */
class BidirectionalActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "Bidirectional"
        const val FRAME_INTERVAL_MS = 100L  // ~10 FPS - optimal for BLE L2CAP
    }
    
    // UI components
    private lateinit var viewFinder: PreviewView
    private lateinit var receivedVideoView: SurfaceView
    private lateinit var waitingOverlay: LinearLayout
    private lateinit var btnConnect: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvSendFps: TextView
    private lateinit var tvReceiveStats: TextView
    private lateinit var statusIndicator: View
    private lateinit var logView: TextView
    
    // Core components
    private lateinit var bleAdvertiser: BleAdvertiser
    private lateinit var videoEncoder: VideoEncoder
    private lateinit var videoDecoder: VideoDecoder
    
    // State - Sending
    @Volatile
    private var isSending = false
    private val isFirstFrame = AtomicBoolean(true)
    private var lastFrameTime = 0L
    private var framesSent = 0
    private var sendFpsCount = 0
    private var lastSendFpsTime = 0L
    
    // State - Receiving
    @Volatile
    private var isReceiving = false
    
    @Volatile
    private var receiveSurface: Surface? = null
    
    @Volatile
    private var isSurfaceValid = false
    
    private val surfaceLock = Object()
    private var framesReceived = 0
    private var lastReceiveStatsTime = 0L
    
    // Streams
    @Volatile
    private var sendOutputStream: OutputStream? = null
    
    @Volatile
    private var receiveInputStream: InputStream? = null
    
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.applyLocale(newBase))
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bidirectional)
        
        initViews()
        initComponents()
        setupCallbacks()
        setupSurface()
    }
    
    private fun initViews() {
        viewFinder = findViewById(R.id.view_finder)
        receivedVideoView = findViewById(R.id.received_video_view)
        waitingOverlay = findViewById(R.id.waiting_overlay)
        btnConnect = findViewById(R.id.btn_connect)
        btnStop = findViewById(R.id.btn_stop)
        tvStatus = findViewById(R.id.tv_status)
        tvSendFps = findViewById(R.id.tv_send_fps)
        tvReceiveStats = findViewById(R.id.tv_receive_stats)
        statusIndicator = findViewById(R.id.status_indicator)
        logView = findViewById(R.id.log_view)
        
        findViewById<android.widget.ImageButton>(R.id.btn_back).setOnClickListener {
            finish()
        }
        
        btnConnect.setOnClickListener {
            startConnection()
        }
        
        btnStop.setOnClickListener {
            stopAll()
        }
    }
    
    private fun initComponents() {
        bleAdvertiser = BleAdvertiser(this)
        bleAdvertiser.streamDirection = BleAdvertiser.StreamDirectionType.BIDIRECTIONAL
        videoEncoder = VideoEncoder()
        videoDecoder = VideoDecoder()
    }
    
    private fun setupCallbacks() {
        bleAdvertiser.onDeviceConnected = { device ->
            log("眼鏡已連線: ${device.address}")
        }
        
        bleAdvertiser.onDeviceDisconnected = { device ->
            log("眼鏡已斷線: ${device.address}")
            runOnUiThread {
                stopAll()
            }
        }
        
        bleAdvertiser.onL2capClientConnected = { outputStream, inputStream ->
            sendOutputStream = outputStream
            receiveInputStream = inputStream
            
            runOnUiThread {
                updateConnectionStatus(true)
                btnConnect.isEnabled = false
                btnStop.isEnabled = true
                log("L2CAP 連線成功，開始雙向串流")
            }
            
            // Start sending (camera + encoder)
            isSending = true
            runOnUiThread { startCamera() }
            
            // Start receiving (decoder)
            isReceiving = true
            Thread { receiveFrames() }.start()
        }
        
        bleAdvertiser.onL2capClientDisconnected = {
            log("L2CAP 連線中斷")
            runOnUiThread {
                stopAll()
            }
        }
    }
    
    private fun setupSurface() {
        receivedVideoView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                synchronized(surfaceLock) {
                    receiveSurface = holder.surface
                    isSurfaceValid = true
                    log("Receive surface created")
                    surfaceLock.notifyAll()
                }
            }
            
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                synchronized(surfaceLock) {
                    receiveSurface = holder.surface
                    isSurfaceValid = true
                    log("Receive surface changed: ${width}x${height}")
                    surfaceLock.notifyAll()
                }
            }
            
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                synchronized(surfaceLock) {
                    isSurfaceValid = false
                    receiveSurface = null
                    log("Receive surface destroyed")
                }
            }
        })
    }
    
    private fun startConnection() {
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
            log("✓ 廣播啟動成功 (雙向模式)")
        } else {
            log("✗ 廣播啟動失敗")
            btnConnect.isEnabled = true
            tvStatus.text = "廣播失敗"
        }
    }
    
    // ===== SENDING (Camera → Encoder → L2CAP) =====
    
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                
                // Configure preview
                val preview = Preview.Builder()
                    .setTargetResolution(android.util.Size(
                        VideoEncoder.DEFAULT_WIDTH,
                        VideoEncoder.DEFAULT_HEIGHT
                    ))
                    .build()
                preview.setSurfaceProvider(viewFinder.surfaceProvider)
                
                // Configure image analysis
                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(android.util.Size(
                        VideoEncoder.DEFAULT_WIDTH,
                        VideoEncoder.DEFAULT_HEIGHT
                    ))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                
                val executor = Executors.newSingleThreadExecutor()
                imageAnalysis.setAnalyzer(executor) { image ->
                    processFrame(image)
                }
                
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )
                log("Camera started")
            } catch (e: Exception) {
                log("Camera init failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }
    
    private fun processFrame(image: ImageProxy) {
        try {
            if (!isSending) {
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
            
            // Initialize encoder on first frame
            if (isFirstFrame.compareAndSet(true, false)) {
                if (!videoEncoder.start(image.width, image.height)) {
                    log("Encoder start failed")
                    image.close()
                    return
                }
                
                // Start sender thread
                Thread { sendFrames() }.start()
                log("First frame: ${image.width}x${image.height}")
            }
            
            // Encode frame
            videoEncoder.encodeFrame(image)
            
            // Update FPS
            sendFpsCount++
            updateSendFps()
        } finally {
            image.close()
        }
    }
    
    private fun sendFrames() {
        val headerBuffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        log("Sender thread started")
        
        try {
            while (isSending) {
                val frameData = videoEncoder.getEncodedFrame(100) ?: continue
                val stream = sendOutputStream ?: break
                
                try {
                    headerBuffer.clear()
                    headerBuffer.putInt(frameData.size)
                    stream.write(headerBuffer.array())
                    stream.write(frameData)
                    stream.flush()
                    
                    framesSent++
                    
                    if (framesSent % 30 == 0) {
                        Log.d(TAG, "Sent $framesSent frames")
                    }
                } catch (e: Exception) {
                    log("Send error: ${e.message}")
                    break
                }
            }
        } finally {
            log("Sender thread stopped")
        }
    }
    
    private fun updateSendFps() {
        val now = System.currentTimeMillis()
        if (now - lastSendFpsTime >= 1000) {
            val fps = sendFpsCount
            sendFpsCount = 0
            lastSendFpsTime = now
            
            runOnUiThread {
                tvSendFps.text = "$fps FPS"
            }
        }
    }
    
    // ===== RECEIVING (L2CAP → Decoder → Display) =====
    
    private fun receiveFrames() {
        log("Receiver thread started")
        framesReceived = 0
        lastReceiveStatsTime = System.currentTimeMillis()
        
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
                videoDecoder.setSurface(receiveSurface)
            }
        }
        
        val headerBuffer = ByteArray(4)
        val frameBuffer = ByteArray(1_000_000)  // Max frame size
        
        try {
            val stream = receiveInputStream ?: return
            
            while (isReceiving) {
                // Read frame header (4 bytes for length, little-endian to match glasses sender)
                var bytesRead = 0
                while (bytesRead < 4 && isReceiving) {
                    val read = stream.read(headerBuffer, bytesRead, 4 - bytesRead)
                    if (read == -1) {
                        log("Receive connection closed")
                        return
                    }
                    bytesRead += read
                }
                
                if (bytesRead < 4) break
                
                // Parse frame length (little-endian to match glasses sender)
                val frameLength = ByteBuffer.wrap(headerBuffer)
                    .order(ByteOrder.LITTLE_ENDIAN).int
                
                if (frameLength <= 0 || frameLength > frameBuffer.size) {
                    log("Invalid frame length: $frameLength")
                    continue
                }
                
                // Read frame data
                bytesRead = 0
                while (bytesRead < frameLength && isReceiving) {
                    val read = stream.read(frameBuffer, bytesRead, frameLength - bytesRead)
                    if (read == -1) break
                    bytesRead += read
                }
                
                if (bytesRead < frameLength) break
                
                val frameData = frameBuffer.copyOf(frameLength)
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
                
                // Update stats
                updateReceiveStats()
            }
        } catch (e: Exception) {
            log("Receiver error: ${e.message}")
        } finally {
            log("Receiver thread stopped")
        }
    }
    
    private fun updateReceiveStats() {
        val now = System.currentTimeMillis()
        if (now - lastReceiveStatsTime >= 1000) {
            val (_, decoded) = videoDecoder.getStats()
            lastReceiveStatsTime = now
            
            runOnUiThread {
                tvReceiveStats.text = "$framesReceived / $decoded"
            }
        }
    }
    
    // ===== UI & Cleanup =====
    
    @SuppressLint("ResourceType")
    private fun updateConnectionStatus(connected: Boolean) {
        if (connected) {
            statusIndicator.setBackgroundColor(getColor(android.R.color.holo_green_dark))
            tvStatus.text = "已連線 (雙向)"
        } else {
            statusIndicator.setBackgroundColor(getColor(android.R.color.holo_red_dark))
            tvStatus.text = "未連線"
        }
    }
    
    private fun stopAll() {
        // Stop sending
        isSending = false
        isFirstFrame.set(true)
        framesSent = 0
        sendFpsCount = 0
        videoEncoder.stop()
        
        // Stop receiving
        isReceiving = false
        framesReceived = 0
        videoDecoder.stop()
        
        // Disconnect and stop advertising
        bleAdvertiser.stopAdvertising()
        sendOutputStream = null
        receiveInputStream = null
        
        // Unbind camera
        try {
            val cameraProvider = ProcessCameraProvider.getInstance(this).get()
            cameraProvider.unbindAll()
        } catch (e: Exception) {
            Log.e(TAG, "Error unbinding camera: ${e.message}")
        }
        
        runOnUiThread {
            updateConnectionStatus(false)
            btnConnect.isEnabled = true
            btnStop.isEnabled = false
            waitingOverlay.visibility = View.VISIBLE
            tvSendFps.text = "0 FPS"
            tvReceiveStats.text = "0 / 0"
        }
        
        log("All stopped")
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
        stopAll()
    }
}
