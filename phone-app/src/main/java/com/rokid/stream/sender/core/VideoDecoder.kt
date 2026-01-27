package com.rokid.stream.sender.core

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * VideoDecoder - Shared H.264 video decoder for all streaming modes
 * 
 * Encapsulates MediaCodec decoder with low-latency optimizations.
 * Designed to be reused across GlassesToPhoneActivity and BidirectionalActivity.
 */
class VideoDecoder(
    private val width: Int = DEFAULT_WIDTH,
    private val height: Int = DEFAULT_HEIGHT
) {
    companion object {
        private const val TAG = "VideoDecoder"
        
        const val DEFAULT_WIDTH = 720
        const val DEFAULT_HEIGHT = 720
        const val DECODER_TIMEOUT_US = 1000L  // 1ms for low latency
        const val MAX_FRAME_QUEUE = 10
    }
    
    // MediaCodec decoder instance
    @Volatile
    private var decoder: MediaCodec? = null
    private val decoderLock = Object()
    
    // Output surface for rendering
    @Volatile
    private var outputSurface: Surface? = null
    
    // State flags
    @Volatile
    var isRunning = false
        private set
    
    @Volatile
    private var isConfigured = false
    
    // Codec configuration data (SPS/PPS)
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
     * Initialize and start the decoder with codec config data
     * @param configData SPS/PPS data for codec initialization
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
            
            try {
                Log.d(TAG, "Initializing H.264 decoder: ${width}x${height}")
                
                val mediaDecoder = MediaCodec.createDecoderByType("video/avc")
                val format = MediaFormat.createVideoFormat("video/avc", width, height)
                
                // Add codec config if provided
                if (configData != null) {
                    format.setByteBuffer("csd-0", ByteBuffer.wrap(configData))
                    codecConfigData = configData.copyOf()
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
        
        // Check for codec config
        if (isCodecConfig(frameData)) {
            codecConfigData = frameData.copyOf()
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
     * Check if frame data contains codec config (SPS/PPS)
     */
    fun isCodecConfig(data: ByteArray): Boolean {
        if (data.size < 5) return false
        
        val startIndex = when {
            data.size > 4 && data[0] == 0.toByte() && data[1] == 0.toByte() &&
                    data[2] == 0.toByte() && data[3] == 1.toByte() -> 4
            data.size > 3 && data[0] == 0.toByte() && data[1] == 0.toByte() &&
                    data[2] == 1.toByte() -> 3
            else -> 0
        }
        
        if (startIndex >= data.size) return false
        
        val nalType = data[startIndex].toInt() and 0x1F
        return nalType == 7 || nalType == 8  // SPS or PPS
    }
    
    /**
     * Get decode statistics
     */
    fun getStats(): Pair<Int, Int> = Pair(framesReceived, framesDecoded)
}
