package com.rokid.stream.sender.streaming

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.rokid.stream.sender.R
import com.rokid.stream.sender.ble.BleAdvertiser
import com.rokid.stream.sender.core.VideoEncoder
import com.rokid.stream.sender.util.LocaleManager
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PhoneToGlassesActivity - Send phone camera video to glasses
 * 
 * This activity handles:
 * - Camera capture and preview
 * - H.264 video encoding
 * - BLE advertising for glasses discovery
 * - Video frame transmission via L2CAP
 */
class PhoneToGlassesActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "PhoneToGlasses"
        const val FRAME_INTERVAL_MS = 100L  // ~10 FPS - optimal for BLE L2CAP
    }
    
    // UI components
    private lateinit var viewFinder: PreviewView
    private lateinit var btnConnect: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvFps: TextView
    private lateinit var statusIndicator: View
    private lateinit var logView: TextView
    
    // Core components
    private lateinit var bleAdvertiser: BleAdvertiser
    private lateinit var videoEncoder: VideoEncoder
    
    // State
    @Volatile
    private var isStreaming = false
    private val isFirstFrame = AtomicBoolean(true)
    private var lastFrameTime = 0L
    private var framesSent = 0
    private var lastFpsTime = 0L
    private var fpsFrameCount = 0
    
    // Output stream for sending frames (buffered for better L2CAP throughput)
    @Volatile
    private var outputStream: OutputStream? = null
    @Volatile
    private var bufferedOutputStream: BufferedOutputStream? = null
    
    // L2CAP buffer size - larger buffer for better throughput
    private val L2CAP_BUFFER_SIZE = 64 * 1024  // 64KB buffer
    
    // Write queue for non-blocking transmission (max 3 frames to prevent memory buildup)
    private val writeQueue = LinkedBlockingQueue<ByteArray>(3)
    @Volatile
    private var socketWriterRunning = false
    
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.applyLocale(newBase))
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_phone_to_glasses)
        
        initViews()
        initComponents()
        setupCallbacks()
    }
    
    private fun initViews() {
        viewFinder = findViewById(R.id.view_finder)
        btnConnect = findViewById(R.id.btn_connect)
        btnStop = findViewById(R.id.btn_stop)
        tvStatus = findViewById(R.id.tv_status)
        tvFps = findViewById(R.id.tv_fps)
        statusIndicator = findViewById(R.id.status_indicator)
        logView = findViewById(R.id.log_view)
        
        findViewById<android.widget.ImageButton>(R.id.btn_back).setOnClickListener {
            finish()
        }
        
        btnConnect.setOnClickListener {
            startAdvertising()
        }
        
        btnStop.setOnClickListener {
            stopStreaming()
        }
    }
    
    private fun initComponents() {
        bleAdvertiser = BleAdvertiser(this)
        bleAdvertiser.streamDirection = BleAdvertiser.StreamDirectionType.PHONE_TO_GLASSES
        videoEncoder = VideoEncoder()
    }
    
    private fun setupCallbacks() {
        bleAdvertiser.onDeviceConnected = { device ->
            log("眼鏡已連線: ${device.address}")
        }
        
        bleAdvertiser.onDeviceDisconnected = { device ->
            log("眼鏡已斷線: ${device.address}")
            runOnUiThread {
                stopStreaming()
            }
        }
        
        bleAdvertiser.onL2capClientConnected = { outStream, _ ->
            outputStream = outStream
            // Wrap in BufferedOutputStream for better L2CAP throughput
            bufferedOutputStream = BufferedOutputStream(outStream, L2CAP_BUFFER_SIZE)
            isStreaming = true
            socketWriterRunning = true
            
            // Start socket writer thread (handles blocking writes)
            Thread { socketWriter() }.start()
            
            runOnUiThread {
                updateConnectionStatus(true)
                btnConnect.isEnabled = false
                btnStop.isEnabled = true
                log("L2CAP 連線成功，開始串流")
                startCamera()
            }
        }
        
        bleAdvertiser.onL2capClientDisconnected = {
            log("L2CAP 連線中斷")
            runOnUiThread {
                stopStreaming()
            }
        }
    }
    
    private fun startAdvertising() {
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
            log("✓ 廣播啟動成功")
        } else {
            log("✗ 廣播啟動失敗")
            btnConnect.isEnabled = true
            tvStatus.text = "廣播失敗"
        }
    }
    
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
                
                // Configure image analysis for frame capture
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
                
                // Bind camera use cases
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
            if (!isStreaming) {
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
                // Use fixed encoder resolution regardless of camera resolution
                // This ensures consistent low bitrate output for BLE L2CAP
                if (!videoEncoder.start(VideoEncoder.DEFAULT_WIDTH, VideoEncoder.DEFAULT_HEIGHT)) {
                    log("Encoder start failed")
                    image.close()
                    return
                }
                
                // Start sender thread
                Thread { sendFrames() }.start()
                log("First frame: ${image.width}x${image.height} -> encode at ${VideoEncoder.DEFAULT_WIDTH}x${VideoEncoder.DEFAULT_HEIGHT}")
            }
            
            // Encode the frame
            videoEncoder.encodeFrame(image)
            
            // Update FPS display
            updateFps()
        } finally {
            image.close()
        }
    }
    
    private fun sendFrames() {
        val headerBuffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        log("Sender thread started")
        
        var framesQueued = 0
        var framesDropped = 0
        var lastLogTime = System.currentTimeMillis()
        
        try {
            while (isStreaming) {
                val frameData = videoEncoder.getEncodedFrame(100) ?: continue
                
                // Build packet: [4-byte length header] + [frame data]
                headerBuffer.clear()
                headerBuffer.putInt(frameData.size)
                val packet = ByteArray(4 + frameData.size)
                System.arraycopy(headerBuffer.array(), 0, packet, 0, 4)
                System.arraycopy(frameData, 0, packet, 4, frameData.size)
                
                // Try to add to queue without blocking
                // If queue is full, drop this frame (old frames are already in queue)
                if (writeQueue.offer(packet)) {
                    framesQueued++
                    framesSent++
                    fpsFrameCount++
                } else {
                    framesDropped++
                    if (framesDropped % 10 == 0) {
                        Log.d(TAG, "Queue full, dropped $framesDropped frames")
                    }
                }
                
                // Log every second
                val now = System.currentTimeMillis()
                if (now - lastLogTime >= 1000) {
                    Log.d(TAG, "Queued: $framesQueued, Dropped: $framesDropped, Queue size: ${writeQueue.size}")
                    lastLogTime = now
                }
            }
        } finally {
            log("Sender thread stopped. Queued: $framesQueued, Dropped: $framesDropped")
        }
    }
    
    // Dedicated thread for socket writes (blocking is OK here)
    private fun socketWriter() {
        Log.d(TAG, "Socket writer thread started")
        
        var totalBytesSent = 0L
        var lastLogTime = System.currentTimeMillis()
        var framesSentOverSocket = 0
        
        try {
            while (socketWriterRunning && isStreaming) {
                // Wait for next packet (with timeout to check running flag)
                val packet = writeQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                val stream = bufferedOutputStream ?: break
                
                try {
                    stream.write(packet)
                    stream.flush()  // Flush each packet for low latency
                    
                    framesSentOverSocket++
                    totalBytesSent += packet.size
                    
                    // Log every second
                    val now = System.currentTimeMillis()
                    if (now - lastLogTime >= 1000) {
                        val kbps = (totalBytesSent * 8 / 1000).toInt()
                        Log.d(TAG, "Socket: sent $framesSentOverSocket frames, $kbps Kbps")
                        lastLogTime = now
                        totalBytesSent = 0
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Socket write error: ${e.message}")
                    break
                }
            }
        } finally {
            try { bufferedOutputStream?.flush() } catch (e: Exception) {}
            Log.d(TAG, "Socket writer thread stopped. Total sent: $framesSentOverSocket")
        }
    }
    
    private fun updateFps() {
        val now = System.currentTimeMillis()
        if (now - lastFpsTime >= 1000) {
            val fps = fpsFrameCount
            fpsFrameCount = 0
            lastFpsTime = now
            
            runOnUiThread {
                tvFps.text = "$fps FPS"
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
    
    private fun stopStreaming() {
        isStreaming = false
        socketWriterRunning = false
        isFirstFrame.set(true)
        framesSent = 0
        writeQueue.clear()
        
        videoEncoder.stop()
        bleAdvertiser.stopAdvertising()
        bufferedOutputStream = null
        outputStream = null
        
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
            tvFps.text = "0 FPS"
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
        stopStreaming()
    }
}
