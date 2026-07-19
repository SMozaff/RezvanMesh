package com.rezvani.mesh.utils

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*

object DiagLogger {

    enum class Level { VERBOSE, INFO, WARN, ERROR }

    data class DiagEntry(
        val timestamp: Long,
        val sessionId: String,
        val tag: String,
        val level: Level,
        val message: String
    ) {
        fun formatted(): String {
            val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(timestamp))
            return "$ts [$sessionId] [$tag/${level.name}] $message"
        }
    }

    private val sessionId = UUID.randomUUID().toString().take(8)
    private var appContext: Context? = null

    private val _entries = MutableStateFlow<List<DiagEntry>>(emptyList())
    val entries: StateFlow<List<DiagEntry>> = _entries.asStateFlow()

    // Background writer
    private val writeChannel = Channel<DiagEntry>(capacity = 1024)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var logFile: File? = null

    /** Must be called once from Application.onCreate() */
    fun init(context: Context) {
        appContext = context.applicationContext

        val dir = File(context.getExternalFilesDir(null), "diag")
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        logFile = File(dir, "diag_${stamp}_$sessionId.txt")

        // Boot marker
        scope.launch {
            try {
                logFile?.appendText(
                    "===== SESSION $sessionId START at ${Date()} =====\n" +
                    "Build: ${android.os.Build.MODEL} / Android ${android.os.Build.VERSION.RELEASE}\n" +
                    "App PID: ${android.os.Process.myPid()}\n\n"
                )
            } catch (_: Throwable) {}
        }

        // Single background consumer – all writes serialized through one thread
        scope.launch {
            for (entry in writeChannel) {
                try {
                    logFile?.appendText("${entry.formatted()}\n")
                } catch (_: Throwable) {}
            }
        }
    }

    /** Primary logging method. Non‑blocking. */
    fun log(context: Context, tag: String, level: Level, msg: String) {
        val entry = DiagEntry(System.currentTimeMillis(), sessionId, tag, level, msg)
        _entries.value = (_entries.value + entry).takeLast(2000)
        writeChannel.trySend(entry)
    }

    /** Convenience overload for backward compatibility. */
    fun log(context: Context, msg: String) {
        log(context, "MAIN", Level.INFO, msg)
    }

    /** Log an error with optional throwable stack trace. */
    fun err(context: Context, tag: String, msg: String, t: Throwable? = null) {
        val full = if (t != null) {
            val sw = StringWriter()
            t.printStackTrace(PrintWriter(sw))
            "$msg\n${sw}"
        } else msg
        log(context, tag, Level.ERROR, full)
    }

    /** Static shortcuts – require init() to have been called. */
    fun ble(msg: String) {
        appContext?.let { log(it, "BLE", Level.INFO, msg) }
    }

    fun ble(msg: String, vararg fields: Pair<String, String>) {
        appContext?.let {
            val full = if (fields.isEmpty()) msg
                       else "$msg ${fields.joinToString(" ") { (k, v) -> "$k=$v" }}"
            log(it, "BLE", Level.INFO, full)
        }
    }

    fun rust(msg: String) {
        appContext?.let { log(it, "RUST", Level.INFO, msg) }
    }

    fun err(tag: String, msg: String, t: Throwable? = null) {
        appContext?.let { err(it, tag, msg, t) }
    }

    /** Returns the current log file path. */
    fun currentLogFile(): File? = logFile

    /** Returns full session log as a string. */
    fun dumpCurrentSession(): String {
        return logFile?.takeIf { it.exists() }?.readText() ?: "(no log file)"
    }

    /**
     * Export the current log into MediaStore Downloads as a *single* shareable file.
     * Call this from a "Share Diagnostics" button – never from hot paths.
     */
    fun exportToDownloads(context: Context): android.net.Uri? {
        val source = logFile ?: return null
        if (!source.exists()) return null

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,
                "rezvan_${source.nameWithoutExtension}.txt")
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = context.contentResolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
        ) ?: return null

        return try {
            context.contentResolver.openOutputStream(uri)?.use { os ->
                source.inputStream().use { it.copyTo(os) }
            }
            uri
        } catch (t: Throwable) {
            context.contentResolver.delete(uri, null, null)
            null
        }
    }
}