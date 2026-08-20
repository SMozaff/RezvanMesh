package com.rezvani.mesh.radio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleFragmenterTest {

    @Test
    fun `large payload round trips at default mtu`() {
        val original = ByteArray(31_000) { ((it * 29) and 0xFF).toByte() }
        val fragments = BleFragmenter.fragment(original, mtu = 23, msgId = 42)
        val reassembler = BleReassembler()
        var rebuilt: ByteArray? = null

        fragments.forEach { fragment ->
            rebuilt = reassembler.offer("peer-a", fragment) ?: rebuilt
        }

        assertTrue(fragments.size > 1)
        assertArrayEquals(original, rebuilt)
    }

    @Test
    fun `out of order fragments and duplicates still reassemble once`() {
        val original = ByteArray(4_096) { (it and 0xFF).toByte() }
        val fragments = BleFragmenter.fragment(original, mtu = 247, msgId = 7)
        val reassembler = BleReassembler()
        var rebuilt: ByteArray? = null

        fragments.reversed().forEach { fragment ->
            rebuilt = reassembler.offer("peer-b", fragment) ?: rebuilt
        }
        assertArrayEquals(original, rebuilt)
        assertNull(reassembler.offer("peer-b", fragments.first()))
    }

    @Test
    fun `non fragmented packet passes through unchanged`() {
        val packet = byteArrayOf(0x01, 0x03, 0x05, 0x07)
        val result = BleReassembler().offer("peer-c", packet)
        assertArrayEquals(packet, result)
    }

    @Test
    fun `oversized reassembly is discarded within configured memory bound`() {
        val original = ByteArray(256) { 1 }
        val fragments = BleFragmenter.fragment(original, mtu = 23, msgId = 9)
        val reassembler = BleReassembler(maxPacketBytes = 32)

        fragments.forEach { fragment ->
            assertNull(reassembler.offer("peer-d", fragment))
        }
    }

    @Test
    fun `fragment count is bounded before allocation`() {
        val malicious = byteArrayOf(
            BleFragmenter.MAGIC, 0x00,
            0x00, 0x01,
            0x00, 0x00,
            0x7F.toByte(), 0xFF.toByte(),
            0x01
        )
        assertNull(BleReassembler(maxFragments = 64).offer("peer-e", malicious))
        assertEquals(9, malicious.size)
    }
}
