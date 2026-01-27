package com.rokid.stream.sender.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.rokid.stream.sender.R
import com.rokid.stream.sender.util.LogManager
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Log Manager Screen - View, export, and delete application logs
 * 
 * Features:
 * - View all logs with color-coded severity levels
 * - Filter logs by level (VERBOSE, DEBUG, INFO, WARN, ERROR)
 * - Search logs by keyword
 * - Export logs to file and share
 * - Delete all logs or clear by criteria
 * - Real-time log updates
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogManagerScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    
    // Initialize LogManager if needed
    LaunchedEffect(Unit) {
        if (!LogManager.isInitialized()) {
            LogManager.init(context)
        }
    }
    
    // Log state
    var logs by remember { mutableStateOf(LogManager.getLogs()) }
    var stats by remember { mutableStateOf(LogManager.getStats()) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedLevel by remember { mutableStateOf<LogManager.Level?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showFilterMenu by remember { mutableStateOf(false) }
    var autoScroll by remember { mutableStateOf(true) }
    
    // Refresh logs periodically
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            logs = LogManager.getLogs()
            stats = LogManager.getStats()
        }
    }
    
    // Filter logs based on search and level
    val filteredLogs = remember(logs, searchQuery, selectedLevel) {
        logs.filter { entry ->
            val matchesSearch = searchQuery.isEmpty() || 
                entry.message.contains(searchQuery, ignoreCase = true) ||
                entry.tag.contains(searchQuery, ignoreCase = true)
            val matchesLevel = selectedLevel == null || entry.level.priority >= selectedLevel!!.priority
            matchesSearch && matchesLevel
        }
    }
    
    // Auto-scroll to bottom when new logs arrive
    LaunchedEffect(filteredLogs.size, autoScroll) {
        if (autoScroll && filteredLogs.isNotEmpty()) {
            listState.animateScrollToItem(filteredLogs.size - 1)
        }
    }
    
    // Delete confirmation dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.log_delete_title)) },
            text = { Text(stringResource(R.string.log_delete_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        LogManager.clearAllLogs()
                        logs = LogManager.getLogs()
                        stats = LogManager.getStats()
                        showDeleteDialog = false
                        Toast.makeText(context, context.getString(R.string.log_deleted), Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text(stringResource(R.string.log_delete_all), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.log_manager_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    // Filter button
                    Box {
                        IconButton(onClick = { showFilterMenu = true }) {
                            Icon(
                                Icons.Default.FilterList,
                                contentDescription = "Filter",
                                tint = if (selectedLevel != null) MaterialTheme.colorScheme.primary 
                                       else MaterialTheme.colorScheme.onSurface
                            )
                        }
                        DropdownMenu(
                            expanded = showFilterMenu,
                            onDismissRequest = { showFilterMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.log_filter_all)) },
                                onClick = {
                                    selectedLevel = null
                                    showFilterMenu = false
                                },
                                leadingIcon = {
                                    if (selectedLevel == null) {
                                        Icon(Icons.Default.Check, contentDescription = null)
                                    }
                                }
                            )
                            HorizontalDivider()
                            LogManager.Level.entries.forEach { level ->
                                DropdownMenuItem(
                                    text = { 
                                        Text(
                                            "${level.label} - ${level.name}",
                                            color = getLevelColor(level)
                                        )
                                    },
                                    onClick = {
                                        selectedLevel = level
                                        showFilterMenu = false
                                    },
                                    leadingIcon = {
                                        if (selectedLevel == level) {
                                            Icon(Icons.Default.Check, contentDescription = null)
                                        }
                                    }
                                )
                            }
                        }
                    }
                    
                    // Export button
                    IconButton(
                        onClick = {
                            scope.launch {
                                exportLogs(context, logs)
                            }
                        }
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Export")
                    }
                    
                    // Delete button
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        bottomBar = {
            // Stats bar
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${stringResource(R.string.log_total)}: ${stats.totalCount}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatChip("E", stats.errorCount, Color(0xFFE53935))
                        StatChip("W", stats.warnCount, Color(0xFFFFA726))
                        StatChip("I", stats.infoCount, Color(0xFF43A047))
                        StatChip("D", stats.debugCount, Color(0xFF1E88E5))
                    }
                    // Auto-scroll toggle
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Auto",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Switch(
                            checked = autoScroll,
                            onCheckedChange = { autoScroll = it },
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.log_search)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors()
            )
            
            // Log list
            if (filteredLogs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Description,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Text(
                            text = stringResource(R.string.log_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    items(filteredLogs) { entry ->
                        LogEntryItem(entry = entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogEntryItem(entry: LogManager.LogEntry) {
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
    val levelColor = getLevelColor(entry.level)
    
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        color = levelColor.copy(alpha = 0.1f),
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Level indicator
            Text(
                text = entry.level.label,
                fontWeight = FontWeight.Bold,
                color = levelColor,
                fontSize = 12.sp,
                modifier = Modifier.width(16.dp)
            )
            
            Spacer(modifier = Modifier.width(4.dp))
            
            // Timestamp
            Text(
                text = dateFormat.format(Date(entry.timestamp)),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.width(8.dp))
            
            // Tag
            Text(
                text = entry.tag,
                fontWeight = FontWeight.Medium,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.widthIn(max = 100.dp)
            )
            
            Spacer(modifier = Modifier.width(8.dp))
            
            // Message
            Text(
                text = entry.message,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun StatChip(label: String, count: Int, color: Color) {
    Surface(
        color = color.copy(alpha = 0.2f),
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = "$label:$count",
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

private fun getLevelColor(level: LogManager.Level): Color {
    return when (level) {
        LogManager.Level.VERBOSE -> Color(0xFF9E9E9E)
        LogManager.Level.DEBUG -> Color(0xFF1E88E5)
        LogManager.Level.INFO -> Color(0xFF43A047)
        LogManager.Level.WARN -> Color(0xFFFFA726)
        LogManager.Level.ERROR -> Color(0xFFE53935)
    }
}

private fun exportLogs(context: android.content.Context, logs: List<LogManager.LogEntry>) {
    try {
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val timestamp = dateFormat.format(Date())
        val fileName = "rokidstream_logs_$timestamp.txt"
        val file = File(context.cacheDir, fileName)
        
        LogManager.exportLogs(file)
        
        // Share file using FileProvider
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        
        context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.log_export_title)))
    } catch (e: Exception) {
        Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
