package com.rokid.stream.receiver.core

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * VideoEncoder - H.265 (HEVC) / H.264 (AVC) video encoder for Glasses→Phone streaming mode
 * 
 * Encapsulates MediaCodec encoder with low-latency optimizations.
 * Adapted from phone-app VideoEncoder for use on Rokid glasses.
 * 
 * Features:
 * - Dynamic codec selection: H.265 primary, H.264 fallback
 * - HEVC provides ~30-40% better compression at same quality
 * - Low latency configuration for real-time streaming
 * - NV12 format conversion from CameraX YUV_420_888
 * - Thread-safe operation with proper synchronization
 * - Automatic SPS/PPS (H.264) or VPS/SPS/PPS (H.265) handling for decoder initialization
 */
class VideoEncoder(
    private val width: Int = DEFAULT_WIDTH,
    private val height: Int = DEFAULT_HEIGHT,
    private val bitrate: Int = DEFAULT_BITRATE,
    private val frameRate: Int = DEFAULT_FRAME_RATE
) {
    companion object {
        private const val TAG = "VideoEncoder"
        
        // MIME types
        const val MIME_TYPE_HEVC = MediaFormat.MIMETYPE_VIDEO_HEVC  // "video/hevc"
        const val MIME_TYPE_AVC = MediaFormat.MIMETYPE_VIDEO_AVC   // "video/avc"
        
        // Default video encoding parameters - OPTIMIZED FOR BLE L2CAP STREAMING
        // Based on: https://medium.com/@20x05zero/real-time-video-streaming-to-ar-glasses
        const val DEFAULT_WIDTH = 720
        const val DEFAULT_HEIGHT = 720
        const val DEFAULT_FRAME_RATE = 10    // 10 FPS - stable streaming
        const val I_FRAME_INTERVAL = 2       // Keyframe every 2 seconds (reduces keyframe overhead)
        const val ENCODER_TIMEOUT_US = 1000L // 1ms encoder timeout
        const val MAX_PENDING_FRAMES = 10    // Reduced buffer - BLE can't keep up with large queue
        const val MAX_FRAME_SIZE = 1_000_000 // 1MB for validation
        
        // === BLE L2CAP BANDWIDTH OPTIMIZATIONS ===
        // BLE L2CAP typical MTU: ~8KB, effective throughput: ~200-300 Kbps
        // With 10fps @ 240x240, target average frame size: ~2-3KB
        const val DEFAULT_BITRATE = 150_000  // 150 Kbps for H.264 - BLE optimized
        
        // HEVC bitrate reduction factor (40% lower than H.264 for same quality)
        // HEVC is more efficient, allowing further bitrate reduction
        const val HEVC_BITRATE_FACTOR = 0.6f // 150K * 0.6 = 90 Kbps for HEVC
        
        /**
         * Check if device supports hardware H.265 (HEVC) encoding
         * 
         * This checks for hardware encoders only (not software)
         */
        @JvmStatic
        fun isH265Supported(): Boolean {
            return try {
                val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
                for (codecInfo in codecList.codecInfos) {
                    if (!codecInfo.isEncoder) continue
                    
                    for (type in codecInfo.supportedTypes) {
                        if (type.equals(MIME_TYPE_HEVC, ignoreCase = true)) {
                            // Check if this is a hardware encoder (not software)
                            val isHardware = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                codecInfo.isHardwareAccelerated
                            } else {
                                // Heuristic: software codecs usually contain "OMX.google" or "c2.android"
                                val name = codecInfo.name.lowercase()
                                !name.contains("google") && !name.contains("c2.android")
                            }
                            
                            if (isHardware) {
                                Log.d(TAG, "✅ Hardware HEVC encoder found: ${codecInfo.name}")
                                return true
                            }
                        }
                    }
                }
                Log.d(TAG, "❌ No hardware HEVC encoder found, will use H.264")
                false
            } catch (e: Exception) {
                Log.e(TAG, "Error checking HEVC support: ${e.message}")
                false
            }
        }
    }
    
    // MediaCodec encoder instance
    @Volatile
    private var encoder: MediaCodec? = null
    private val encoderLock = Object()
    
    // Active codec type
    @Volatile
    var activeCodec: String = MIME_TYPE_AVC
        private set
    
    // SPS/PPS (H.264) or VPS/SPS/PPS (H.265) data for decoder initialization
    @Volatile
    var spsPpsData: ByteArray? = null
        private set
    
    // Queue for pending encoded frames
    private val pendingFrames = LinkedBlockingQueue<ByteArray>(MAX_PENDING_FRAMES)
    
    // State flags
    @Volatile
    var isRunning = false
        private set
    
    @Volatile
    private var isDraining = false
    
    // Statistics
    private var framesEncoded = 0
    private var droppedFrames = 0
    
    // === BACKPRESSURE CONTROL ===
    // When queue is full, skip input frames to reduce encoder pressure
    @Volatile
    private var skipNextInputFrames = 0
    
    // Callback for encoded frames
    var onFrameEncoded: ((ByteArray, Boolean) -> Unit)? = null
    
    /**
     * Check if queue has room for more frames
     * Call this before capturing/encoding new frames
     */
    fun canAcceptFrame(): Boolean {
        return pendingFrames.size < MAX_PENDING_FRAMES - 2
    }
    
    /**
     * Get current queue utilization (0.0 to 1.0)
     */
    fun getQueueUtilization(): Float {
        return pendingFrames.size.toFloat() / MAX_PENDING_FRAMES
    }
    
    /**
     * Initialize and start the encoder
     * Attempts H.265 first, falls back to H.264 if unsupported or fails
     * 
     * @param actualWidth Actual camera frame width
     * @param actualHeight Actual camera frame height
     * @return true if successful
     */
    fun start(actualWidth: Int = width, actualHeight: Int = height): Boolean {
        synchronized(encoderLock) {
            if (encoder != null) {
                Log.w(TAG, "Encoder already started")
                return true
            }
            
            // Try H.265 first if supported
            if (isH265Supported()) {
                if (tryStartEncoder(MIME_TYPE_HEVC, actualWidth, actualHeight)) {
                    return true
                }
                Log.w(TAG, "⚠️ H.265 initialization failed, falling back to H.264")
            }
            
            // Fallback to H.264
            return tryStartEncoder(MIME_TYPE_AVC, actualWidth, actualHeight)
        }
    }
    
    /**
     * Try to start encoder with specified MIME type
     * @return true if successful, false otherwise
     */
    private fun tryStartEncoder(mimeType: String, actualWidth: Int, actualHeight: Int): Boolean {
        try {
            val isHevc = mimeType == MIME_TYPE_HEVC
            val codecName = if (isHevc) "H.265 (HEVC)" else "H.264 (AVC)"
            Log.d(TAG, "Initializing $codecName encoder: ${actualWidth}x${actualHeight}")
            
            val mediaEncoder = MediaCodec.createEncoderByType(mimeType)
            val format = MediaFormat.createVideoFormat(mimeType, actualWidth, actualHeight)
            
            // Configure encoder format
            format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
            )
            
            // === BITRATE CONFIGURATION ===
            // HEVC is ~30-40% more efficient, so reduce bitrate to save bandwidth
            val effectiveBitrate = if (isHevc) {
                (bitrate * HEVC_BITRATE_FACTOR).toInt()
            } else {
                bitrate
            }
            format.setInteger(MediaFormat.KEY_BIT_RATE, effectiveBitrate)
            Log.d(TAG, "📊 Bitrate: $effectiveBitrate bps ($codecName)")
            
            format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
            
            // === CODEC PROFILE CONFIGURATION ===
            if (isHevc) {
                // HEVC Main Profile - widely supported, good compression
                format.setInteger(
                    MediaFormat.KEY_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
                )
                format.setInteger(
                    MediaFormat.KEY_LEVEL,
                    MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel31
                )
            } else {
                // AVC Baseline Profile - maximum compatibility
                format.setInteger(
                    MediaFormat.KEY_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
                )
                format.setInteger(
                    MediaFormat.KEY_LEVEL,
                    MediaCodecInfo.CodecProfileLevel.AVCLevel31
                )
            }
            
            // === LOW LATENCY OPTIMIZATIONS ===
            
            // Enable low latency mode if supported (API 30+)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                Log.d(TAG, "🚀 Low latency mode enabled (API 30+)")
            }
            
            // Disable B-frames for zero reordering delay
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
            
            // Set real-time priority
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                format.setInteger(MediaFormat.KEY_PRIORITY, 0)  // 0 = realtime
            }
            
            // Set CBR (Constant Bit Rate) mode for consistent streaming
            format.setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
            )
            
            // Reduce complexity for faster encoding
            format.setInteger(MediaFormat.KEY_COMPLEXITY, 0)  // Minimum complexity
            
            // Prepend codec config to IDR frames for decoder recovery
            try {
                format.setInteger("prepend-sps-pps-to-idr-frames", 1)
            } catch (e: Exception) {
                // Ignore if not supported
            }
            
            mediaEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            mediaEncoder.start()
            
            encoder = mediaEncoder
            activeCodec = mimeType
            isRunning = true
            framesEncoded = 0
            droppedFrames = 0
            
            // Start encoder output drain thread
            Thread { drainEncoderOutput() }.start()
            
            Log.d(TAG, "✅ $codecName encoder initialized successfully")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Encoder initialization failed ($mimeType): ${e.message}")
            // Clean up on failure
            try {
                encoder?.release()
            } catch (ex: Exception) {
                // Ignore
            }
            encoder = null
            return false
        }
    }
    
    /**
     * Check if currently using H.265 (HEVC) codec
     */
    fun isUsingHevc(): Boolean = activeCodec == MIME_TYPE_HEVC
    
    /**
     * Encode a camera frame from CameraX ImageProxy
     * Implements backpressure control to prevent queue overflow
     */
    fun encodeFrame(image: ImageProxy) {
        val safeEncoder = encoder ?: return
        if (!isRunning) return
        
        // === BACKPRESSURE CONTROL ===
        // Skip frames if queue is filling up to prevent encoder from producing faster than we can send
        if (!canAcceptFrame()) {
            skipNextInputFrames++
            if (skipNextInputFrames % 5 == 1) {
                Log.d(TAG, "⏭️ Skipping frame (queue full: ${pendingFrames.size}/$MAX_PENDING_FRAMES)")
            }
            return
        }
        skipNextInputFrames = 0
        
        try {
            val inputIndex = safeEncoder.dequeueInputBuffer(ENCODER_TIMEOUT_US)
            if (inputIndex >= 0) {
                val inputBuffer = safeEncoder.getInputBuffer(inputIndex) ?: return
                inputBuffer.clear()
                
                // Convert YUV_420_888 to NV12 and fill buffer
                val dataSize = fillNV12Buffer(image, inputBuffer)
                
                // Queue the input buffer for encoding
                val presentationTimeUs = System.nanoTime() / 1000
                safeEncoder.queueInputBuffer(inputIndex, 0, dataSize, presentationTimeUs, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Frame encoding error: ${e.message}")
        }
    }
    
    /**
     * Queue a raw NV21/YUV frame from Camera2 API for encoding
     * Implements backpressure control to prevent queue overflow
     * @param frameData Raw frame data in NV21 format
     */
    fun queueFrame(frameData: ByteArray) {
        val safeEncoder = encoder ?: return
        if (!isRunning) return
        
        // === BACKPRESSURE CONTROL ===
        // Skip frames if queue is filling up to prevent encoder from producing faster than we can send
        if (!canAcceptFrame()) {
            skipNextInputFrames++
            if (skipNextInputFrames % 5 == 1) {
                Log.d(TAG, "⏭️ Skipping frame (queue full: ${pendingFrames.size}/$MAX_PENDING_FRAMES)")
            }
            return
        }
        skipNextInputFrames = 0
        
        try {
            val inputIndex = safeEncoder.dequeueInputBuffer(ENCODER_TIMEOUT_US)
            if (inputIndex >= 0) {
                val inputBuffer = safeEncoder.getInputBuffer(inputIndex) ?: return
                inputBuffer.clear()
                
                // NV21 to NV12 conversion (swap U and V planes)
                // NV21: Y plane followed by VU interleaved
                // NV12: Y plane followed by UV interleaved
                val ySize = width * height
                val uvSize = ySize / 2
                
                if (frameData.size >= ySize + uvSize) {
                    // Copy Y plane as-is
                    inputBuffer.put(frameData, 0, ySize)
                    
                    // Swap VU to UV for NV12
                    for (i in 0 until uvSize / 2) {
                        val v = frameData[ySize + i * 2]
                        val u = frameData[ySize + i * 2 + 1]
                        inputBuffer.put(u)
                        inputBuffer.put(v)
                    }
                } else {
                    // Fallback: just copy what we have
                    inputBuffer.put(frameData, 0, minOf(frameData.size, inputBuffer.remaining()))
                }
                
                // Queue the input buffer for encoding
                val presentationTimeUs = System.nanoTime() / 1000
                safeEncoder.queueInputBuffer(inputIndex, 0, inputBuffer.position(), presentationTimeUs, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Frame queue error: ${e.message}")
        }
    }
    
    /**
     * Get next encoded frame from queue
     * @param timeoutMs Timeout in milliseconds
     * @return Encoded frame data or null if timeout
     */
    fun getEncodedFrame(timeoutMs: Long = 100): ByteArray? {
        return pendingFrames.poll(timeoutMs, TimeUnit.MILLISECONDS)
    }
    
    /**
     * Stop and release the encoder
     */
    fun stop() {
        isRunning = false
        isDraining = false
        
        synchronized(encoderLock) {
            try {
                encoder?.stop()
                encoder?.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping encoder: ${e.message}")
            }
            encoder = null
        }
        
        pendingFrames.clear()
        spsPpsData = null
        
        Log.d(TAG, "Encoder stopped. Encoded: $framesEncoded, Dropped: $droppedFrames")
    }
    
    /**
     * Drains encoded output from encoder and queues frames for sending
     */
    private fun drainEncoderOutput() {
        val bufferInfo = MediaCodec.BufferInfo()
        Log.d(TAG, "Starting encoder output drain thread")
        isDraining = true
        
        try {
            while (isRunning && isDraining) {
                val safeEncoder = encoder ?: break
                
                val outputIndex = safeEncoder.dequeueOutputBuffer(bufferInfo, 10000)
                
                when {
                    outputIndex >= 0 -> {
                        val outputBuffer = safeEncoder.getOutputBuffer(outputIndex)
                        if (outputBuffer != null) {
                            // Check for codec config data (VPS/SPS/PPS for H.265, SPS/PPS for H.264)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                val configData = ByteArray(bufferInfo.size)
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.get(configData)
                                spsPpsData = configData
                                Log.d(TAG, "📦 Received codec config: ${bufferInfo.size} bytes (${activeCodec})")
                                
                                // CRITICAL: Always send codec config, never drop it
                                // Clear queue if necessary to make room for codec config
                                while (!pendingFrames.offer(configData)) {
                                    pendingFrames.poll()
                                }
                                onFrameEncoded?.invoke(configData, true)
                            } else if (bufferInfo.size > 0) {
                                // Regular encoded frame
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                
                                val frameData = ByteArray(bufferInfo.size)
                                outputBuffer.get(frameData)
                                
                                // Check if this is a keyframe
                                val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                                
                                if (isKeyFrame) {
                                    // IMPORTANT: Prepend codec config before keyframe for decoder recovery
                                    // This ensures decoder can reinitialize if it missed previous config
                                    spsPpsData?.let { config ->
                                        // Combine codec config + keyframe into single packet
                                        val combinedData = ByteArray(config.size + frameData.size)
                                        System.arraycopy(config, 0, combinedData, 0, config.size)
                                        System.arraycopy(frameData, 0, combinedData, config.size, frameData.size)
                                        
                                        // Queue the combined packet (config + keyframe)
                                        if (!pendingFrames.offer(combinedData)) {
                                            // Make room for critical keyframe
                                            pendingFrames.poll()
                                            pendingFrames.offer(combinedData)
                                            droppedFrames++
                                        }
                                        framesEncoded++
                                        onFrameEncoded?.invoke(combinedData, true)
                                    } ?: run {
                                        // No config data, just send keyframe
                                        if (!pendingFrames.offer(frameData)) {
                                            pendingFrames.poll()
                                            pendingFrames.offer(frameData)
                                            droppedFrames++
                                        }
                                        framesEncoded++
                                        onFrameEncoded?.invoke(frameData, true)
                                    }
                                } else {
                                    // Non-keyframe (P-frame or B-frame)
                                    if (!pendingFrames.offer(frameData)) {
                                        pendingFrames.poll()
                                        pendingFrames.offer(frameData)
                                        droppedFrames++
                                        if (droppedFrames % 10 == 0) {
                                            Log.d(TAG, "Dropped $droppedFrames frames due to slow output")
                                        }
                                    }
                                    framesEncoded++
                                    onFrameEncoded?.invoke(frameData, false)
                                }
                            }
                        }
                        
                        safeEncoder.releaseOutputBuffer(outputIndex, false)
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        Log.d(TAG, "Encoder output format changed: ${safeEncoder.outputFormat}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Encoder drain error: ${e.message}")
        } finally {
            Log.d(TAG, "Encoder drain thread stopped")
        }
    }
    
    /**
     * Converts YUV_420_888 format to NV12 (YUV420 semi-planar) format
     * This is required because MediaCodec expects NV12 but CameraX provides YUV_420_888
     */
    private fun fillNV12Buffer(image: ImageProxy, buffer: ByteBuffer): Int {
        val imageWidth = image.width
        val imageHeight = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        var position = 0

        // Copy Y plane row by row
        if (yRowStride == imageWidth) {
            val ySize = imageWidth * imageHeight
            yBuffer.position(0)
            val yBytes = ByteArray(minOf(ySize, yBuffer.remaining()))
            yBuffer.get(yBytes)
            buffer.put(yBytes)
            position += yBytes.size
        } else {
            val rowBytes = ByteArray(imageWidth)
            for (row in 0 until imageHeight) {
                yBuffer.position(row * yRowStride)
                yBuffer.get(rowBytes, 0, minOf(imageWidth, yBuffer.remaining()))
                buffer.put(rowBytes)
                position += imageWidth
            }
        }

        // Copy UV plane (interleaved as NV12)
        val uvHeight = imageHeight / 2
        val uvWidth = imageWidth / 2

        if (uvPixelStride == 2 && uvRowStride == imageWidth) {
            uBuffer.position(0)
            val uvSize = imageWidth * uvHeight
            val uvBytes = ByteArray(minOf(uvSize, uBuffer.remaining()))
            uBuffer.get(uvBytes)
            buffer.put(uvBytes)
            position += uvBytes.size
        } else {
            for (row in 0 until uvHeight) {
                for (col in 0 until uvWidth) {
                    val uvIndex = row * uvRowStride + col * uvPixelStride
                    if (uvIndex < uBuffer.limit()) {
                        buffer.put(uBuffer.get(uvIndex))
                        position++
                    }
                    if (uvIndex < vBuffer.limit()) {
                        buffer.put(vBuffer.get(uvIndex))
                        position++
                    }
                }
            }
        }

        return position
    }
    
    /**
     * Check if frame is a keyframe by examining NAL unit type
     * Supports both H.264 (AVC) and H.265 (HEVC) NAL types
     */
    fun isKeyFrame(frameData: ByteArray): Boolean {
        if (frameData.size < 5) return false
        
        for (i in 0 until minOf(frameData.size - 4, 100)) {
            if (frameData[i] == 0x00.toByte() && frameData[i+1] == 0x00.toByte()) {
                val startCodeLen = if (frameData[i+2] == 0x01.toByte()) 3
                else if (frameData[i+2] == 0x00.toByte() && frameData[i+3] == 0x01.toByte()) 4
                else continue
                
                val nalByte = frameData[i + startCodeLen].toInt()
                
                return if (activeCodec == MIME_TYPE_HEVC) {
                    // H.265/HEVC NAL unit type is in bits 1-6 of first byte
                    // NAL types: 16-21 = IRAP/IDR, 32 = VPS, 33 = SPS, 34 = PPS
                    val nalType = (nalByte shr 1) and 0x3F
                    nalType in 16..21 || nalType == 32 || nalType == 33 || nalType == 34
                } else {
                    // H.264/AVC NAL unit type is in bits 0-4 of first byte
                    // NAL types: 5 = IDR slice, 7 = SPS
                    val nalType = nalByte and 0x1F
                    nalType == 5 || nalType == 7
                }
            }
        }
        return false
    }
}
