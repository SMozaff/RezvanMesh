package com.rezvani.mesh

import android.app.Application
import android.os.Build
import com.rezvani.mesh.utils.DiagLogger
import java.io.File
import java.io.FileOutputStream
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
            sb.appendLine("(these contain peer NodeIds and truncated MAC addresses -- share with care)")
            DiagLogger.entries.value.takeLast(200).forEach { sb.appendLine(it.formatted()) }

            val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val filename = "rezvan-crash-$ts-${BuildConfig.GIT_SHA}.txt"

            // Written to app-scoped external storage rather than
            // `Environment.DIRECTORY_DOWNLOADS`.
            //
            // Downloads is user-visible, indexed by the media scanner, and on
            // older releases world-readable -- so an unattended crash was
            // silently depositing a device fingerprint, the git SHA, a stack
            // trace, and 200 lines of diagnostics containing peer NodeIds and
            // truncated MAC addresses into a shareable location, with no
            // indication to the user that it had happened. For a
            // privacy-oriented offline mesh app that is a disclosure bug, not
            // just untidiness.
            //
            // `getExternalFilesDir` is app-scoped: removed on uninstall, not
            // scanned into the media store, and still reachable over adb (or a
            // file manager) when someone actually wants to file a bug report.
            // `DiagLogger` already writes its rolling log to the same area.
            val dir = File(getExternalFilesDir(null), "crash").apply { mkdirs() }
            val file = File(dir, filename)
            FileOutputStream(file).use { os ->
                os.write(sb.toString().toByteArray())
                os.flush()
            }
            DiagLogger.err("APP", "Crash dossier written to ${file.absolutePath}")
        } catch (_: Throwable) { }
    }
}
