package com.rokid.stream.sender.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.rokid.stream.sender.ui.theme.RokidStreamTheme
import com.rokid.stream.sender.util.LocaleManager
import kotlinx.coroutines.launch

/**
 * Compose-based Activity for RokidStream Sender App
 * 
 * This activity uses Jetpack Compose for UI while maintaining
 * the existing video streaming logic.
 */
abstract class ComposeStreamingActivity : ComponentActivity() {
    
    protected val viewModel: StreamingViewModel by viewModels()
    
    // Camera preview view reference
    protected var cameraPreviewView: PreviewView? = null
    
    // Received video surface view reference
    protected var receivedVideoSurfaceView: SurfaceView? = null
    protected var receivedSurface: Surface? = null
    
    // Permission launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            onPermissionsGranted()
        } else {
            viewModel.setError("Camera and Bluetooth permissions are required to use this app")
        }
    }
    
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.applyLocale(newBase))
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        requestPermissions()
        
        setContent {
            RokidStreamTheme {
                val uiState by viewModel.uiState.collectAsState()
                
                StreamingScreen(
                    connectionStatus = uiState.connectionStatus,
                    selectedDirection = uiState.selectedDirection,
                    selectedMode = uiState.selectedMode,
                    logMessages = uiState.logMessages,
                    onDirectionChange = { viewModel.selectDirection(it) },
                    onModeChange = { viewModel.selectMode(it) },
                    onConnectClick = { onConnectClicked() },
                    onDisconnectClick = { onDisconnectClicked() },
                    onSettingsClick = { 
                        startActivity(Intent(this, SettingsActivity::class.java))
                    },
                    cameraPreview = {
                        AndroidView(
                            factory = { context ->
                                PreviewView(context).also { preview ->
                                    preview.layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                    cameraPreviewView = preview
                                    onCameraPreviewReady(preview)
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    },
                    receivedVideoView = {
                        AndroidView(
                            factory = { context ->
                                SurfaceView(context).also { surfaceView ->
                                    surfaceView.layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                    receivedVideoSurfaceView = surfaceView
                                    surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                                        override fun surfaceCreated(holder: SurfaceHolder) {
                                            receivedSurface = holder.surface
                                            onReceivedVideoSurfaceReady(holder.surface)
                                        }
                                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                                            receivedSurface = null
                                            onReceivedVideoSurfaceDestroyed()
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
    
    private fun requestPermissions() {
        val permissions = arrayOf(
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
        permissionLauncher.launch(permissions)
    }
    
    /**
     * Called when all permissions are granted
     */
    protected abstract fun onPermissionsGranted()
    
    /**
     * Called when connect button is clicked
     */
    protected abstract fun onConnectClicked()
    
    /**
     * Called when disconnect button is clicked
     */
    protected abstract fun onDisconnectClicked()
    
    /**
     * Called when camera preview view is ready
     */
    protected abstract fun onCameraPreviewReady(previewView: PreviewView)
    
    /**
     * Called when received video surface is ready
     */
    protected abstract fun onReceivedVideoSurfaceReady(surface: Surface)
    
    /**
     * Called when received video surface is destroyed
     */
    protected abstract fun onReceivedVideoSurfaceDestroyed()
    
    /**
     * Helper to run on UI thread
     */
    protected fun runOnUiThread(action: () -> Unit) {
        lifecycleScope.launch {
            action()
        }
    }
    
    /**
     * Convert UI connection mode to internal enum
     */
    protected fun getSelectedConnectionMode(): Int {
        return when (viewModel.uiState.value.selectedMode) {
            ConnectionMode.L2CAP -> 0
            ConnectionMode.WIFI -> 1
            ConnectionMode.ROKID_SDK -> 2
        }
    }
    
    /**
     * Check if should send video (based on direction)
     */
    protected fun shouldSendVideo(): Boolean {
        return when (viewModel.uiState.value.selectedDirection) {
            StreamDirection.PHONE_TO_GLASSES -> true
            StreamDirection.GLASSES_TO_PHONE -> false
            StreamDirection.BIDIRECTIONAL -> true
        }
    }
    
    /**
     * Check if should receive video (based on direction)
     */
    protected fun shouldReceiveVideo(): Boolean {
        return when (viewModel.uiState.value.selectedDirection) {
            StreamDirection.PHONE_TO_GLASSES -> false
            StreamDirection.GLASSES_TO_PHONE -> true
            StreamDirection.BIDIRECTIONAL -> true
        }
    }
}
