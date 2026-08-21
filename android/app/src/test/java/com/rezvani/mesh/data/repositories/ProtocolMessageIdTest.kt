package com.rezvani.mesh.data.repositories

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolMessageIdTest {

    @Test
    fun generatedIdentifierIsCanonicalAndRoundTripsToWireBytes() {
        val id = ProtocolMessageId.generateHex()
        assertTrue(id.matches(Regex("^[0-9a-f]{32}$")))

        val bytes = ProtocolMessageId.toBytes(id)
        requireNotNull(bytes)
        assertEquals(16, bytes.size)
        assertEquals(id, ProtocolMessageId.fromBytes(bytes))
    }

    @Test
    fun rejectsNonCanonicalOrWrongLengthIdentifiers() {
        assertNull(ProtocolMessageId.toBytes(""))
        assertNull(ProtocolMessageId.toBytes("A".repeat(32)))
        assertNull(ProtocolMessageId.toBytes("g".repeat(32)))
        assertNull(ProtocolMessageId.toBytes("ab".repeat(15)))
        assertNull(ProtocolMessageId.fromBytes(ByteArray(15)))
    }

    @Test
    fun preservesAllUnsignedByteValues() {
        val source = ByteArray(16) { it.toByte() }
        val encoded = ProtocolMessageId.fromBytes(source)
        requireNotNull(encoded)
        val decoded = ProtocolMessageId.toBytes(encoded)
        requireNotNull(decoded)
        assertArrayEquals(source, decoded)
    }
}
