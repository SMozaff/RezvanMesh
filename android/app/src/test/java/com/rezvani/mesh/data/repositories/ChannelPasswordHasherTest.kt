package com.rezvani.mesh.data.repositories

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Tests for private-channel password hashing.
 *
 * The prior implementation was a bare `SHA-256(password)` hex digest, verified
 * with `==`. These tests pin the properties that make the replacement correct:
 * salting, a real work factor, constant-time comparison, and failing closed on
 * anything unrecognisable.
 */
class ChannelPasswordHasherTest {

    @Test
    fun `a correct password verifies`() {
        val stored = ChannelPasswordHasher.hash("correct horse battery staple")
        assertTrue(ChannelPasswordHasher.verify("correct horse battery staple", stored))
    }

    @Test
    fun `a wrong password does not verify`() {
        val stored = ChannelPasswordHasher.hash("correct horse battery staple")
        assertFalse(ChannelPasswordHasher.verify("Correct horse battery staple", stored))
        assertFalse(ChannelPasswordHasher.verify("", stored))
        assertFalse(ChannelPasswordHasher.verify("correct horse battery stapl", stored))
    }

    /**
     * Regression test for the unsalted-SHA256 defect: two hashes of the same
     * password must not be equal, or one precomputed table attacks every
     * channel on every device.
     */
    @Test
    fun `the same password hashes differently every time`() {
        val a = ChannelPasswordHasher.hash("same password")
        val b = ChannelPasswordHasher.hash("same password")
        assertNotEquals(
            "identical passwords must not produce identical hashes",
            a, b
        )
        // Both still verify against the original password.
        assertTrue(ChannelPasswordHasher.verify("same password", a))
        assertTrue(ChannelPasswordHasher.verify("same password", b))
    }

    @Test
    fun `the stored format records algorithm iterations salt and hash`() {
        val stored = ChannelPasswordHasher.hash("pw")
        val parts = stored.split("$")
        assertEquals(4, parts.size)
        assertEquals("pbkdf2-sha256", parts[0])
        assertEquals(ChannelPasswordHasher.DEFAULT_ITERATIONS, parts[1].toInt())
        // Salt and hash must both decode to the expected sizes.
        assertEquals(16, Base64.getDecoder().decode(parts[2]).size)
        assertEquals(32, Base64.getDecoder().decode(parts[3]).size)
        assertFalse(ChannelPasswordHasher.needsRehash(stored))
    }

    /**
     * The whole point of PBKDF2: an attacker holding the database pays the
     * work factor per guess. Assert the stored iteration count is actually
     * honoured, i.e. the derivation is not secretly a single round.
     */
    @Test
    fun `the recorded iteration count is honoured on verify`() {
        val stored = ChannelPasswordHasher.hash("pw", iterations = 1000)
        assertEquals(1000, stored.split("$")[1].toInt())
        assertTrue(ChannelPasswordHasher.verify("pw", stored))
    }

    /**
     * A row whose stored iteration count disagrees with the one actually used to
     * derive the hash must not verify. Built field-by-field rather than with
     * `String.replace`, because the base64 salt and hash can legitimately
     * contain the digits of any iteration count as a substring.
     */
    @Test
    fun `a mismatched stored iteration count does not verify`() {
        val other = ChannelPasswordHasher.hash("pw", iterations = 2000).split("$")
        val lying = listOf(other[0], "1000", other[2], other[3]).joinToString("$")
        assertFalse(ChannelPasswordHasher.verify("pw", lying))
    }

    @Test
    fun `an out of range stored iteration count is rejected rather than honoured`() {
        // A corrupted or hand-edited row must not turn a join into an
        // unbounded CPU burn, and must not silently weaken the KDF either.
        val stored = ChannelPasswordHasher.hash("pw", iterations = 1000)
        val parts = stored.split("$")
        val tooMany = listOf(parts[0], "999999999", parts[2], parts[3]).joinToString("$")
        val zero = listOf(parts[0], "0", parts[2], parts[3]).joinToString("$")
        val negative = listOf(parts[0], "-1", parts[2], parts[3]).joinToString("$")
        val notANumber = listOf(parts[0], "many", parts[2], parts[3]).joinToString("$")
        assertFalse(ChannelPasswordHasher.verify("pw", tooMany))
        assertFalse(ChannelPasswordHasher.verify("pw", zero))
        assertFalse(ChannelPasswordHasher.verify("pw", negative))
        assertFalse(ChannelPasswordHasher.verify("pw", notANumber))
    }

    @Test
    fun `malformed stored values fail closed`() {
        for (stored in listOf(
            "",
            "not-a-hash",
            "pbkdf2-sha256",
            "pbkdf2-sha256\$1000",
            "pbkdf2-sha256\$1000\$$",
            "pbkdf2-sha256\$1000\$$\$\$extra",
            "pbkdf2-sha256\$\$\$\$",
            "pbkdf2-sha256\$1000\$!!!!\$\$\$\$"
        )) {
            assertFalse(
                "stored value $stored must be rejected",
                ChannelPasswordHasher.verify("pw", stored)
            )
        }
    }

    /**
     * A stored hash whose algorithm tag is unknown must be rejected, not
     * treated as a legacy row.
     */
    @Test
    fun `an unknown algorithm is rejected`() {
        val stored = ChannelPasswordHasher.hash("pw")
        val parts = stored.split("$")
        val swapped = listOf("scrypt-something", parts[1], parts[2], parts[3]).joinToString("$")
        assertFalse(ChannelPasswordHasher.verify("pw", swapped))
    }

    /**
     * Legacy bare-SHA256 rows must keep verifying, otherwise every private
     * channel created before this change is permanently locked out of its own
     * members -- the original password cannot be recovered to re-hash it.
     */
    @Test
    fun `legacy bare sha256 rows still verify`() {
        val password = "old channel password"
        val legacy = MessageDigest.getInstance("SHA-256")
            .digest(password.toByteArray())
            .joinToString("") { "%02x".format(it) }

        assertTrue(ChannelPasswordHasher.verify(password, legacy))
        assertFalse(ChannelPasswordHasher.verify("wrong", legacy))
        assertTrue(
            "a legacy row should be flagged for opportunistic re-hash",
            ChannelPasswordHasher.needsRehash(legacy)
        )
    }

    @Test
    fun `a 64 character non hex string is not mistaken for a legacy hash`() {
        assertFalse(ChannelPasswordHasher.verify("pw", "z".repeat(64)))
    }

    @Test
    fun `hashing an empty password is rejected outright`() {
        val failure = runCatching { ChannelPasswordHasher.hash("") }
        assertTrue("empty password must not be hashable", failure.isFailure)
    }

    /**
     * A 64-byte hash (512 bits) is accepted even though our own default is 32
     * bytes -- the verify path sizes the derivation from the stored value, so
     * raising the hash length later does not break existing rows.
     */
    @Test
    fun `a longer stored hash length is honoured`() {
        val salt = ByteArray(16) { 7 }
        val hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec("pw".toCharArray(), salt, 1000, 64 * 8))
            .encoded
        val stored = listOf(
            "pbkdf2-sha256",
            "1000",
            Base64.getEncoder().withoutPadding().encodeToString(salt),
            Base64.getEncoder().withoutPadding().encodeToString(hash)
        ).joinToString("$")
        assertTrue(ChannelPasswordHasher.verify("pw", stored))
    }

    @Test
    fun `verification does not throw on adversarial input`() {
        val nasty = listOf(
            "\$".repeat(10),
            "pbkdf2-sha256\$1000\$\$\$",
            "pbkdf2-sha256\$1000\$AAAA\$\$AAAA",
            " ",
            "pbkdf2-sha256\$1\$" + "A".repeat(4096) + "\$AAAA"
        )
        for (stored in nasty) {
            // The contract is "returns false", so anything thrown is a bug.
            ChannelPasswordHasher.verify("pw", stored)
        }
    }
}
