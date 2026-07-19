package com.rezvani.mesh.utils

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*

object CrashLogger {
    private const val TAG = "CrashLogger"
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext

        writeToDownloads(
            "rezvan_startup_${System.currentTimeMillis()}.txt",
            "=== REZVAN STARTUP ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())} ===\nApp launched.\n\n"
        )

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception", throwable)
            writeCrash(throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }

        Runtime.getRuntime().addShutdownHook(Thread {
            Log.w(TAG, "Process shutting down")
            writeToDownloads(
                "rezvan_shutdown_${System.currentTimeMillis()}.txt",
                "=== PROCESS SHUTDOWN ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())} ===\nPossible native crash or force close.\n\n"
            )
        })
    }

    private fun writeCrash(throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        writeToDownloads(
            "rezvan_crash_${System.currentTimeMillis()}.txt",
            "=== CRASH ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())} ===\n${sw}\n\n"
        )
    }

    private fun writeToDownloads(filename: String, content: String) {
        val ctx = appContext ?: return
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                ctx.contentResolver.openOutputStream(it)?.use { os ->
                    os.write(content.toByteArray())
                    os.flush()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "writeToDownloads failed", e)
        }
    }
}
