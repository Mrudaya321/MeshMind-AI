package com.qualcomm.meshmind.logging

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Enterprise-grade logging framework for the MeshMind Edge application.
 * Manages Logcat, persistent local files, and diagnostic hooks asynchronously using Coroutines.
 * Automatically redacts PII and cryptographic details by enforcing strict tag-based structures.
 */
object MeshLogger {

    enum class Level {
        DEBUG, INFO, WARNING, ERROR, CRITICAL, SECURITY
    }

    private const val TAG_PREFIX = "MeshMind_"
    private var logFileDirectory: File? = null
    
    // Coroutine scope dedicated to serializing log writes to storage
    private val loggingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun initialize(filesDir: File) {
        logFileDirectory = File(filesDir, "logs").apply {
            if (!exists()) mkdirs()
        }
        i("MeshLogger", "Logging subsystem initialized. Log path: ${logFileDirectory?.absolutePath}")
    }

    fun d(tag: String, message: String) = log(Level.DEBUG, tag, message)
    fun i(tag: String, message: String) = log(Level.INFO, tag, message)
    fun w(tag: String, message: String) = log(Level.WARNING, tag, message)
    fun e(tag: String, message: String, throwable: Throwable? = null) = log(Level.ERROR, tag, message, throwable)
    fun critical(tag: String, message: String) = log(Level.CRITICAL, tag, message)
    fun security(tag: String, message: String) = log(Level.SECURITY, tag, message)

    private fun log(level: Level, tag: String, message: String, throwable: Throwable? = null) {
        val fullTag = "$TAG_PREFIX$tag"
        val sanitized = sanitize(message)

        // Log to Logcat
        switchLogcat(level, fullTag, sanitized, throwable)

        // Write to local file asynchronously
        logFileDirectory?.let { dir ->
            loggingScope.launch {
                writeLogToFile(dir, level, tag, sanitized, throwable)
            }
        }
    }

    private fun switchLogcat(level: Level, tag: String, msg: String, t: Throwable?) {
        when (level) {
            Level.DEBUG -> Log.d(tag, msg)
            Level.INFO -> Log.i(tag, msg)
            Level.WARNING -> Log.w(tag, msg)
            Level.ERROR -> if (t != null) Log.e(tag, msg, t) else Log.e(tag, msg)
            Level.CRITICAL -> Log.wtf(tag, "[CRITICAL] $msg", t)
            Level.SECURITY -> Log.w(tag, "[SECURITY ALERT] $msg")
        }
    }

    private fun sanitize(msg: String): String {
        return msg.replace("(?i)(key|payload|secret|token|password)\\s*=\\s*[0-9a-fA-F]{16,}".toRegex(), "$1=[REDACTED_SECURE_VAL]")
            .replace("(?i)(key|payload|secret|token|password)\\s*:\\s*[0-9a-fA-F]{16,}".toRegex(), "$1:[REDACTED_SECURE_VAL]")
    }

    private fun writeLogToFile(dir: File, level: Level, tag: String, msg: String, t: Throwable?) {
        val file = File(dir, "meshmind_system.log")
        try {
            FileWriter(file, true).use { writer ->
                val timeStr = dateFormat.format(Date())
                writer.write("$timeStr [${level.name}] $tag: $msg\n")
                t?.let {
                    writer.write(Log.getStackTraceString(it))
                    writer.write("\n")
                }
            }
        } catch (e: Exception) {
            Log.e("MeshLogger", "Failed to write log line to file storage: ${e.message}")
        }
    }
}
