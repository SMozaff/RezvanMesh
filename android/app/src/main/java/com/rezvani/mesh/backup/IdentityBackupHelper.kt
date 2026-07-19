package com.rezvani.mesh.backup

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

/**
 * Thrown when Keystore-backed secure storage cannot be created or accessed.
 * Callers MUST surface this to the user (e.g. a blocking error screen) rather
 * than silently falling back to storing key material -- identity seed or
 * database passphrase -- unencrypted. Keystore failure is rare enough on
 * real devices that a hard stop here is the correct tradeoff for an app whose
 * whole value proposition is protecting this exact key material.
 */
class IdentityStorageException(message: String, cause: Throwable? = null) : Exception(message, cause)

object IdentityBackupHelper {
    private const val PREFS_FILE = "rezvan_secure_identity"
    private const val KEY_SEED = "identity_seed"

    fun generateSeed(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    /** @throws IdentityStorageException if Keystore-backed secure storage is unavailable. */
    fun saveSeed(context: Context, seed: ByteArray) {
        val prefs = getPrefs(context)
        prefs.edit().putString(KEY_SEED, Base64.encodeToString(seed, Base64.NO_WRAP)).commit()
    }

    /** @throws IdentityStorageException if Keystore-backed secure storage is unavailable. */
    fun loadSeed(context: Context): ByteArray? {
        val prefs = getPrefs(context)
        val encoded = prefs.getString(KEY_SEED, null) ?: return null
        return try {
            Base64.decode(encoded, Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    /** @throws IdentityStorageException if Keystore-backed secure storage is unavailable. */
    fun hasIdentity(context: Context): Boolean = loadSeed(context) != null

    // Node ID is intentionally NOT computed here. The canonical formula is
    // SHA-256(Ed25519 public key)[0:8] (rezvan_common::compute_node_id),
    // computed once inside the Rust engine from the seed-derived identity
    // keypair. Get it via MeshCore.nativeGetNodeId(enginePtr) instead of
    // recomputing it in Kotlin -- a previous version of this file hashed the
    // raw *seed* directly, which silently produced a different ID than the
    // one the engine actually puts in every packet's `originator` field
    // (security audit finding #8).

    /**
     * Opens (or creates) the Keystore-backed prefs file used to store the
     * identity seed. On MasterKey/Keystore failure this throws
     * [IdentityStorageException] rather than silently downgrading to
     * unencrypted storage -- there is no plaintext fallback path.
     */
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
                "Secure storage unavailable on this device -- cannot safely create or load your identity.",
                e
            )
        }
    }
}
