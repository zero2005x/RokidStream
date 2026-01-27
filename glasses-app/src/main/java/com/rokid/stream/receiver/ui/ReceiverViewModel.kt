package com.rokid.stream.receiver.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.*

/**
 * UI State for Receiver Screen
 */
data class ReceiverUiState(
    val serverStatus: ServerStatus = ServerStatus.STOPPED,
    val selectedDirection: StreamDirection = StreamDirection.PHONE_TO_GLASSES,
    val selectedMode: ConnectionMode = ConnectionMode.L2CAP,
    val connectedDeviceName: String = "",
    val logMessages: List<String> = emptyList(),
    val errorMessage: String? = null,
    val framesReceived: Long = 0,
    val framesSent: Long = 0,
    val currentBitrate: Int = 0
)

/**
 * ViewModel for Receiver Screen state management
 */
class ReceiverViewModel : ViewModel() {
    
    companion object {
        private const val TAG = "ReceiverViewModel"
        private const val MAX_LOG_MESSAGES = 100
    }
    
    private val _uiState = MutableStateFlow(ReceiverUiState())
    val uiState: StateFlow<ReceiverUiState> = _uiState.asStateFlow()
    
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    
    /**
     * Update server status
     */
    fun updateServerStatus(status: ServerStatus) {
        _uiState.update { it.copy(serverStatus = status) }
        addLog("Server status: ${status.name}")
    }
    
    /**
     * Select streaming direction
     */
    fun selectDirection(direction: StreamDirection) {
        if (_uiState.value.serverStatus != ServerStatus.STOPPED) {
            addLog("⚠️ Please stop service first before changing stream direction")
            return
        }
        _uiState.update { it.copy(selectedDirection = direction) }
        addLog("Stream direction: ${direction.label}")
    }
    
    /**
     * Select connection mode
     */
    fun selectMode(mode: ConnectionMode) {
        if (_uiState.value.serverStatus != ServerStatus.STOPPED) {
            addLog("⚠️ Please stop service first before changing connection mode")
            return
        }
        _uiState.update { it.copy(selectedMode = mode) }
        addLog("Connection mode: ${mode.label}")
    }
    
    /**
     * Update connected device name
     */
    fun updateConnectedDevice(name: String) {
        _uiState.update { it.copy(connectedDeviceName = name) }
    }
    
    /**
     * Increment frames received counter
     */
    fun incrementFramesReceived() {
        _uiState.update { it.copy(framesReceived = it.framesReceived + 1) }
    }
    
    /**
     * Increment frames sent counter
     */
    fun incrementFramesSent() {
        _uiState.update { it.copy(framesSent = it.framesSent + 1) }
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
                framesReceived = 0,
                framesSent = 0,
                currentBitrate = 0
            )
        }
    }
    
    /**
     * Handle server starting
     */
    fun onServerStarting() {
        updateServerStatus(ServerStatus.STARTING)
        addLog("🔄 Starting service...")
    }
    
    /**
     * Handle server started and waiting for client
     */
    fun onServerWaitingClient() {
        updateServerStatus(ServerStatus.WAITING_CLIENT)
        addLog("⏳ Waiting for phone connection...")
    }
    
    /**
     * Handle client connected
     */
    fun onClientConnected(deviceName: String = "Phone") {
        updateConnectedDevice(deviceName)
        updateServerStatus(ServerStatus.CONNECTED)
        addLog("✅ Connected: $deviceName")
    }
    
    /**
     * Handle streaming started
     */
    fun onStreamingStarted() {
        updateServerStatus(ServerStatus.STREAMING)
        resetStats()
        addLog("📹 Streaming started")
    }
    
    /**
     * Handle server stopped
     */
    fun onServerStopped() {
        updateServerStatus(ServerStatus.STOPPED)
        updateConnectedDevice("")
        addLog("🔌 Service stopped")
    }
    
    /**
     * Handle error
     */
    fun onError(error: String) {
        updateServerStatus(ServerStatus.STOPPED)
        setError(error)
    }
}
