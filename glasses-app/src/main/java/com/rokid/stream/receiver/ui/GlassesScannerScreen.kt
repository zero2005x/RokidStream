package com.rokid.stream.receiver.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rokid.stream.receiver.R

/**
 * Scanned Bluetooth device info
 */
data class ScannedDevice(
    val address: String,
    val name: String,
    val rssi: Int = 0   // Signal strength
)

/**
 * Glasses connection state
 */
enum class GlassesState {
    SCANNING,       // Scanning for phones
    DEVICE_LIST,    // Showing device list
    CONNECTING,     // Connecting to selected phone
    CONNECTED,      // Connected, waiting for stream
    STREAMING       // Actively receiving/sending stream
}

/**
 * Simplified Glasses UI - Device Scanner Version
 * 
 * The glasses only need to:
 * 1. Scan for available phones running the app
 * 2. Let user select which phone to connect to
 * 3. Display the video stream
 * 
 * All streaming settings (mode, direction) are controlled by the phone.
 * 
 * Touch gestures:
 * - Swipe up/down: Scroll device list
 * - Single tap: Select device / Disconnect
 */
@Composable
fun GlassesScannerScreen(
    state: GlassesState,
    scannedDevices: List<ScannedDevice>,
    connectedDeviceName: String = "",
    isScanning: Boolean = false,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onDeviceSelected: (ScannedDevice) -> Unit,
    onDisconnect: () -> Unit,
    videoView: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        when (state) {
            GlassesState.SCANNING, GlassesState.DEVICE_LIST -> {
                // Device selection screen
                DeviceSelectionScreen(
                    devices = scannedDevices,
                    isScanning = isScanning,
                    onStartScan = onStartScan,
                    onStopScan = onStopScan,
                    onDeviceSelected = onDeviceSelected
                )
            }
            
            GlassesState.CONNECTING -> {
                // Connecting overlay
                ConnectingScreen(deviceName = connectedDeviceName)
            }
            
            GlassesState.CONNECTED, GlassesState.STREAMING -> {
                // Video display with disconnect option
                StreamingScreen(
                    state = state,
                    connectedDeviceName = connectedDeviceName,
                    onDisconnect = onDisconnect,
                    videoView = videoView
                )
            }
        }
    }
}

@Composable
private fun DeviceSelectionScreen(
    devices: List<ScannedDevice>,
    isScanning: Boolean,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onDeviceSelected: (ScannedDevice) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.glasses_select_phone),
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            
            // Scan button
            IconButton(
                onClick = { if (isScanning) onStopScan() else onStartScan() }
            ) {
                if (isScanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.glasses_scan),
                        tint = Color.White
                    )
                }
            }
        }
        
        // Scanning indicator
        if (isScanning) {
            Text(
                text = stringResource(R.string.glasses_scanning),
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 14.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        
        // Device list
        if (devices.isEmpty()) {
            // Empty state
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.PhoneAndroid,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.3f),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = if (isScanning) {
                            stringResource(R.string.glasses_searching)
                        } else {
                            stringResource(R.string.glasses_no_devices)
                        },
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center
                    )
                    if (!isScanning) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.glasses_tap_to_scan),
                            color = Color.White.copy(alpha = 0.4f),
                            fontSize = 14.sp
                        )
                    }
                }
            }
        } else {
            // Device list
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(devices) { device ->
                    DeviceListItem(
                        device = device,
                        onClick = { onDeviceSelected(device) }
                    )
                }
            }
        }
        
        // Bottom hint
        Text(
            text = stringResource(R.string.glasses_swipe_hint),
            color = Color.White.copy(alpha = 0.4f),
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
        )
    }
}

@Composable
private fun DeviceListItem(
    device: ScannedDevice,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = Color.White.copy(alpha = 0.1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Phone icon
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PhoneAndroid,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            // Device info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name.ifEmpty { stringResource(R.string.glasses_unknown_device) },
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = device.address,
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp
                )
            }
            
            // Signal strength indicator
            SignalStrengthIndicator(rssi = device.rssi)
            
            Spacer(modifier = Modifier.width(8.dp))
            
            // Arrow
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
private fun SignalStrengthIndicator(rssi: Int) {
    // Convert RSSI to signal bars (0-3)
    val bars = when {
        rssi >= -50 -> 3  // Excellent
        rssi >= -70 -> 2  // Good
        rssi >= -85 -> 1  // Fair
        else -> 0         // Weak
    }
    
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        repeat(3) { index ->
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height((8 + index * 4).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        if (index < bars) Color.Green
                        else Color.White.copy(alpha = 0.2f)
                    )
            )
        }
    }
}

@Composable
private fun ConnectingScreen(deviceName: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.glasses_connecting),
                color = Color.White,
                fontSize = 18.sp
            )
            if (deviceName.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = deviceName,
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp
                )
            }
        }
    }
}

@Composable
private fun StreamingScreen(
    state: GlassesState,
    connectedDeviceName: String,
    onDisconnect: () -> Unit,
    videoView: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        // Double-tap to disconnect (避免與 Rokid 系統長按手勢衝突)
                        onDisconnect()
                    }
                )
            }
    ) {
        // Video view (full screen)
        videoView()
        
        // Status overlay (top)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)
                    )
                )
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Connected device
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.PhoneAndroid,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = connectedDeviceName.ifEmpty { stringResource(R.string.glasses_connected) },
                        color = Color.White,
                        fontSize = 14.sp
                    )
                }
                
                // Status badge
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (state == GlassesState.STREAMING) {
                        Color(0xFF4CAF50).copy(alpha = 0.3f)
                    } else {
                        Color.White.copy(alpha = 0.2f)
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (state == GlassesState.STREAMING) {
                            // Pulsing dot for streaming
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF4CAF50))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text(
                            text = if (state == GlassesState.STREAMING) {
                                stringResource(R.string.glasses_status_streaming)
                            } else {
                                stringResource(R.string.glasses_status_connected)
                            },
                            color = Color.White,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
        
        // Disconnect hint (bottom)
        Text(
            text = stringResource(R.string.glasses_long_press_disconnect),
            color = Color.White.copy(alpha = 0.4f),
            fontSize = 12.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
        )
    }
}
