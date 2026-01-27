package com.rokid.stream.receiver.ui

import android.Manifest
import android.content.Context
import android.os.Bundle
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.rokid.stream.receiver.ui.theme.RokidStreamTheme
import com.rokid.stream.receiver.util.LocaleManager
import kotlinx.coroutines.launch

/**
 * Simplified Activity for RokidStream Glasses App
 * 
 * Optimized for touch panel navigation:
 * - Swipe left/right: Switch connection mode
 * - Single tap: Start/Stop connection
 * - Double tap: Exit app (system behavior)
 * 
 * This is a base class - extend it to implement actual streaming logic.
 */
abstract class SimpleGlassesActivity : ComponentActivity() {
    
    // UI State
    private var _status by mutableStateOf(GlassesStatus.IDLE)
    protected var status: GlassesStatus
        get() = _status
        set(value) { _status = value }
    
    private var _selectedMode by mutableStateOf(GlassesConnectionMode.L2CAP)
    protected var selectedMode: GlassesConnectionMode
        get() = _selectedMode
        set(value) { _selectedMode = value }
    
    private var _connectedDeviceName by mutableStateOf("")
    protected var connectedDeviceName: String
        get() = _connectedDeviceName
        set(value) { _connectedDeviceName = value }
    
    // Video surface
    protected var videoSurface: Surface? = null
        private set
    
    private var videoSurfaceView: SurfaceView? = null
    
    // Permission launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            onPermissionsGranted()
        }
    }
    
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.applyLocale(newBase))
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Load saved mode preference
        loadSavedMode()
        
        requestPermissions()
        
        setContent {
            RokidStreamTheme {
                SimpleGlassesScreen(
                    status = status,
                    selectedMode = selectedMode,
                    connectedDeviceName = connectedDeviceName,
                    onModeChange = { newMode ->
                        if (status == GlassesStatus.IDLE) {
                            selectedMode = newMode
                            saveModePreference(newMode)
                        }
                    },
                    onToggleConnection = {
                        handleToggleConnection()
                    },
                    videoView = {
                        AndroidView(
                            factory = { context ->
                                SurfaceView(context).also { surfaceView ->
                                    surfaceView.layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                    videoSurfaceView = surfaceView
                                    surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                                        override fun surfaceCreated(holder: SurfaceHolder) {
                                            videoSurface = holder.surface
                                            onVideoSurfaceReady(holder.surface)
                                        }
                                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                                            onVideoSurfaceChanged(holder.surface, width, height)
                                        }
                                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                                            videoSurface = null
                                            onVideoSurfaceDestroyed()
                                        }
                                    })
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                )
            }
        }
    }
    
    private fun handleToggleConnection() {
        when (status) {
            GlassesStatus.IDLE -> {
                status = GlassesStatus.WAITING
                onStartConnection(selectedMode)
            }
            GlassesStatus.WAITING -> {
                status = GlassesStatus.IDLE
                onCancelConnection()
            }
            GlassesStatus.CONNECTED, GlassesStatus.STREAMING -> {
                status = GlassesStatus.IDLE
                onDisconnect()
            }
        }
    }
    
    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE
        )
        permissionLauncher.launch(permissions.toTypedArray())
    }
    
    private fun loadSavedMode() {
        val prefs = getSharedPreferences("glasses_settings", Context.MODE_PRIVATE)
        val modeIndex = prefs.getInt("connection_mode", 0)
        selectedMode = GlassesConnectionMode.entries.getOrNull(modeIndex) ?: GlassesConnectionMode.L2CAP
    }
    
    private fun saveModePreference(mode: GlassesConnectionMode) {
        getSharedPreferences("glasses_settings", Context.MODE_PRIVATE)
            .edit()
            .putInt("connection_mode", mode.ordinal)
            .apply()
    }
    
    /**
     * Update status from subclass (thread-safe)
     */
    protected fun updateStatus(newStatus: GlassesStatus) {
        lifecycleScope.launch {
            status = newStatus
        }
    }
    
    /**
     * Update connected device name from subclass (thread-safe)
     */
    protected fun updateConnectedDevice(name: String) {
        lifecycleScope.launch {
            connectedDeviceName = name
        }
    }
    
    // ===== Abstract methods to implement =====
    
    /**
     * Called when all permissions are granted
     */
    protected abstract fun onPermissionsGranted()
    
    /**
     * Called when user taps to start connection
     */
    protected abstract fun onStartConnection(mode: GlassesConnectionMode)
    
    /**
     * Called when user taps to cancel waiting connection
     */
    protected abstract fun onCancelConnection()
    
    /**
     * Called when user taps to disconnect
     */
    protected abstract fun onDisconnect()
    
    /**
     * Called when video surface is ready for rendering
     */
    protected abstract fun onVideoSurfaceReady(surface: Surface)
    
    /**
     * Called when video surface size changes
     */
    protected open fun onVideoSurfaceChanged(surface: Surface, width: Int, height: Int) {}
    
    /**
     * Called when video surface is destroyed
     */
    protected abstract fun onVideoSurfaceDestroyed()
}
