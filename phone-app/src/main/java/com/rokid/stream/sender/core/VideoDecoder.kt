package com.rokid.stream.sender.core

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * VideoDecoder - H.265 (HEVC) / H.264 (AVC) video decoder for all streaming modes
 * 
 * Encapsulates MediaCodec decoder with low-latency optimizations.
 * Designed to be reused across streaming Activities.
 * 
 * Features:
 * - Auto-detects codec type from stream (VPS=H.265, SPS=H.264)
 * - Low latency configuration for real-time streaming
 */
class VideoDecoder(
    private val width: Int = DEFAULT_WIDTH,
    private val height: Int = DEFAULT_HEIGHT
) {
    companion object {
        private const val TAG = "VideoDecoder"
        
        // MIME types
        const val MIME_TYPE_HEVC = MediaFormat.MIMETYPE_VIDEO_HEVC  // "video/hevc"
        const val MIME_TYPE_AVC = MediaFormat.MIMETYPE_VIDEO_AVC   // "video/avc"
        
        // Match glasses encoder resolution (240x240)
        const val DEFAULT_WIDTH = 240
        const val DEFAULT_HEIGHT = 240
        const val DECODER_TIMEOUT_US = 1000L  // 1ms for low latency
        const val MAX_FRAME_QUEUE = 10
    }
    
    // MediaCodec decoder instance
    @Volatile
    private var decoder: MediaCodec? = null
    private val decoderLock = Object()
    
    // Active codec type (detected from stream)
    @Volatile
    var activeCodec: String = MIME_TYPE_AVC
        private set
    
    // Output surface for rendering
    @Volatile
    private var outputSurface: Surface? = null
    
    // State flags
    @Volatile
    var isRunning = false
        private set
    
    @Volatile
    private var isConfigured = false
    
    // Codec configuration data (SPS/PPS for H.264, VPS/SPS/PPS for H.265)
    @Volatile
    var codecConfigData: ByteArray? = null
        private set
    
    // Frame queue for async decoding
    private val frameQueue = LinkedBlockingQueue<ByteArray>(MAX_FRAME_QUEUE)
    
    // Statistics
    private var framesDecoded = 0
    private var framesReceived = 0
    
    // Buffer info for output processing
    private val bufferInfo = MediaCodec.BufferInfo()
    
    /**
     * Set the output surface for video rendering
     */
    fun setSurface(surface: Surface?) {
        synchronized(decoderLock) {
            outputSurface = surface
            
            // If decoder is running, we may need to reconfigure
            if (decoder != null && surface != null && !isConfigured) {
                // Will reconfigure on next frame
            }
        }
    }
    
    /**
     * Detect codec type from config data
     * H.265 starts with VPS (NAL type 32), H.264 starts with SPS (NAL type 7)
     */
    private fun detectCodecType(configData: ByteArray): String {
        if (configData.size < 5) return MIME_TYPE_AVC
        
        // Find NAL start code
        val startIndex = when {
            configData.size > 4 && configData[0] == 0.toByte() && configData[1] == 0.toByte() &&
                    configData[2] == 0.toByte() && configData[3] == 1.toByte() -> 4
            configData.size > 3 && configData[0] == 0.toByte() && configData[1] == 0.toByte() &&
                    configData[2] == 1.toByte() -> 3
            else -> return MIME_TYPE_AVC
        }
        
        if (startIndex >= configData.size) return MIME_TYPE_AVC
        
        val nalByte = configData[startIndex].toInt()
        
        // H.265: NAL type is bits 1-6, VPS=32, SPS=33, PPS=34
        val hevcNalType = (nalByte shr 1) and 0x3F
        if (hevcNalType in 32..34) {
            Log.d(TAG, "🎬 Detected H.265 (HEVC) stream, NAL type=$hevcNalType")
            return MIME_TYPE_HEVC
        }
        
        // H.264: NAL type is bits 0-4, SPS=7, PPS=8
        val avcNalType = nalByte and 0x1F
        if (avcNalType == 7 || avcNalType == 8) {
            Log.d(TAG, "🎬 Detected H.264 (AVC) stream, NAL type=$avcNalType")
            return MIME_TYPE_AVC
        }
        
        return MIME_TYPE_AVC
    }
    
    /**
     * Initialize and start the decoder with codec config data
     * @param configData SPS/PPS (H.264) or VPS/SPS/PPS (H.265) data for codec initialization.
     *                   Can also be a keyframe with prepended codec config.
     * @return true if successful
     */
    fun start(configData: ByteArray? = null): Boolean {
        synchronized(decoderLock) {
            val surface = outputSurface
            if (surface == null) {
                Log.w(TAG, "Cannot start decoder: no surface set")
                return false
            }
            
            if (decoder != null) {
                Log.w(TAG, "Decoder already started")
                return true
            }
            
            // Extract pure codec config if the data includes IDR
            val pureConfigData = if (configData != null && containsIdrSlice(configData)) {
                val configEnd = findCodecConfigEnd(configData)
                if (configEnd > 0 && configEnd < configData.size) {
                    Log.d(TAG, "Extracting codec config from keyframe data (config: $configEnd bytes, total: ${configData.size} bytes)")
                    configData.copyOfRange(0, configEnd)
                } else {
                    configData
                }
            } else {
                configData
            }
            
            // Detect codec type from config data
            val mimeType = if (pureConfigData != null) {
                detectCodecType(pureConfigData)
            } else {
                MIME_TYPE_AVC
            }
            activeCodec = mimeType
            
            try {
                val codecName = if (mimeType == MIME_TYPE_HEVC) "H.265 (HEVC)" else "H.264 (AVC)"
                Log.d(TAG, "Initializing $codecName decoder: ${width}x${height}")
                
                val mediaDecoder = MediaCodec.createDecoderByType(mimeType)
                val format = MediaFormat.createVideoFormat(mimeType, width, height)
                
                // Add codec config if provided
                if (pureConfigData != null) {
                    format.setByteBuffer("csd-0", ByteBuffer.wrap(pureConfigData))
                    codecConfigData = pureConfigData.copyOf()
                    Log.d(TAG, "Codec config size: ${pureConfigData.size} bytes")
                }
                
                // === LOW LATENCY OPTIMIZATIONS ===
                
                // Enable low latency mode if supported (API 30+)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
                
                // Set real-time priority
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    format.setInteger(MediaFormat.KEY_PRIORITY, 0)  // 0 = realtime
                }
                
                mediaDecoder.configure(format, surface, null, 0)
                mediaDecoder.start()
                
                decoder = mediaDecoder
                isRunning = true
                isConfigured = true
                framesDecoded = 0
                framesReceived = 0
                
                Log.d(TAG, "Decoder initialized successfully")
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Decoder initialization failed: ${e.message}")
                return false
            }
        }
    }
    
    /**
     * Queue a frame for decoding (async)
     */
    fun queueFrame(frameData: ByteArray) {
        framesReceived++
        
        // Check for codec config (including frames that start with config)
        // Store codec config for decoder initialization
        if (startsWithCodecConfig(frameData)) {
            // Extract just the codec config portion if this includes IDR
            if (containsIdrSlice(frameData)) {
                // Frame contains config + IDR, extract config part
                val configEnd = findCodecConfigEnd(frameData)
                if (configEnd > 0 && configEnd < frameData.size) {
                    codecConfigData = frameData.copyOfRange(0, configEnd)
                }
            } else {
                // Pure codec config
                codecConfigData = frameData.copyOf()
            }
        }
        
        // Try to initialize decoder if not yet configured
        if (!isConfigured && codecConfigData != null) {
            start(codecConfigData)
        }
        
        if (!frameQueue.offer(frameData)) {
            // Queue full, drop oldest frame
            frameQueue.poll()
            frameQueue.offer(frameData)
        }
    }
    
    /**
     * Find the end position of codec config data (where IDR slice starts)
     */
    private fun findCodecConfigEnd(data: ByteArray): Int {
        var i = 0
        var lastConfigEnd = 0
        
        while (i < data.size - 4) {
            // Look for NAL start code
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                val startCodeLen: Int
                val nalIndex: Int
                
                when {
                    i + 3 < data.size && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte() -> {
                        startCodeLen = 4
                        nalIndex = i + 4
                    }
                    data[i + 2] == 1.toByte() -> {
                        startCodeLen = 3
                        nalIndex = i + 3
                    }
                    else -> {
                        i++
                        continue
                    }
                }
                
                if (nalIndex >= data.size) break
                
                val nalByte = data[nalIndex].toInt()
                val hevcNalType = (nalByte shr 1) and 0x3F
                
                // If this is config NAL, update lastConfigEnd
                if (hevcNalType in 32..34) {
                    // Find next NAL start to determine end of this config NAL
                    lastConfigEnd = findNextNalStart(data, nalIndex)
                    if (lastConfigEnd < 0) lastConfigEnd = data.size
                }
                // If this is IDR, return the position before it
                else if (hevcNalType in 16..21) {
                    return i
                }
                
                i += startCodeLen
            } else {
                i++
            }
        }
        
        return lastConfigEnd
    }
    
    /**
     * Find the start of the next NAL unit
     */
    private fun findNextNalStart(data: ByteArray, startFrom: Int): Int {
        var i = startFrom
        while (i < data.size - 3) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                if (i + 3 < data.size && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                    return i
                }
                if (data[i + 2] == 1.toByte()) {
                    return i
                }
            }
            i++
        }
        return -1
    }
    
    /**
     * Decode a single frame synchronously
     * @return true if frame was rendered
     */
    fun decodeFrame(frameData: ByteArray): Boolean {
        val safeDecoder = decoder ?: return false
        if (!isRunning || !isConfigured) return false
        
        try {
            val inputIndex = safeDecoder.dequeueInputBuffer(DECODER_TIMEOUT_US)
            if (inputIndex >= 0) {
                val inputBuffer = safeDecoder.getInputBuffer(inputIndex)
                inputBuffer?.clear()
                inputBuffer?.put(frameData)
                
                val flags = if (isCodecConfig(frameData)) {
                    MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                } else {
                    0
                }
                
                safeDecoder.queueInputBuffer(
                    inputIndex, 0, frameData.size,
                    System.nanoTime() / 1000, flags
                )
            } else {
                return false
            }
            
            return drainOutput()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Decode error (illegal state): ${e.message}")
            stop()
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Decode error: ${e.message}")
            return false
        }
    }
    
    /**
     * Process queued frames in a loop (call from background thread)
     */
    fun processQueue() {
        Log.d(TAG, "Starting decode queue processor")
        
        try {
            while (isRunning) {
                val frame = frameQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                
                if (decodeFrame(frame)) {
                    framesDecoded++
                }
            }
        } catch (e: InterruptedException) {
            Log.d(TAG, "Decode queue interrupted")
        } catch (e: Exception) {
            Log.e(TAG, "Decode queue error: ${e.message}")
        } finally {
            Log.d(TAG, "Decode queue processor stopped")
        }
    }
    
    /**
     * Drain output buffers and render frames
     */
    private fun drainOutput(): Boolean {
        val safeDecoder = decoder ?: return false
        var rendered = false
        
        var outputIndex = safeDecoder.dequeueOutputBuffer(bufferInfo, DECODER_TIMEOUT_US)
        
        while (true) {
            when {
                outputIndex >= 0 -> {
                    // Render with timestamp for VSync sync
                    safeDecoder.releaseOutputBuffer(outputIndex, bufferInfo.presentationTimeUs * 1000)
                    rendered = true
                    framesDecoded++
                    outputIndex = safeDecoder.dequeueOutputBuffer(bufferInfo, 0)
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    Log.d(TAG, "Decoder output format changed: ${safeDecoder.outputFormat}")
                    outputIndex = safeDecoder.dequeueOutputBuffer(bufferInfo, 0)
                }
                else -> break
            }
        }
        
        return rendered
    }
    
    /**
     * Stop and release the decoder
     */
    fun stop() {
        isRunning = false
        isConfigured = false
        
        synchronized(decoderLock) {
            try {
                decoder?.stop()
                decoder?.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping decoder: ${e.message}")
            }
            decoder = null
        }
        
        frameQueue.clear()
        
        Log.d(TAG, "Decoder stopped. Received: $framesReceived, Decoded: $framesDecoded")
    }
    
    /**
     * Check if frame data is PURE codec config (only VPS/SPS/PPS, no IDR slice)
     * 
     * This function returns true ONLY for pure codec config data, NOT for frames
     * that have codec config prepended to them.
     * 
     * H.264: SPS (NAL type 7) or PPS (NAL type 8) only
     * H.265: VPS (NAL type 32), SPS (NAL type 33), or PPS (NAL type 34) only
     * 
     * When codec config is prepended to a keyframe (e.g., VPS+SPS+PPS+IDR),
     * this returns false because the decoder should process it as a normal frame.
     */
    fun isCodecConfig(data: ByteArray): Boolean {
        if (data.size < 5) return false
        
        // Check first NAL unit type
        val firstNalType = getFirstNalType(data)
        if (firstNalType < 0) return false
        
        // Check if first NAL is codec config type
        val isHevcConfig = firstNalType in 32..34
        val isAvcConfig = firstNalType == 7 || firstNalType == 8
        
        if (!isHevcConfig && !isAvcConfig) return false
        
        // Now check if this is PURE codec config (no IDR frame after)
        // If the data contains an IDR slice, it's a keyframe with prepended config
        return !containsIdrSlice(data)
    }
    
    /**
     * Check if frame data starts with codec config (VPS/SPS/PPS)
     * This is used to detect and store codec config for decoder initialization
     */
    fun startsWithCodecConfig(data: ByteArray): Boolean {
        if (data.size < 5) return false
        
        val firstNalType = getFirstNalType(data)
        if (firstNalType < 0) return false
        
        // Check if first NAL is codec config type
        return firstNalType in 32..34 || firstNalType == 7 || firstNalType == 8
    }
    
    /**
     * Get the NAL unit type of the first NAL unit in the data
     */
    private fun getFirstNalType(data: ByteArray): Int {
        val startIndex = when {
            data.size > 4 && data[0] == 0.toByte() && data[1] == 0.toByte() &&
                    data[2] == 0.toByte() && data[3] == 1.toByte() -> 4
            data.size > 3 && data[0] == 0.toByte() && data[1] == 0.toByte() &&
                    data[2] == 1.toByte() -> 3
            else -> return -1
        }
        
        if (startIndex >= data.size) return -1
        
        val nalByte = data[startIndex].toInt()
        
        // Detect if this is HEVC or AVC based on common patterns
        // For HEVC: NAL type is bits 1-6 of first byte
        // For AVC: NAL type is bits 0-4 of first byte
        val hevcNalType = (nalByte shr 1) and 0x3F
        val avcNalType = nalByte and 0x1F
        
        // Heuristic: if HEVC NAL type makes sense (0-63 valid, common: 0-40), use HEVC
        // VPS=32, SPS=33, PPS=34, IDR=19-20, P-frame=0-1
        if (hevcNalType in 0..40) {
            return hevcNalType
        }
        
        return avcNalType
    }
    
    /**
     * Check if the data contains an IDR slice (keyframe)
     * This helps distinguish pure codec config from config+keyframe
     */
    private fun containsIdrSlice(data: ByteArray): Boolean {
        var i = 0
        while (i < data.size - 4) {
            // Look for NAL start code
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                val startCodeLen: Int
                val nalIndex: Int
                
                when {
                    i + 3 < data.size && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte() -> {
                        startCodeLen = 4
                        nalIndex = i + 4
                    }
                    data[i + 2] == 1.toByte() -> {
                        startCodeLen = 3
                        nalIndex = i + 3
                    }
                    else -> {
                        i++
                        continue
                    }
                }
                
                if (nalIndex >= data.size) break
                
                val nalByte = data[nalIndex].toInt()
                
                // Check for H.265 IDR (NAL types 16-21 = IRAP)
                val hevcNalType = (nalByte shr 1) and 0x3F
                if (hevcNalType in 16..21) return true
                
                // Check for H.264 IDR (NAL type 5)
                val avcNalType = nalByte and 0x1F
                if (avcNalType == 5) return true
                
                i += startCodeLen
            } else {
                i++
            }
        }
        
        return false
    }
    
    /**
     * Check if currently using H.265 (HEVC) codec
     */
    fun isUsingHevc(): Boolean = activeCodec == MIME_TYPE_HEVC
    
    /**
     * Get decode statistics
     */
    fun getStats(): Pair<Int, Int> = Pair(framesReceived, framesDecoded)
}
