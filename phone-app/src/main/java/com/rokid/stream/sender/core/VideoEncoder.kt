package com.rokid.stream.sender.core

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
 * VideoEncoder - H.265 (HEVC) / H.264 (AVC) video encoder for all streaming modes
 * 
 * Encapsulates MediaCodec encoder with low-latency optimizations.
 * Designed to be reused across streaming Activities.
 * 
 * Features:
 * - Dynamic codec selection: H.265 primary, H.264 fallback
 * - HEVC provides ~30-40% better compression at same quality
 * - Low latency configuration for real-time streaming
 * - MediaTek-specific optimizations to avoid high-latency buffering
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
        
        // Default video encoding parameters - OPTIMIZED for BLE L2CAP STREAMING
        // BLE L2CAP with 2M PHY can handle ~80-150 Kbps sustained
        // Rokid glasses only display monochrome (green), so grayscale is sufficient
        const val DEFAULT_WIDTH = 240        // Low resolution for L2CAP bandwidth
        const val DEFAULT_HEIGHT = 240       // Low resolution for L2CAP bandwidth  
        const val DEFAULT_BITRATE = 100_000  // 100 Kbps - more headroom for stable streaming
        const val DEFAULT_FRAME_RATE = 10    // 10 FPS - smooth updates, ~100ms interval
        const val I_FRAME_INTERVAL = 3       // Keyframe every 3 seconds (reduce overhead)
        const val ENCODER_TIMEOUT_US = 1000L // 1ms encoder timeout
        const val MAX_PENDING_FRAMES = 30    // Increased buffer for BLE L2CAP throughput
        const val MAX_FRAME_SIZE = 1_000_000 // 1MB for validation
        
        // HEVC bitrate reduction factor (30% lower than H.264 for same quality)
        const val HEVC_BITRATE_FACTOR = 0.7f
        
        /**
         * Check if device supports hardware H.265 (HEVC) encoding
         * 
         * This checks for hardware encoders only (not software)
         * MediaTek Dimensity 920 should support hardware HEVC encoding
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
    
    // Callback for encoded frames
    var onFrameEncoded: ((ByteArray, Boolean) -> Unit)? = null
    
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
            // Same visual quality at lower bitrate
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
            // Critical for real-time streaming to AR glasses
            
            // Enable low latency mode if supported (API 30+)
            // This is the most important flag for reducing encoding delay
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                Log.d(TAG, "🚀 Low latency mode enabled (API 30+)")
            }
            
            // Disable B-frames for zero reordering delay
            // B-frames require future frames, adding significant latency
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
            
            // Set real-time priority (0 = realtime, 1 = non-realtime)
            // Tells encoder to prioritize speed over power efficiency
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                format.setInteger(MediaFormat.KEY_PRIORITY, 0)
            }
            
            // Set CBR (Constant Bit Rate) mode for consistent streaming
            // VBR can cause bitrate spikes that overwhelm BLE L2CAP
            format.setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
            )
            
            // Reduce complexity for faster encoding
            // 0 = minimum complexity = fastest encoding
            format.setInteger(MediaFormat.KEY_COMPLEXITY, 0)
            
            // === MEDIATEK-SPECIFIC OPTIMIZATIONS ===
            // MediaTek chips (Dimensity series) may buffer frames for analysis
            // These vendor-specific keys help reduce that buffering
            try {
                // Try to set prepend SPS/PPS for each IDR frame
                // This helps decoder recover faster from packet loss
                format.setInteger("prepend-sps-pps-to-idr-frames", 1)
            } catch (e: Exception) {
                // Ignore if not supported
            }
            
            // Configure and start encoder
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
     * Encode a camera frame
     */
    fun encodeFrame(image: ImageProxy) {
        val safeEncoder = encoder ?: return
        if (!isRunning) return
        
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
                                Log.d(TAG, "📦 Received codec config: ${bufferInfo.size} bytes ($activeCodec)")
                                
                                // CRITICAL: Always send codec config, never drop it
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
                                    // Prepend codec config before keyframe for decoder recovery
                                    spsPpsData?.let { config ->
                                        val combinedData = ByteArray(config.size + frameData.size)
                                        System.arraycopy(config, 0, combinedData, 0, config.size)
                                        System.arraycopy(frameData, 0, combinedData, config.size, frameData.size)
                                        
                                        if (!pendingFrames.offer(combinedData)) {
                                            pendingFrames.poll()
                                            pendingFrames.offer(combinedData)
                                            droppedFrames++
                                        }
                                        framesEncoded++
                                        onFrameEncoded?.invoke(combinedData, true)
                                    } ?: run {
                                        if (!pendingFrames.offer(frameData)) {
                                            pendingFrames.poll()
                                            pendingFrames.offer(frameData)
                                            droppedFrames++
                                        }
                                        framesEncoded++
                                        onFrameEncoded?.invoke(frameData, true)
                                    }
                                } else {
                                    // Non-keyframe
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
     * with downscaling to target encoder dimensions
     */
    private fun fillNV12Buffer(image: ImageProxy, buffer: ByteBuffer): Int {
        val srcWidth = image.width
        val srcHeight = image.height
        val dstWidth = width   // Encoder target width (e.g., 240)
        val dstHeight = height // Encoder target height (e.g., 240)
        
        val yPlane = image.planes[0]
        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride

        var position = 0
        
        // Scale factor for downsampling
        val scaleX = srcWidth.toFloat() / dstWidth
        val scaleY = srcHeight.toFloat() / dstHeight

        // Copy Y plane with nearest-neighbor downscaling
        for (dstRow in 0 until dstHeight) {
            val srcRow = (dstRow * scaleY).toInt().coerceIn(0, srcHeight - 1)
            for (dstCol in 0 until dstWidth) {
                val srcCol = (dstCol * scaleX).toInt().coerceIn(0, srcWidth - 1)
                val srcIndex = srcRow * yRowStride + srcCol
                if (srcIndex < yBuffer.limit()) {
                    buffer.put(yBuffer.get(srcIndex))
                } else {
                    buffer.put(0)
                }
                position++
            }
        }

        // UV plane - GRAYSCALE MODE: Set UV to neutral (128) for monochrome output
        // This significantly improves compression for Rokid glasses which only display green/monochrome
        val uvSize = dstWidth * (dstHeight / 2)
        val neutralUV = ByteArray(uvSize) { 128.toByte() }  // 128 = neutral gray
        buffer.put(neutralUV)
        position += uvSize

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
                    // NAL types: 19/20 = IDR, 32 = VPS, 33 = SPS, 34 = PPS
                    val nalType = (nalByte shr 1) and 0x3F
                    nalType in 16..21 || nalType == 32 || nalType == 33 || nalType == 34  // IRAP/IDR or VPS/SPS/PPS
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
    
    /**
     * Check if data is codec config (SPS/PPS for H.264, VPS/SPS/PPS for H.265)
     */
    fun isCodecConfig(frameData: ByteArray): Boolean {
        if (frameData.size < 5) return false
        
        for (i in 0 until minOf(frameData.size - 4, 50)) {
            if (frameData[i] == 0x00.toByte() && frameData[i+1] == 0x00.toByte()) {
                val startCodeLen = if (frameData[i+2] == 0x01.toByte()) 3
                else if (frameData[i+2] == 0x00.toByte() && frameData[i+3] == 0x01.toByte()) 4
                else continue
                
                val nalByte = frameData[i + startCodeLen].toInt()
                
                return if (activeCodec == MIME_TYPE_HEVC) {
                    // H.265: VPS=32, SPS=33, PPS=34
                    val nalType = (nalByte shr 1) and 0x3F
                    nalType in 32..34
                } else {
                    // H.264: SPS=7, PPS=8
                    val nalType = nalByte and 0x1F
                    nalType == 7 || nalType == 8
                }
            }
        }
        return false
    }
}
