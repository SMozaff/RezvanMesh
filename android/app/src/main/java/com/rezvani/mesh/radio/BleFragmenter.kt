package com.rezvani.mesh.radio

import java.util.concurrent.ConcurrentHashMap

/**
 * Application-layer fragmentation + reassembly for GATT.
 *
 * A GATT write is capped at the negotiated ATT MTU (default 23B; we negotiate
 * up to 517). Olm pre-key messages (~200B), KeyAnnouncements (90B) and voice
 * clips (16-31KB) exceed one write, so packets sent over GATT are split into
 * fragments and rebuilt on the receiver. Advertisement traffic (26B) does NOT
 * go through here.
 *
 * Fragment wire format (one fragment == one GATT write):
 *   [0]      magic 0xF0   (a whole mesh packet starts with version 0x01, so
 *                          0xF0 unambiguously marks a fragment)
 *   [1]      reserved 0x00
 *   [2..3]   msgId  u16 BE (groups fragments of one logical packet)
 *   [4..5]   index  u16 BE (0-based fragment number)
 *   [6..7]   total  u16 BE (fragment count for this message)
 *   [8..]    chunk
 *
 * Single-hop, write-with-response transport is reliable and in-order, so v1
 * needs no ARQ/FEC. Multi-hop voice (future) would.
 */
object BleFragmenter {
    const val MAGIC: Byte = 0xF0.toByte()
    const val HEADER = 8
    private const val ATT_OVERHEAD = 3   // opcode + attribute handle
    private const val MIN_USABLE = 20    // floor when MTU is unknown/tiny

    /**
     * Largest logical packet that can be fragmented and successfully reassembled.
     *
     * This must match [BleReassembler]'s default `maxPacketBytes`. The sender
     * used to impose no limit at all, which made oversized packets fail in two
     * different silent ways:
     *
     * * A packet large enough to need more than 65 535 fragments had its
     *   `total` field -- a `u16` on the wire -- wrap, so the receiver saw a
     *   small `total` and discarded every fragment.
     * * A packet over the receiver's 64 KiB cap was accepted fragment by
     *   fragment and then dropped at the byte-count check, having already
     *   consumed the receiver's reassembly budget.
     *
     * In both cases the sender believed it had queued the packet. Checking here
     * lets the sender report a real failure instead.
     */
    const val MAX_PACKET_BYTES = 64 * 1024

    /**
     * Ceiling on the fragment count.
     *
     * Matches [BleReassembler]'s `maxFragments`. Chosen so a full 64 KiB
     * packet always fits even at the smallest usable MTU, where a fragment
     * carries only 12 payload bytes.
     */
    const val MAX_FRAGMENTS = 4_096

    /**
     * Whether a packet of [packetSize] bytes can be fragmented and reassembled.
     *
     * Callers must check this before queueing, so an oversized packet is
     * reported as rejected rather than vanishing.
     */
    fun canFragment(packetSize: Int): Boolean = packetSize in 1..MAX_PACKET_BYTES

    /**
     * Split a full packet into MTU-sized fragments.
     *
     * Returns an empty list if the packet cannot be represented on the wire.
     * Callers should use [canFragment] first so they can surface a failure;
     * this check exists so the function cannot produce a corrupt `total` field
     * even if a future caller forgets.
     */
    fun fragment(packet: ByteArray, mtu: Int, msgId: Int): List<ByteArray> {
        if (!canFragment(packet.size)) return emptyList()

        val usable = (mtu - ATT_OVERHEAD).coerceAtLeast(MIN_USABLE)
        val chunkSize = (usable - HEADER).coerceAtLeast(1)
        val total = ((packet.size + chunkSize - 1) / chunkSize).coerceAtLeast(1)
        // Belt and braces: at the minimum chunk size an oversized packet could
        // still exceed the fragment ceiling, and `total` is written as a u16.
        if (total > MAX_FRAGMENTS) return emptyList()

        val out = ArrayList<ByteArray>(total)
        var offset = 0
        for (idx in 0 until total) {
            val end = minOf(offset + chunkSize, packet.size)
            val chunk = packet.copyOfRange(offset, end)
            val frag = ByteArray(HEADER + chunk.size)
            frag[0] = MAGIC
            frag[1] = 0x00
            frag[2] = (msgId ushr 8).toByte(); frag[3] = msgId.toByte()
            frag[4] = (idx ushr 8).toByte();   frag[5] = idx.toByte()
            frag[6] = (total ushr 8).toByte(); frag[7] = total.toByte()
            chunk.copyInto(frag, HEADER)
            out.add(frag)
            offset = end
        }
        return out
    }
}

/**
 * Reassembly buffer. One instance per RadioController. Bounded to resist
 * memory exhaustion from incomplete or hostile fragment streams.
 */
class BleReassembler(
    private val maxInFlight: Int = 8,
    private val timeoutMs: Long = 15_000L,
    private val maxFragments: Int = BleFragmenter.MAX_FRAGMENTS,
    private val maxPacketBytes: Int = BleFragmenter.MAX_PACKET_BYTES
) {
    private class Partial(
        val total: Int,
        val chunks: Array<ByteArray?>,
        var received: Int,
        var byteCount: Int,
        val firstSeen: Long
    )

    // key = "senderAddr|msgId"
    private val partials = ConcurrentHashMap<String, Partial>()

    /**
     * Feed a received GATT write.
     *  - returns the fully reassembled packet when the last fragment arrives,
     *  - returns the input unchanged if it is not a fragment (back-compat),
     *  - returns null while a message is still incomplete or on a dropped frag.
     */
    fun offer(sender: String, data: ByteArray): ByteArray? {
        if (data.size < BleFragmenter.HEADER || data[0] != BleFragmenter.MAGIC) {
            return data // not a fragment; pass straight through
        }

        val msgId = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        val index = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
        val total = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
        if (total == 0 || total > maxFragments || index >= total) return null

        purgeExpired()

        val key = "$sender|$msgId"
        val chunk = data.copyOfRange(BleFragmenter.HEADER, data.size)

        val partial = partials.getOrPut(key) {
            if (partials.size >= maxInFlight) evictOldest()
            Partial(total, arrayOfNulls(total), 0, 0, System.currentTimeMillis())
        }

        if (partial.total != total) { partials.remove(key); return null } // inconsistent
        if (partial.chunks[index] != null) return null                     // duplicate
        if (partial.byteCount + chunk.size > maxPacketBytes) {
            partials.remove(key)
            return null
        }
        partial.chunks[index] = chunk
        partial.byteCount += chunk.size
        partial.received++

        if (partial.received == partial.total) {
            partials.remove(key)
            val size = partial.chunks.sumOf { it?.size ?: 0 }
            val out = ByteArray(size)
            var o = 0
            for (c in partial.chunks) { c!!.copyInto(out, o); o += c.size }
            return out
        }
        return null
    }

    private fun purgeExpired() {
        val now = System.currentTimeMillis()
        partials.entries.removeAll { now - it.value.firstSeen > timeoutMs }
    }

    private fun evictOldest() {
        partials.entries.minByOrNull { it.value.firstSeen }?.let { partials.remove(it.key) }
    }
}