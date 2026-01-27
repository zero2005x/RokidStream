package com.rokid.stream.sender.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.rokid.stream.sender.R

/**
 * Transport mode options for video streaming
 */
enum class TransportMode {
    BLE_L2CAP,      // Standard Bluetooth L2CAP
    ROKID_BLE,      // Rokid-optimized Bluetooth
    WIFI_TCP        // WiFi TCP streaming
}

/**
 * Transport Mode Selection Dialog
 * 
 * Allows users to select between different transport protocols:
 * - BLE L2CAP: Low latency Bluetooth
 * - Rokid BLE: Optimized for Rokid glasses
 * - WiFi TCP: High bandwidth WiFi streaming
 */
@Composable
fun TransportModeDialog(
    currentMode: TransportMode,
    onModeSelected: (TransportMode) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Title
                Text(
                    text = stringResource(R.string.transport_mode_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                
                Text(
                    text = stringResource(R.string.transport_mode_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Transport Mode Options
                TransportModeOption(
                    icon = Icons.Default.Bluetooth,
                    title = stringResource(R.string.transport_ble_l2cap),
                    description = stringResource(R.string.transport_ble_l2cap_desc),
                    isSelected = currentMode == TransportMode.BLE_L2CAP,
                    onClick = { onModeSelected(TransportMode.BLE_L2CAP) }
                )
                
                TransportModeOption(
                    icon = Icons.AutoMirrored.Filled.BluetoothSearching,
                    title = stringResource(R.string.transport_rokid_ble),
                    description = stringResource(R.string.transport_rokid_ble_desc),
                    isSelected = currentMode == TransportMode.ROKID_BLE,
                    onClick = { onModeSelected(TransportMode.ROKID_BLE) }
                )
                
                TransportModeOption(
                    icon = Icons.Default.Wifi,
                    title = stringResource(R.string.transport_wifi),
                    description = stringResource(R.string.transport_wifi_desc),
                    isSelected = currentMode == TransportMode.WIFI_TCP,
                    onClick = { onModeSelected(TransportMode.WIFI_TCP) }
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Close button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.close))
                    }
                }
            }
        }
    }
}

@Composable
private fun TransportModeOption(
    icon: ImageVector,
    title: String,
    description: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    }
    
    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
    } else {
        MaterialTheme.colorScheme.surface
    }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            tint = if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        
        Spacer(modifier = Modifier.width(16.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
