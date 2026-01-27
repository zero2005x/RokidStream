package com.rokid.stream.sender.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.*

/**
 * UI State for Streaming Screen
 */
data class StreamingUiState(
    val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val selectedDirection: StreamDirection = StreamDirection.PHONE_TO_GLASSES,
    val selectedMode: ConnectionMode = ConnectionMode.L2CAP,
    val logMessages: List<String> = emptyList(),
    val errorMessage: String? = null,
    val deviceName: String = "",
    val isScanning: Boolean = false,
    val framesSent: Long = 0,
    val framesReceived: Long = 0,
    val currentBitrate: Int = 0
)

/**
 * ViewModel for Streaming Screen state management
 */
class StreamingViewModel : ViewModel() {
    
    companion object {
        private const val TAG = "StreamingViewModel"
        private const val MAX_LOG_MESSAGES = 100
    }
    
    private val _uiState = MutableStateFlow(StreamingUiState())
    val uiState: StateFlow<StreamingUiState> = _uiState.asStateFlow()
    
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    
    /**
     * Update connection status
     */
    fun updateConnectionStatus(status: ConnectionStatus) {
        _uiState.update { it.copy(connectionStatus = status) }
        addLog("Connection status: ${status.name}")
    }
    
    /**
     * Select streaming direction
     */
    fun selectDirection(direction: StreamDirection) {
        if (_uiState.value.connectionStatus != ConnectionStatus.DISCONNECTED) {
            addLog("⚠️ Please disconnect first before changing stream direction")
            return
        }
        _uiState.update { it.copy(selectedDirection = direction) }
        addLog("Stream direction: ${direction.label}")
    }
    
    /**
     * Select connection mode
     */
    fun selectMode(mode: ConnectionMode) {
        if (_uiState.value.connectionStatus != ConnectionStatus.DISCONNECTED) {
            addLog("⚠️ Please disconnect first before changing connection mode")
            return
        }
        _uiState.update { it.copy(selectedMode = mode) }
        addLog("Connection mode: ${mode.label}")
    }
    
    /**
     * Update device name
     */
    fun updateDeviceName(name: String) {
        _uiState.update { it.copy(deviceName = name) }
    }
    
    /**
     * Set scanning state
     */
    fun setScanning(scanning: Boolean) {
        _uiState.update { it.copy(isScanning = scanning) }
    }
    
    /**
     * Increment frames sent counter
     */
    fun incrementFramesSent() {
        _uiState.update { it.copy(framesSent = it.framesSent + 1) }
    }
    
    /**
     * Increment frames received counter
     */
    fun incrementFramesReceived() {
        _uiState.update { it.copy(framesReceived = it.framesReceived + 1) }
    }
    
    /**
     * Update bitrate display
     */
    fun updateBitrate(bitrate: Int) {
        _uiState.update { it.copy(currentBitrate = bitrate) }
    }
    
    /**
     * Set error message
     */
    fun setError(message: String?) {
        _uiState.update { it.copy(errorMessage = message) }
        if (message != null) {
            addLog("❌ Error: $message")
        }
    }
    
    /**
     * Clear error message
     */
    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
    
    /**
     * Add log message with timestamp
     */
    fun addLog(message: String) {
        val timestamp = dateFormat.format(Date())
        val logEntry = "[$timestamp] $message"
        Log.d(TAG, logEntry)
        
        _uiState.update { currentState ->
            val updatedLogs = (currentState.logMessages + logEntry).takeLast(MAX_LOG_MESSAGES)
            currentState.copy(logMessages = updatedLogs)
        }
    }
    
    /**
     * Clear all logs
     */
    fun clearLogs() {
        _uiState.update { it.copy(logMessages = emptyList()) }
    }
    
    /**
     * Reset statistics
     */
    fun resetStats() {
        _uiState.update { 
            it.copy(
                framesSent = 0,
                framesReceived = 0,
                currentBitrate = 0
            )
        }
    }
    
    /**
     * Handle connection initiated
     */
    fun onConnectionInitiated() {
        updateConnectionStatus(ConnectionStatus.CONNECTING)
        addLog("🔄 Connecting...")
    }
    
    /**
     * Handle connection established
     */
    fun onConnectionEstablished(deviceName: String = "") {
        updateDeviceName(deviceName)
        updateConnectionStatus(ConnectionStatus.CONNECTED)
        addLog("✅ Connected to: $deviceName")
    }
    
    /**
     * Handle streaming started
     */
    fun onStreamingStarted() {
        updateConnectionStatus(ConnectionStatus.STREAMING)
        resetStats()
        addLog("📹 Streaming started")
    }
    
    /**
     * Handle disconnection
     */
    fun onDisconnected() {
        updateConnectionStatus(ConnectionStatus.DISCONNECTED)
        updateDeviceName("")
        addLog("🔌 Disconnected")
    }
    
    /**
     * Handle connection error
     */
    fun onConnectionError(error: String) {
        updateConnectionStatus(ConnectionStatus.DISCONNECTED)
        setError(error)
    }
}
