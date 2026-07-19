package com.rezvani.mesh.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.rezvani.mesh.backup.IdentityStorageException
import java.security.SecureRandom

/**
 * Single source of truth for the SQLCipher database passphrase.
 *
 * Generates a random 32-byte key on first run (SecureRandom, not a compile-time
 * constant) and persists it through Keystore-backed EncryptedSharedPreferences
 * -- the same storage pattern used for the identity seed in IdentityBackupHelper.
 * Every ViewModel / service that opens AppDatabase must go through
 * getOrCreateKey() instead of hardcoding a passphrase, so there is exactly one
 * key per install instead of one shared across every install of the app.
 *
 * On Keystore/MasterKey failure this throws the shared
 * [com.rezvani.mesh.backup.IdentityStorageException] (same type
 * IdentityBackupHelper throws) so callers can surface one consistent hard
 * error to the user instead of transparently storing the DB key unencrypted.
 */
object DbKeyProvider {
    private const val PREFS_FILE = "rezvan_secure_dbkey"
    private const val KEY_DB_PASSPHRASE = "db_passphrase"
    private const val KEY_LENGTH_BYTES = 32

    /**
     * Returns the per-install database passphrase, generating and persisting
     * a new random one on first call.
     *
     * @throws IdentityStorageException if Keystore-backed secure storage is
     *   unavailable on this device.
     */
    @Synchronized
    fun getOrCreateKey(context: Context): ByteArray {
        val prefs = getPrefs(context)
        val existing = prefs.getString(KEY_DB_PASSPHRASE, null)
        if (existing != null) {
            return try {
                Base64.decode(existing, Base64.NO_WRAP)
            } catch (e: Exception) {
                // Stored value is corrupt; regenerate rather than fail permanently.
                generateAndStore(prefs)
            }
        }
        return generateAndStore(prefs)
    }

    private fun generateAndStore(prefs: SharedPreferences): ByteArray {
        val key = ByteArray(KEY_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString(KEY_DB_PASSPHRASE, Base64.encodeToString(key, Base64.NO_WRAP))
            .commit()
        return key
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            throw IdentityStorageException(
                "Secure storage unavailable on this device -- cannot safely create or load the database key.",
                e
            )
        }
    }
}
