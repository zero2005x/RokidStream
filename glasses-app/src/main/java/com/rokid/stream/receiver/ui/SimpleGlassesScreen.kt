package com.rokid.stream.receiver.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rokid.stream.receiver.R

/**
 * Connection mode for glasses
 */
enum class GlassesConnectionMode(
    val icon: ImageVector,
    val labelResId: Int,
    val descriptionResId: Int
) {
    L2CAP(Icons.Default.Bluetooth, R.string.mode_l2cap, R.string.mode_l2cap_desc),
    WIFI(Icons.Default.Wifi, R.string.mode_wifi, R.string.mode_wifi_desc),
    ROKID_SDK(Icons.Default.Cloud, R.string.mode_rokid_sdk, R.string.mode_rokid_sdk_desc)
}

/**
 * Connection status
 */
enum class GlassesStatus {
    IDLE,           // Not started
    WAITING,        // Waiting for phone connection
    CONNECTED,      // Phone connected
    STREAMING       // Actively streaming
}

/**
 * Simplified Glasses UI optimized for touch panel navigation
 * 
 * Touch gestures:
 * - Swipe Left/Right: Switch between connection modes (when idle)
 * - Single Tap: Start/Stop connection
 * 
 * Note: Double tap exits the app (system behavior)
 */
@Composable
fun SimpleGlassesScreen(
    status: GlassesStatus,
    selectedMode: GlassesConnectionMode,
    connectedDeviceName: String = "",
    onModeChange: (GlassesConnectionMode) -> Unit,
    onToggleConnection: () -> Unit,
    videoView: @Composable () -> Unit
) {
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val modes = GlassesConnectionMode.entries
    val currentIndex = modes.indexOf(selectedMode)
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(status) {
                // Only allow mode switching when idle
                if (status == GlassesStatus.IDLE) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            // Swipe threshold
                            if (dragOffset > 100f && currentIndex > 0) {
                                // Swipe right -> previous mode
                                onModeChange(modes[currentIndex - 1])
                            } else if (dragOffset < -100f && currentIndex < modes.size - 1) {
                                // Swipe left -> next mode
                                onModeChange(modes[currentIndex + 1])
                            }
                            dragOffset = 0f
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            dragOffset += dragAmount
                        }
                    )
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        onToggleConnection()
                    }
                )
            }
    ) {
        // Video display area (full screen background)
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            videoView()
        }
        
        // Overlay UI
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top: Status indicator
            StatusBar(
                status = status,
                connectedDeviceName = connectedDeviceName
            )
            
            Spacer(modifier = Modifier.weight(1f))
            
            // Bottom: Mode selector and hint
            BottomControls(
                status = status,
                selectedMode = selectedMode,
                modes = modes,
                currentIndex = currentIndex
            )
        }
    }
}

@Composable
private fun StatusBar(
    status: GlassesStatus,
    connectedDeviceName: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)
                )
            )
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // App title
        Text(
            text = stringResource(R.string.app_name),
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
        
        // Status badge
        StatusBadge(status = status, deviceName = connectedDeviceName)
    }
}

@Composable
private fun StatusBadge(
    status: GlassesStatus,
    deviceName: String
) {
    val (color, textResId, icon) = when (status) {
        GlassesStatus.IDLE -> Triple(Color.Gray, R.string.glasses_status_idle, Icons.Default.Stop)
        GlassesStatus.WAITING -> Triple(Color(0xFFFF9800), R.string.glasses_status_waiting, Icons.Default.Search)
        GlassesStatus.CONNECTED -> Triple(Color(0xFF4CAF50), R.string.glasses_status_connected, Icons.Default.Check)
        GlassesStatus.STREAMING -> Triple(Color(0xFF2196F3), R.string.glasses_status_streaming, Icons.Default.PlayArrow)
    }
    
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = color.copy(alpha = 0.3f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Animated indicator for waiting/streaming
            if (status == GlassesStatus.WAITING || status == GlassesStatus.STREAMING) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(color)
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            
            Text(
                text = if (status == GlassesStatus.CONNECTED && deviceName.isNotEmpty()) {
                    deviceName
                } else {
                    stringResource(textResId)
                },
                color = color,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun BottomControls(
    status: GlassesStatus,
    selectedMode: GlassesConnectionMode,
    modes: List<GlassesConnectionMode>,
    currentIndex: Int
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))
                )
            )
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Mode selector (only visible when idle)
        AnimatedVisibility(
            visible = status == GlassesStatus.IDLE,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it }
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Mode indicator dots
                Row(
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
                    modes.forEachIndexed { index, _ ->
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .size(if (index == currentIndex) 10.dp else 6.dp)
                                .clip(CircleShape)
                                .background(
                                    if (index == currentIndex) Color.White
                                    else Color.White.copy(alpha = 0.4f)
                                )
                        )
                    }
                }
                
                // Current mode display
                ModeCard(mode = selectedMode)
                
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
        
        // Action hint
        ActionHint(status = status)
    }
}

@Composable
private fun ModeCard(mode: GlassesConnectionMode) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color.White.copy(alpha = 0.15f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = mode.icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = stringResource(mode.labelResId),
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(mode.descriptionResId),
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun ActionHint(status: GlassesStatus) {
    val (textResId, icon) = when (status) {
        GlassesStatus.IDLE -> Pair(R.string.hint_tap_to_start, Icons.Default.TouchApp)
        GlassesStatus.WAITING -> Pair(R.string.hint_tap_to_cancel, Icons.Default.Cancel)
        GlassesStatus.CONNECTED, GlassesStatus.STREAMING -> Pair(R.string.hint_tap_to_disconnect, Icons.Default.LinkOff)
    }
    
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.6f),
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(textResId),
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )
        
        // Swipe hint when idle
        if (status == GlassesStatus.IDLE) {
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = "•",
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.width(16.dp))
            Icon(
                imageVector = Icons.Default.SwipeLeft,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.hint_swipe_to_switch),
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 14.sp
            )
        }
    }
}
