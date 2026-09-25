package com.rezvani.mesh.data.repositories

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Password hashing for private channels.
 *
 * Extracted out of [ChannelRepository] for two reasons: the repository needs a
 * live Room database and an Android `Context` so it cannot be unit tested, and
 * this is the security-critical half of the join decision and deserves tests
 * of its own. It is also pure logic with no Android dependencies, so it runs
 * unchanged on the JVM and under instrumentation.
 *
 * # Why not SHA-256
 *
 * The original implementation was a bare `SHA-256(password)` hex digest. For a
 * value people are told to pick and reuse, that is the textbook wrong choice:
 *
 * * No salt, so identical passwords produce identical digests across every
 *   channel and every device. A single precomputed table attacks all of them.
 * * Far too fast, so a stolen database can be brute-forced at billions of
 *   guesses per second on commodity hardware.
 *
 * PBKDF2-HMAC-SHA256 fixes both and is available on every supported API level
 * through `SecretKeyFactory`, so there is no reason to accept the weaker
 * primitive.
 *
 * # Format
 *
 * ```text
 * pbkdf2-sha256$<iterations>$<base64 salt>$<base64 hash>
 * ```
 *
 * The iteration count lives in the string so it can be raised in a future
 * release without invalidating existing rows -- old hashes stay verifiable under
 * their own recorded cost. The separator is `$`, which cannot appear in any of
 * the encoded fields.
 */
object ChannelPasswordHasher {

    /**
     * OWASP's 2023 recommendation for PBKDF2-HMAC-SHA256.
     *
     * This is deliberately expensive to compute. The cost lands on channel
     * creation and channel join -- both rare, user-initiated, and off the hot
     * path -- while the benefit is that a leaked database is not cheaply
     * crackable offline.
     */
    const val DEFAULT_ITERATIONS = 210_000

    /**
     * Ceiling accepted when reading a stored iteration count.
     *
     * The count lives in our own database, so it is not an attacker-controlled
     * path today. It still costs nothing to bound: a corrupted or hand-edited
     * row should fail closed rather than turn a channel join into an
     * unbounded CPU burn.
     */
    const val MAX_ITERATIONS = 1_000_000

    private const val ALGORITHM = "pbkdf2-sha256"
    private const val SALT_BYTES = 16
    private const val HASH_BYTES = 32
    private const val SEPARATOR = "$"

    /**
     * Hash [password] with a fresh random salt.
     *
     * Never returns the same string twice for the same password, by design.
     */
    fun hash(password: String, iterations: Int = DEFAULT_ITERATIONS): String {
        require(password.isNotEmpty()) { "password must not be empty" }
        require(iterations in 1..MAX_ITERATIONS) { "iteration count out of range" }
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val hash = pbkdf2(password, salt, iterations, HASH_BYTES)
        return listOf(
            ALGORITHM,
            iterations.toString(),
            Base64.getEncoder().withoutPadding().encodeToString(salt),
            Base64.getEncoder().withoutPadding().encodeToString(hash)
        ).joinToString(SEPARATOR)
    }

    /**
     * Check [password] against a stored [stored] hash.
     *
     * Returns false -- never throws -- for a malformed, truncated, or
     * unrecognised stored value. Failing closed matters here: a corrupted row
     * must deny access, not crash the join or, worse, be treated as a match.
     */
    fun verify(password: String, stored: String): Boolean {
        if (password.isEmpty()) return false

        val parts = stored.split(SEPARATOR)
        return when {
            // Current format.
            parts.size == 4 && parts[0] == ALGORITHM -> {
                val iterations = parts[1].toIntOrNull() ?: return false
                if (iterations !in 1..MAX_ITERATIONS) return false
                val salt = decode(parts[2]) ?: return false
                val expected = decode(parts[3]) ?: return false
                if (expected.isEmpty()) return false
                val actual = pbkdf2(password, salt, iterations, expected.size)
                MessageDigest.isEqual(actual, expected)
            }
            // Legacy bare hex SHA-256, still accepted.
            //
            // Kept for compatibility only. The stored value is a one-way hash,
            // so the original password cannot be recovered to re-hash it, and
            // rejecting it would permanently lock every existing private
            // channel out of its own members. Any successful legacy verification
            // should be followed by an opportunistic re-hash on the next join.
            parts.size == 1 && parts[0].length == 64 && parts[0].all { it.isDigit() || it in 'a'..'f' } -> {
                MessageDigest.isEqual(
                    legacySha256Hex(password).toByteArray(Charsets.UTF_8),
                    parts[0].toByteArray(Charsets.UTF_8)
                )
            }
            else -> false
        }
    }

    /** True when [stored] uses the current format, so a caller can re-hash. */
    fun needsRehash(stored: String): Boolean =
        !stored.startsWith("$ALGORITHM$SEPARATOR")

    private fun decode(value: String): ByteArray? = runCatching {
        Base64.getDecoder().decode(value)
    }.getOrNull()

    private fun pbkdf2(password: String, salt: ByteArray, iterations: Int, keyLength: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, keyLength * 8)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            // Don't leave the password material sitting in the spec's char array.
            spec.clearPassword()
        }
    }

    private fun legacySha256Hex(password: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(password.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
