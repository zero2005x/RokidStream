package com.rokid.stream.receiver.core

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * VideoEncoder - H.264 video encoder for Glasses→Phone streaming mode
 * 
 * Encapsulates MediaCodec encoder with low-latency optimizations.
 * Adapted from phone-app VideoEncoder for use on Rokid glasses.
 * 
 * Features:
 * - Low latency configuration for real-time streaming
 * - NV12 format conversion from CameraX YUV_420_888
 * - Thread-safe operation with proper synchronization
 * - Automatic SPS/PPS handling for decoder initialization
 */
class VideoEncoder(
    private val width: Int = DEFAULT_WIDTH,
    private val height: Int = DEFAULT_HEIGHT,
    private val bitrate: Int = DEFAULT_BITRATE,
    private val frameRate: Int = DEFAULT_FRAME_RATE
) {
    companion object {
        private const val TAG = "VideoEncoder"
        
        // Default video encoding parameters - OPTIMIZED FOR BLE L2CAP STREAMING
        // Based on: https://medium.com/@20x05zero/real-time-video-streaming-to-ar-glasses
        const val DEFAULT_WIDTH = 720
        const val DEFAULT_HEIGHT = 720
        const val DEFAULT_BITRATE = 300_000  // 300 Kbps - optimal for BLE L2CAP
        const val DEFAULT_FRAME_RATE = 10    // 10 FPS - stable streaming
        const val I_FRAME_INTERVAL = 1       // Keyframe every 1 second
        const val ENCODER_TIMEOUT_US = 1000L // 1ms encoder timeout
        const val MAX_PENDING_FRAMES = 30    // Increased buffer for BLE L2CAP throughput
        const val MAX_FRAME_SIZE = 1_000_000 // 1MB for validation
    }
    
    // MediaCodec encoder instance
    @Volatile
    private var encoder: MediaCodec? = null
    private val encoderLock = Object()
    
    // SPS/PPS data for decoder initialization
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
            
            try {
                Log.d(TAG, "Initializing H.264 encoder: ${actualWidth}x${actualHeight}")
                
                val mediaEncoder = MediaCodec.createEncoderByType("video/avc")
                val format = MediaFormat.createVideoFormat("video/avc", actualWidth, actualHeight)
                
                // Configure encoder format
                format.setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                )
                format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
                
                // Set encoder profile for better compatibility
                format.setInteger(
                    MediaFormat.KEY_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
                )
                format.setInteger(
                    MediaFormat.KEY_LEVEL,
                    MediaCodecInfo.CodecProfileLevel.AVCLevel31
                )
                
                // === LOW LATENCY OPTIMIZATIONS ===
                
                // Enable low latency mode if supported (API 30+)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
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
                
                mediaEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                mediaEncoder.start()
                
                encoder = mediaEncoder
                isRunning = true
                framesEncoded = 0
                droppedFrames = 0
                
                // Start encoder output drain thread
                Thread { drainEncoderOutput() }.start()
                
                Log.d(TAG, "Encoder initialized successfully")
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Encoder initialization failed: ${e.message}")
                return false
            }
        }
    }
    
    /**
     * Encode a camera frame from CameraX ImageProxy
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
                            // Check for codec config data (SPS/PPS)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                val configData = ByteArray(bufferInfo.size)
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.get(configData)
                                spsPpsData = configData
                                Log.d(TAG, "Received codec config (SPS/PPS): ${bufferInfo.size} bytes")
                                
                                // Queue SPS/PPS with high priority
                                if (!pendingFrames.offer(configData)) {
                                    pendingFrames.poll()
                                    pendingFrames.offer(configData)
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
                                    spsPpsData?.let { sps ->
                                        if (!pendingFrames.offer(sps)) {
                                            pendingFrames.poll()
                                            pendingFrames.offer(sps)
                                        }
                                    }
                                }
                                
                                // Queue the encoded frame
                                if (!pendingFrames.offer(frameData)) {
                                    pendingFrames.poll()
                                    pendingFrames.offer(frameData)
                                    droppedFrames++
                                    if (droppedFrames % 10 == 0) {
                                        Log.d(TAG, "Dropped $droppedFrames frames due to slow output")
                                    }
                                }
                                
                                framesEncoded++
                                onFrameEncoded?.invoke(frameData, isKeyFrame)
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
     */
    fun isKeyFrame(frameData: ByteArray): Boolean {
        if (frameData.size < 5) return false
        for (i in 0 until minOf(frameData.size - 4, 100)) {
            if (frameData[i] == 0x00.toByte() && frameData[i+1] == 0x00.toByte()) {
                val startCodeLen = if (frameData[i+2] == 0x01.toByte()) 3
                else if (frameData[i+2] == 0x00.toByte() && frameData[i+3] == 0x01.toByte()) 4
                else continue
                val nalType = (frameData[i + startCodeLen].toInt() and 0x1F)
                if (nalType == 5 || nalType == 7) return true
            }
        }
        return false
    }
}
