package com.rezvani.mesh

import android.app.Application
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.rezvani.mesh.utils.DiagLogger
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*

class RezvanApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DiagLogger.init(this)

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashDossier(thread, throwable)
            } catch (_: Throwable) { }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrashDossier(thread: Thread, t: Throwable) {
        try {
            val sb = StringBuilder()
            sb.appendLine("=== REZVAN CRASH DOSSIER ===")
            sb.appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            sb.appendLine("Build: ${BuildConfig.GIT_SHA} (${BuildConfig.GIT_BRANCH})")
            sb.appendLine("Built: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(BuildConfig.BUILD_TIME))} UTC")
            sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            sb.appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            sb.appendLine("Thread: ${thread.name}")
            sb.appendLine()
            sb.appendLine("=== EXCEPTION ===")
            val sw = StringWriter()
            t.printStackTrace(PrintWriter(sw))
            sb.appendLine(sw.toString())
            sb.appendLine()
            sb.appendLine("=== LAST 200 DIAG ENTRIES ===")
            DiagLogger.entries.value.takeLast(200).forEach { sb.appendLine(it.formatted()) }

            val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val filename = "rezvan-crash-$ts-${BuildConfig.GIT_SHA}.txt"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                contentResolver.openOutputStream(it)?.use { os ->
                    os.write(sb.toString().toByteArray())
                    os.flush()
                }
            }
        } catch (_: Throwable) { }
    }
}
