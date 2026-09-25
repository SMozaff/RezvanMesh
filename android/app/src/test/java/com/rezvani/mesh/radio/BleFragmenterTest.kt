package com.rezvani.mesh.radio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // --- sender/receiver size agreement -------------------------------------

    /**
     * The sender and receiver must agree on the maximum message size. The
     * sender previously imposed no limit, so a packet over the receiver's cap
     * was fragmented, transmitted, and then silently dropped at the far end --
     * after consuming the receiver's reassembly budget and with the sender
     * believing it had queued the packet.
     */
    @Test
    fun `a packet at the shared maximum is fragmentable and reassembles`() {
        val original = ByteArray(BleFragmenter.MAX_PACKET_BYTES) { (it % 251).toByte() }
        val fragments = BleFragmenter.fragment(original, mtu = 517, msgId = 11)
        assertTrue("expected the maximum to be fragmentable", fragments.isNotEmpty())

        val reassembler = BleReassembler()
        var result: ByteArray? = null
        fragments.forEach { result = reassembler.offer("peer-max", it) ?: result }
        assertArrayEquals(original, result)
    }

    @Test
    fun `a packet one byte over the maximum is refused rather than corrupted`() {
        val tooBig = ByteArray(BleFragmenter.MAX_PACKET_BYTES + 1)
        assertFalse(BleFragmenter.canFragment(tooBig.size))
        assertTrue(
            "fragment() must refuse an unfragmentable packet, not emit a wrapped total",
            BleFragmenter.fragment(tooBig, mtu = 517, msgId = 12).isEmpty()
        )
    }

    @Test
    fun `canFragment covers the whole valid range and nothing else`() {
        assertFalse(BleFragmenter.canFragment(0))
        assertTrue(BleFragmenter.canFragment(1))
        assertTrue(BleFragmenter.canFragment(BleFragmenter.MAX_PACKET_BYTES))
        assertFalse(BleFragmenter.canFragment(BleFragmenter.MAX_PACKET_BYTES + 1))
    }

    /**
     * Even with the packet under the byte cap, a tiny MTU can push the
     * fragment count over the ceiling. `total` is a u16 on the wire, so this
     * has to be refused rather than truncated.
     */
    @Test
    fun `fragment count is capped even at the minimum usable MTU`() {
        // At mtu=23 the chunk is 12 bytes, so the full 64 KiB needs ~5462
        // fragments -- over MAX_FRAGMENTS.
        val big = ByteArray(BleFragmenter.MAX_PACKET_BYTES) { 1 }
        val fragments = BleFragmenter.fragment(big, mtu = 23, msgId = 13)
        assertTrue("must refuse rather than wrap the u16 total", fragments.isEmpty())
    }

    @Test
    fun `the fragment total field never exceeds the receiver ceiling`() {
        val packet = ByteArray(16 * 1024) { 2 }
        for (mtu in listOf(23, 64, 247, 517)) {
            val fragments = BleFragmenter.fragment(packet, mtu = mtu, msgId = 14)
            if (fragments.isEmpty()) continue
            // `total` is bytes 6..7 of the fragment header, big-endian.
            val total = ((fragments[0][6].toInt() and 0xFF) shl 8) or
                (fragments[0][7].toInt() and 0xFF)
            assertTrue(
                "total $total at mtu $mtu exceeds MAX_FRAGMENTS",
                total <= BleFragmenter.MAX_FRAGMENTS
            )
            assertEquals("all fragments must agree on total", fragments.size, total)
        }
    }
}
