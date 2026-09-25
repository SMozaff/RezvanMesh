package com.rezvani.mesh.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the channel-ID signedness defect.
 *
 * `ChannelRepository.generateChannelId` applied `and 0x7FFFFFFF` only to the
 * final byte, because Kotlin's infix `and` binds looser than `or`. The
 * assembled 32-bit value therefore kept whatever sign bit byte 0 happened to
 * carry, so roughly half of all channel names produced a *negative* Int.
 *
 * Negative IDs are not merely cosmetic: `ChannelQrCodec` round-trips them into
 * QR payloads, and the Rust JNI layer converts with `channel_id as u32`, which
 * for a negative Int reinterprets the bit pattern instead of converting it.
 */
class ChannelQrCodecTest {

    private val key = ByteArray(32) { it.toByte() }
    private val keyHex = key.joinToString("") { "%02x".format(it) }

    @Test
    fun `encode then decode round-trips a channel id`() {
        val encoded = ChannelQrCodec.encode(1_234_567, key)
        val decoded = ChannelQrCodec.decode(encoded)
        assertNotNull(decoded)
        assertEquals(1_234_567, decoded!!.channelId)
        assertTrue(key.contentEquals(decoded.key))
    }

    @Test
    fun `encode round-trips the largest positive channel id`() {
        val max = Int.MAX_VALUE
        val decoded = ChannelQrCodec.decode(ChannelQrCodec.encode(max, key))
        assertNotNull(decoded)
        assertEquals(max, decoded!!.channelId)
    }

    @Test
    fun `decode rejects a negative channel id`() {
        // A QR payload produced by a build with the precedence bug, or by any
        // other client that doesn't constrain the sign bit.
        val negative = ChannelQrCodec.encode(-1, key)
        assertNull(
            "negative channel IDs must not be accepted",
            ChannelQrCodec.decode(negative)
        )
    }

    @Test
    fun `decode rejects every negative id, not just -1`() {
        for (id in listOf(-1, -2, Int.MIN_VALUE, Int.MIN_VALUE + 1, -1_234_567)) {
            val payload = "rzvch1:$id:$keyHex"
            assertNull("expected $id to be rejected", ChannelQrCodec.decode(payload))
        }
    }

    @Test
    fun `decode accepts zero as a boundary`() {
        // Zero is not a valid channel id in practice, but it is non-negative
        // and therefore outside this validator's remit; the repo layer is what
        // rejects it.
        assertEquals(0, ChannelQrCodec.decode("rzvch1:0:$keyHex")?.channelId)
    }

    @Test
    fun `decode rejects a contact QR code`() {
        // A bare 16-char hex NodeId is a contact code, not a channel code.
        assertNull(ChannelQrCodec.decode("a1b2c3d4e5f60718"))
    }

    @Test
    fun `decode rejects malformed payloads`() {
        assertNull(ChannelQrCodec.decode(""))
        assertNull(ChannelQrCodec.decode("rzvch1:"))
        assertNull(ChannelQrCodec.decode("rzvch1:1"))
        assertNull(ChannelQrCodec.decode("rzvch1:1:short"))
        assertNull(ChannelQrCodec.decode("rzvch1:notanumber:$keyHex"))
        assertNull(ChannelQrCodec.decode("rzvch1:1:${"z".repeat(64)}"))
    }

    @Test
    fun `encode requires a 32 byte key`() {
        val tooShort = runCatching { ChannelQrCodec.encode(1, ByteArray(31)) }
        assertTrue("expected a require() failure for a 31 byte key", tooShort.isFailure)
    }

    @Test
    fun `decode accepts uppercase hex`() {
        val upper = keyHex.uppercase()
        assertTrue(key.contentEquals(ChannelQrCodec.decode("rzvch1:7:$upper")!!.key))
    }
}
