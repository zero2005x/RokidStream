package com.rokid.stream.sender.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LogManager - Comprehensive logging utility for debugging
 * 
 * Features:
 * - In-memory log buffer with configurable size
 * - File-based persistent logging
 * - Log reading (all, filtered, by time range)
 * - Log deletion (full, partial, by age)
 * - Async file writing for performance
 * - Thread-safe operations
 * 
 * Usage:
 *   LogManager.init(context)
 *   LogManager.d("MyTag", "Debug message")
 *   LogManager.getLogs() // Get all logs
 *   LogManager.clearLogs() // Clear all logs
 */
object LogManager {
    
    private const val TAG = "LogManager"
    private const val LOG_FILE_NAME = "app_debug.log"
    private const val MAX_MEMORY_LOGS = 1000
    private const val MAX_FILE_SIZE_BYTES = 5 * 1024 * 1024 // 5MB
    
    // Log levels
    enum class Level(val priority: Int, val label: String) {
        VERBOSE(0, "V"),
        DEBUG(1, "D"),
        INFO(2, "I"),
        WARN(3, "W"),
        ERROR(4, "E")
    }
    
    // Log entry data class
    data class LogEntry(
        val timestamp: Long,
        val level: Level,
        val tag: String,
        val message: String,
        val threadName: String = Thread.currentThread().name
    ) {
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        
        fun format(): String {
            val time = dateFormat.format(Date(timestamp))
            return "$time ${level.label}/$tag [$threadName]: $message"
        }
        
        fun formatCompact(): String {
            val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(timestamp))
            return "$time ${level.label}/$tag: $message"
        }
    }
    
    // In-memory log buffer (thread-safe)
    private val memoryLogs = ConcurrentLinkedQueue<LogEntry>()
    
    // File writing executor (single thread for sequential writes)
    private val fileExecutor = Executors.newSingleThreadExecutor()
    
    // State
    private var context: Context? = null
    private var logFile: File? = null
    private val isInitialized = AtomicBoolean(false)
    private var minLogLevel = Level.VERBOSE
    private var enableFileLogging = true
    private var enableAndroidLog = true
    
    // Listeners for real-time log updates
    private val logListeners = mutableListOf<(LogEntry) -> Unit>()
    
    /**
     * Initialize the LogManager with application context
     * Call this in Application.onCreate() or Activity.onCreate()
     */
    fun init(ctx: Context, config: Config = Config()) {
        if (isInitialized.getAndSet(true)) {
            Log.w(TAG, "LogManager already initialized")
            return
        }
        
        context = ctx.applicationContext
        logFile = File(ctx.filesDir, LOG_FILE_NAME)
        minLogLevel = config.minLevel
        enableFileLogging = config.enableFileLogging
        enableAndroidLog = config.enableAndroidLog
        
        // Rotate log file if too large
        rotateLogFileIfNeeded()
        
        Log.i(TAG, "LogManager initialized. Log file: ${logFile?.absolutePath}")
    }
    
    /**
     * Configuration for LogManager
     */
    data class Config(
        val minLevel: Level = Level.VERBOSE,
        val enableFileLogging: Boolean = true,
        val enableAndroidLog: Boolean = true
    )
    
    // ==================== LOGGING METHODS ====================
    
    /**
     * Log verbose message
     */
    fun v(tag: String, message: String) = log(Level.VERBOSE, tag, message)
    
    /**
     * Log debug message
     */
    fun d(tag: String, message: String) = log(Level.DEBUG, tag, message)
    
    /**
     * Log info message
     */
    fun i(tag: String, message: String) = log(Level.INFO, tag, message)
    
    /**
     * Log warning message
     */
    fun w(tag: String, message: String) = log(Level.WARN, tag, message)
    
    /**
     * Log error message
     */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val fullMessage = if (throwable != null) {
            "$message\n${Log.getStackTraceString(throwable)}"
        } else {
            message
        }
        log(Level.ERROR, tag, fullMessage)
    }
    
    /**
     * Core logging method
     */
    private fun log(level: Level, tag: String, message: String) {
        if (level.priority < minLogLevel.priority) return
        
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message
        )
        
        // Add to memory buffer
        addToMemoryBuffer(entry)
        
        // Log to Android logcat
        if (enableAndroidLog) {
            logToAndroid(entry)
        }
        
        // Write to file asynchronously
        if (enableFileLogging) {
            writeToFileAsync(entry)
        }
        
        // Notify listeners
        notifyListeners(entry)
    }
    
    private fun addToMemoryBuffer(entry: LogEntry) {
        memoryLogs.add(entry)
        
        // Trim buffer if exceeds max size
        while (memoryLogs.size > MAX_MEMORY_LOGS) {
            memoryLogs.poll()
        }
    }
    
    private fun logToAndroid(entry: LogEntry) {
        when (entry.level) {
            Level.VERBOSE -> Log.v(entry.tag, entry.message)
            Level.DEBUG -> Log.d(entry.tag, entry.message)
            Level.INFO -> Log.i(entry.tag, entry.message)
            Level.WARN -> Log.w(entry.tag, entry.message)
            Level.ERROR -> Log.e(entry.tag, entry.message)
        }
    }
    
    private fun writeToFileAsync(entry: LogEntry) {
        val file = logFile ?: return
        
        fileExecutor.execute {
            try {
                FileWriter(file, true).use { writer ->
                    writer.append(entry.format())
                    writer.append("\n")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write log to file", e)
            }
        }
    }
    
    private fun notifyListeners(entry: LogEntry) {
        logListeners.forEach { listener ->
            try {
                listener(entry)
            } catch (e: Exception) {
                Log.e(TAG, "Error in log listener", e)
            }
        }
    }
    
    // ==================== LOG READING METHODS ====================
    
    /**
     * Get all logs from memory buffer
     */
    fun getLogs(): List<LogEntry> = memoryLogs.toList()
    
    /**
     * Get logs as formatted strings
     */
    fun getLogsFormatted(compact: Boolean = false): List<String> {
        return memoryLogs.map { if (compact) it.formatCompact() else it.format() }
    }
    
    /**
     * Get logs as single string (for display/export)
     */
    fun getLogsAsString(compact: Boolean = false): String {
        return getLogsFormatted(compact).joinToString("\n")
    }
    
    /**
     * Get logs filtered by tag
     */
    fun getLogsByTag(tag: String): List<LogEntry> {
        return memoryLogs.filter { it.tag == tag }
    }
    
    /**
     * Get logs filtered by level (and above)
     */
    fun getLogsByLevel(minLevel: Level): List<LogEntry> {
        return memoryLogs.filter { it.level.priority >= minLevel.priority }
    }
    
    /**
     * Get logs within time range
     */
    fun getLogsByTimeRange(startTime: Long, endTime: Long): List<LogEntry> {
        return memoryLogs.filter { it.timestamp in startTime..endTime }
    }
    
    /**
     * Get logs from last N minutes
     */
    fun getLogsFromLastMinutes(minutes: Int): List<LogEntry> {
        val cutoff = System.currentTimeMillis() - (minutes * 60 * 1000)
        return memoryLogs.filter { it.timestamp >= cutoff }
    }
    
    /**
     * Get last N logs
     */
    fun getLastLogs(count: Int): List<LogEntry> {
        return memoryLogs.toList().takeLast(count)
    }
    
    /**
     * Search logs by message content
     */
    fun searchLogs(query: String, ignoreCase: Boolean = true): List<LogEntry> {
        return memoryLogs.filter { 
            it.message.contains(query, ignoreCase) || it.tag.contains(query, ignoreCase)
        }
    }
    
    /**
     * Read logs from file (for persistent logs)
     */
    fun readLogsFromFile(): String {
        val file = logFile ?: return ""
        return try {
            if (file.exists()) file.readText() else ""
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read log file", e)
            ""
        }
    }
    
    /**
     * Get log file size in bytes
     */
    fun getLogFileSize(): Long {
        return logFile?.length() ?: 0
    }
    
    /**
     * Get log file size as human-readable string
     */
    fun getLogFileSizeFormatted(): String {
        val size = getLogFileSize()
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${size / 1024} KB"
            else -> "${size / (1024 * 1024)} MB"
        }
    }
    
    // ==================== LOG DELETION METHODS ====================
    
    /**
     * Clear all logs (memory and file)
     */
    fun clearAllLogs() {
        clearMemoryLogs()
        clearFileLog()
        Log.i(TAG, "All logs cleared")
    }
    
    /**
     * Clear only memory logs
     */
    fun clearMemoryLogs() {
        memoryLogs.clear()
    }
    
    /**
     * Clear only file log
     */
    fun clearFileLog() {
        val file = logFile ?: return
        fileExecutor.execute {
            try {
                if (file.exists()) {
                    file.delete()
                    file.createNewFile()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear log file", e)
            }
        }
    }
    
    /**
     * Delete logs older than specified time
     */
    fun deleteLogsOlderThan(timestamp: Long) {
        val iterator = memoryLogs.iterator()
        var deletedCount = 0
        while (iterator.hasNext()) {
            if (iterator.next().timestamp < timestamp) {
                iterator.remove()
                deletedCount++
            }
        }
        Log.d(TAG, "Deleted $deletedCount logs older than ${Date(timestamp)}")
    }
    
    /**
     * Delete logs older than N minutes
     */
    fun deleteLogsOlderThanMinutes(minutes: Int) {
        val cutoff = System.currentTimeMillis() - (minutes * 60 * 1000)
        deleteLogsOlderThan(cutoff)
    }
    
    /**
     * Delete logs older than N hours
     */
    fun deleteLogsOlderThanHours(hours: Int) {
        deleteLogsOlderThanMinutes(hours * 60)
    }
    
    /**
     * Delete logs by tag
     */
    fun deleteLogsByTag(tag: String) {
        val iterator = memoryLogs.iterator()
        var deletedCount = 0
        while (iterator.hasNext()) {
            if (iterator.next().tag == tag) {
                iterator.remove()
                deletedCount++
            }
        }
        Log.d(TAG, "Deleted $deletedCount logs with tag: $tag")
    }
    
    /**
     * Delete logs by level (and below)
     */
    fun deleteLogsByLevelAndBelow(maxLevel: Level) {
        val iterator = memoryLogs.iterator()
        var deletedCount = 0
        while (iterator.hasNext()) {
            if (iterator.next().level.priority <= maxLevel.priority) {
                iterator.remove()
                deletedCount++
            }
        }
        Log.d(TAG, "Deleted $deletedCount logs with level <= ${maxLevel.label}")
    }
    
    /**
     * Keep only the last N logs, delete the rest
     */
    fun trimLogsKeepLast(count: Int) {
        while (memoryLogs.size > count) {
            memoryLogs.poll()
        }
        Log.d(TAG, "Trimmed logs to $count entries")
    }
    
    // ==================== LOG EXPORT METHODS ====================
    
    /**
     * Export logs to a file
     */
    fun exportLogs(outputFile: File): Boolean {
        return try {
            PrintWriter(FileWriter(outputFile)).use { writer ->
                writer.println("=== RokidStream Debug Log Export ===")
                writer.println("Export Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
                writer.println("Total Entries: ${memoryLogs.size}")
                writer.println("=" .repeat(50))
                writer.println()
                
                memoryLogs.forEach { entry ->
                    writer.println(entry.format())
                }
            }
            Log.i(TAG, "Logs exported to: ${outputFile.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export logs", e)
            false
        }
    }
    
    /**
     * Get log statistics
     */
    fun getStats(): LogStats {
        val logs = memoryLogs.toList()
        return LogStats(
            totalCount = logs.size,
            verboseCount = logs.count { it.level == Level.VERBOSE },
            debugCount = logs.count { it.level == Level.DEBUG },
            infoCount = logs.count { it.level == Level.INFO },
            warnCount = logs.count { it.level == Level.WARN },
            errorCount = logs.count { it.level == Level.ERROR },
            oldestTimestamp = logs.minOfOrNull { it.timestamp },
            newestTimestamp = logs.maxOfOrNull { it.timestamp },
            fileSize = getLogFileSize()
        )
    }
    
    data class LogStats(
        val totalCount: Int,
        val verboseCount: Int,
        val debugCount: Int,
        val infoCount: Int,
        val warnCount: Int,
        val errorCount: Int,
        val oldestTimestamp: Long?,
        val newestTimestamp: Long?,
        val fileSize: Long
    ) {
        fun format(): String {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            return buildString {
                appendLine("=== Log Statistics ===")
                appendLine("Total: $totalCount")
                appendLine("  VERBOSE: $verboseCount")
                appendLine("  DEBUG: $debugCount")
                appendLine("  INFO: $infoCount")
                appendLine("  WARN: $warnCount")
                appendLine("  ERROR: $errorCount")
                oldestTimestamp?.let { appendLine("Oldest: ${dateFormat.format(Date(it))}") }
                newestTimestamp?.let { appendLine("Newest: ${dateFormat.format(Date(it))}") }
                appendLine("File Size: ${fileSize / 1024} KB")
            }
        }
    }
    
    // ==================== LISTENER METHODS ====================
    
    /**
     * Add a listener for real-time log updates
     */
    fun addLogListener(listener: (LogEntry) -> Unit) {
        logListeners.add(listener)
    }
    
    /**
     * Remove a log listener
     */
    fun removeLogListener(listener: (LogEntry) -> Unit) {
        logListeners.remove(listener)
    }
    
    /**
     * Clear all log listeners
     */
    fun clearLogListeners() {
        logListeners.clear()
    }
    
    // ==================== UTILITY METHODS ====================
    
    /**
     * Rotate log file if it exceeds max size
     */
    private fun rotateLogFileIfNeeded() {
        val file = logFile ?: return
        if (file.exists() && file.length() > MAX_FILE_SIZE_BYTES) {
            val backupFile = File(file.parent, "${LOG_FILE_NAME}.bak")
            file.renameTo(backupFile)
            file.createNewFile()
            Log.i(TAG, "Log file rotated. Backup: ${backupFile.absolutePath}")
        }
    }
    
    /**
     * Set minimum log level
     */
    fun setMinLevel(level: Level) {
        minLogLevel = level
    }
    
    /**
     * Enable/disable file logging
     */
    fun setFileLoggingEnabled(enabled: Boolean) {
        enableFileLogging = enabled
    }
    
    /**
     * Enable/disable Android logcat output
     */
    fun setAndroidLogEnabled(enabled: Boolean) {
        enableAndroidLog = enabled
    }
    
    /**
     * Check if LogManager is initialized
     */
    fun isInitialized(): Boolean = isInitialized.get()
    
    /**
     * Get log file path
     */
    fun getLogFilePath(): String? = logFile?.absolutePath
}
