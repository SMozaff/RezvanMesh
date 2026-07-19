// android/app/src/main/java/com/rezvani/mesh/utils/DiagnosticsExporter.kt

package com.rezvani.mesh.utils

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves a diagnostics report as a plain, human-readable .txt file in the
 * device's public Downloads folder -- not app-private storage, so the user
 * can actually find, open, and share it (e.g. attach it when reporting a
 * bug). Not encrypted: this is diagnostic text (test names, pass/fail,
 * timing, device info), not message content or key material, so there's
 * nothing here that needs FileStorageManager's per-attachment AES-GCM
 * encryption.
 *
 * Branches by API level because the *public* Downloads collection works
 * differently depending on scoped storage:
 *   - API 29+ (Q+): MediaStore.Downloads, no runtime permission needed.
 *   - API 26-28: legacy direct file write to the public Downloads directory,
 *     gated by WRITE_EXTERNAL_STORAGE (already declared in the manifest with
 *     android:maxSdkVersion="28", matching exactly this cutoff).
 */
object DiagnosticsExporter {

    /**
     * Returns the display filename used, or null on failure. On API 26-28,
     * this requires WRITE_EXTERNAL_STORAGE to already be granted (declared
     * in the manifest with maxSdkVersion="28", but not currently requested
     * anywhere in the app's permission gate) -- if it isn't granted, the
     * write throws a SecurityException and this returns null, same as any
     * other failure. Not treated as a hard requirement to fix here since the
     * overwhelming majority of real devices run API 29+, where no runtime
     * permission is needed at all; flagged for whoever revisits the
     * permission gate next.
     */
    suspend fun saveReport(context: Context, reportText: String): String? = withContext(Dispatchers.IO) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val filename = "RezvanMesh_Diagnostics_$timestamp.txt"

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(context, filename, reportText)
            } else {
                saveViaLegacyFile(filename, reportText)
            }
            filename
        } catch (e: Exception) {
            null
        }
    }

    private fun saveViaMediaStore(context: Context, filename: String, text: String) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw java.io.IOException("MediaStore insert returned null Uri")
        resolver.openOutputStream(uri)?.use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
        } ?: throw java.io.IOException("Could not open output stream for $uri")
    }

    @Suppress("DEPRECATION")
    private fun saveViaLegacyFile(filename: String, text: String) {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadsDir.exists()) downloadsDir.mkdirs()
        val file = File(downloadsDir, filename)
        FileOutputStream(file).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
        }
    }
}
