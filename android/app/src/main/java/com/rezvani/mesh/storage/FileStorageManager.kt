package com.rezvani.mesh.storage

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Manages encrypted file storage for attachments (images, voice messages, files).
 *
 * Each file is encrypted with a unique random AES-256-GCM key.
 * The key is stored in the database associated with the message ID.
 */
class FileStorageManager(private val context: Context) {
    private val attachmentsDir: File by lazy {
        File(context.filesDir, "attachments").apply {
            if (!exists()) mkdirs()
        }
    }

    companion object {
        private const val TAG = "FileStorageManager"
        private const val GCM_NONCE_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
        private const val AES_KEY_SIZE = 32 // 256 bits
    }

    /**
     * Encrypts and saves a file to disk.
     *
     * @param data Raw file data.
     * @param messageId Associated message ID (used for filename).
     * @return Pair of (file path, encryption key) where key should be stored in database.
     */
    suspend fun saveFile(data: ByteArray, messageId: String): Pair<String, ByteArray> = withContext(Dispatchers.IO) {
        // Generate random file key
        val fileKey = ByteArray(AES_KEY_SIZE)
        SecureRandom().nextBytes(fileKey)

        // Generate random nonce
        val nonce = ByteArray(GCM_NONCE_LENGTH)
        SecureRandom().nextBytes(nonce)

        // Encrypt data
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(fileKey, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
        val encrypted = cipher.doFinal(data)

        // Write to file: [nonce][ciphertext]
        val file = File(attachmentsDir, "$messageId.enc")
        FileOutputStream(file).use { fos ->
            fos.write(nonce)
            fos.write(encrypted)
        }

        Log.i(TAG, "Saved encrypted file: ${file.absolutePath}, size=${data.size}")
        Pair(file.absolutePath, fileKey)
    }

    /**
     * Reads and decrypts a file from disk.
     *
     * @param filePath Path to the encrypted file.
     * @param fileKey AES-256 key used to encrypt the file.
     * @return Decrypted file data, or null if decryption fails.
     */
    suspend fun readFile(filePath: String, fileKey: ByteArray): ByteArray? = withContext(Dispatchers.IO) {
        val file = File(filePath)
        if (!file.exists()) {
            Log.w(TAG, "File not found: $filePath")
            return@withContext null
        }

        return@withContext try {
            FileInputStream(file).use { fis ->
                // Read nonce
                val nonce = ByteArray(GCM_NONCE_LENGTH)
                fis.read(nonce)

                // Read ciphertext
                val encrypted = fis.readBytes()

                // Decrypt
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                val keySpec = SecretKeySpec(fileKey, "AES")
                val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, nonce)
                cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)

                cipher.doFinal(encrypted)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt file: $filePath", e)
            null
        }
    }

    /**
     * Deletes a file from disk.
     */
    suspend fun deleteFile(filePath: String): Boolean = withContext(Dispatchers.IO) {
        val file = File(filePath)
        if (file.exists()) {
            file.delete()
        } else {
            true
        }
    }

    /**
     * Gets the total size of all attachments.
     */
    suspend fun getTotalStorageUsed(): Long = withContext(Dispatchers.IO) {
        attachmentsDir.walkTopDown()
            .filter { it.isFile }
            .map { it.length() }
            .sum()
    }

    /**
     * Deletes files older than the specified timestamp.
     */
    suspend fun deleteFilesOlderThan(timestamp: Long): Int = withContext(Dispatchers.IO) {
        var deletedCount = 0
        attachmentsDir.listFiles()?.forEach { file ->
            if (file.lastModified() < timestamp) {
                if (file.delete()) deletedCount++
            }
        }
        Log.i(TAG, "Deleted $deletedCount old files")
        deletedCount
    }

    /**
     * Formats a file size in bytes to a human-readable string.
     */
    fun formatFileSize(size: Long): String {
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> String.format("%.1f KB", size / 1024.0)
            size < 1024 * 1024 * 1024 -> String.format("%.1f MB", size / (1024.0 * 1024))
            else -> String.format("%.2f GB", size / (1024.0 * 1024 * 1024))
        }
    }
}
