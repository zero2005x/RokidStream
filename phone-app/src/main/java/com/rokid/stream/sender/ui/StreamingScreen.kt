package com.rokid.stream.sender.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.view.PreviewView
import android.view.SurfaceView
import com.rokid.stream.sender.R

/**
 * Streaming direction options
 */
enum class StreamDirection(val label: String, val icon: ImageVector) {
    PHONE_TO_GLASSES("Phone → Glasses", Icons.Default.PhoneAndroid),
    GLASSES_TO_PHONE("Glasses → Phone", Icons.Default.Visibility)
}

/**
 * Connection mode options
 */
enum class ConnectionMode(val label: String, val icon: ImageVector, val description: String) {
    L2CAP("BLE L2CAP", Icons.Default.Bluetooth, "Low latency Bluetooth"),
    WIFI("WiFi TCP", Icons.Default.Wifi, "High bandwidth WiFi"),
    ROKID_SDK("Rokid SDK", Icons.Default.Cloud, "Official ARTC protocol")
}

/**
 * Connection status
 */
enum class ConnectionStatus {
    DISCONNECTED, CONNECTING, CONNECTED, STREAMING
}

/**
 * Main streaming screen composable for Sender (Phone) app
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamingScreen(
    connectionStatus: ConnectionStatus,
    selectedDirection: StreamDirection,
    selectedMode: ConnectionMode,
    logMessages: List<String>,
    onDirectionChange: (StreamDirection) -> Unit,
    onModeChange: (ConnectionMode) -> Unit,
    onConnectClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    onSettingsClick: () -> Unit = {},
    cameraPreview: @Composable () -> Unit,
    receivedVideoView: @Composable () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        stringResource(R.string.app_name),
                        fontWeight = FontWeight.Bold
                    ) 
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    // Connection status indicator
                    ConnectionStatusBadge(status = connectionStatus)
                    // Settings button
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = stringResource(R.string.nav_settings),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            // Video Preview Section
            VideoPreviewSection(
                selectedDirection = selectedDirection,
                connectionStatus = connectionStatus,
                cameraPreview = cameraPreview,
                receivedVideoView = receivedVideoView
            )
            
            // Stream Direction Selection
            StreamDirectionSection(
                selectedDirection = selectedDirection,
                enabled = connectionStatus == ConnectionStatus.DISCONNECTED,
                onDirectionChange = onDirectionChange
            )
            
            // Connection Mode Selection
            ConnectionModeSection(
                selectedMode = selectedMode,
                enabled = connectionStatus == ConnectionStatus.DISCONNECTED,
                onModeChange = onModeChange
            )
            
            // Connect Button
            ConnectButtonSection(
                connectionStatus = connectionStatus,
                selectedMode = selectedMode,
                onConnectClick = onConnectClick,
                onDisconnectClick = onDisconnectClick
            )
            
            // Log Section
            LogSection(logMessages = logMessages)
        }
    }
}

@Composable
fun ConnectionStatusBadge(status: ConnectionStatus) {
    val (color, text) = when (status) {
        ConnectionStatus.DISCONNECTED -> Pair(MaterialTheme.colorScheme.outline, stringResource(R.string.status_offline))
        ConnectionStatus.CONNECTING -> Pair(MaterialTheme.colorScheme.tertiary, stringResource(R.string.status_connecting))
        ConnectionStatus.CONNECTED -> Pair(Color(0xFF4CAF50), stringResource(R.string.status_connected))
        ConnectionStatus.STREAMING -> Pair(Color(0xFF2196F3), stringResource(R.string.status_streaming))
    }
    
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = color.copy(alpha = 0.2f),
        modifier = Modifier.padding(horizontal = 8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = color
            )
        }
    }
}

@Composable
fun VideoPreviewSection(
    selectedDirection: StreamDirection,
    connectionStatus: ConnectionStatus,
    cameraPreview: @Composable () -> Unit,
    receivedVideoView: @Composable () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Text(
                text = stringResource(R.string.title_video_preview),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Camera Preview (Sending)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    if (selectedDirection == StreamDirection.PHONE_TO_GLASSES) {
                        cameraPreview()
                    }
                    
                    // Label overlay
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(8.dp),
                        shape = RoundedCornerShape(4.dp),
                        color = Color.Black.copy(alpha = 0.6f)
                    ) {
                        Text(
                            text = stringResource(R.string.label_send),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White
                        )
                    }
                }
                
                // Received Video
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    if (selectedDirection == StreamDirection.GLASSES_TO_PHONE) {
                        receivedVideoView()
                    }
                    
                    // Label overlay
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(8.dp),
                        shape = RoundedCornerShape(4.dp),
                        color = Color.Black.copy(alpha = 0.6f)
                    ) {
                        Text(
                            text = stringResource(R.string.label_receive),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StreamDirectionSection(
    selectedDirection: StreamDirection,
    enabled: Boolean,
    onDirectionChange: (StreamDirection) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Stream,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.title_stream_direction),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            
            StreamDirection.entries.forEach { direction ->
                DirectionOption(
                    direction = direction,
                    selected = selectedDirection == direction,
                    enabled = enabled,
                    onClick = { onDirectionChange(direction) }
                )
            }
        }
    }
}

@Composable
fun DirectionOption(
    direction: StreamDirection,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick
            ),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) 
            MaterialTheme.colorScheme.primaryContainer 
        else 
            MaterialTheme.colorScheme.surface,
        tonalElevation = if (selected) 2.dp else 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected,
                onClick = null,
                enabled = enabled
            )
            Spacer(modifier = Modifier.width(12.dp))
            Icon(
                imageVector = direction.icon,
                contentDescription = null,
                tint = if (selected) 
                    MaterialTheme.colorScheme.primary 
                else 
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = direction.label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) 
                    MaterialTheme.colorScheme.onSurface 
                else 
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
fun ConnectionModeSection(
    selectedMode: ConnectionMode,
    enabled: Boolean,
    onModeChange: (ConnectionMode) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.SettingsInputAntenna,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.title_connection_mode),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            
            ConnectionMode.entries.forEach { mode ->
                ModeOption(
                    mode = mode,
                    selected = selectedMode == mode,
                    enabled = enabled,
                    onClick = { onModeChange(mode) }
                )
            }
        }
    }
}

@Composable
fun ModeOption(
    mode: ConnectionMode,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick
            ),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) 
            MaterialTheme.colorScheme.secondaryContainer 
        else 
            MaterialTheme.colorScheme.surface,
        tonalElevation = if (selected) 2.dp else 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected,
                onClick = null,
                enabled = enabled
            )
            Spacer(modifier = Modifier.width(12.dp))
            Icon(
                imageVector = mode.icon,
                contentDescription = null,
                tint = if (selected) 
                    MaterialTheme.colorScheme.secondary 
                else 
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = mode.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (enabled) 
                        MaterialTheme.colorScheme.onSurface 
                    else 
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
                Text(
                    text = mode.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun ConnectButtonSection(
    connectionStatus: ConnectionStatus,
    selectedMode: ConnectionMode,
    onConnectClick: () -> Unit,
    onDisconnectClick: () -> Unit
) {
    val isConnected = connectionStatus == ConnectionStatus.CONNECTED || 
                      connectionStatus == ConnectionStatus.STREAMING
    val isConnecting = connectionStatus == ConnectionStatus.CONNECTING
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        if (isConnected) {
            Button(
                onClick = onDisconnectClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.Stop, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.btn_stop_streaming), style = MaterialTheme.typography.titleMedium)
            }
        } else {
            Button(
                onClick = onConnectClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                enabled = !isConnecting,
                shape = RoundedCornerShape(16.dp)
            ) {
                if (isConnecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.btn_connecting), style = MaterialTheme.typography.titleMedium)
                } else {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.btn_connect_stream, selectedMode.label),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
    }
}

@Composable
fun LogSection(logMessages: List<String>) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.title_logs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF1E1E1E)
            ) {
                Column(
                    modifier = Modifier
                        .padding(8.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    logMessages.takeLast(20).forEach { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF00FF00),
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}
