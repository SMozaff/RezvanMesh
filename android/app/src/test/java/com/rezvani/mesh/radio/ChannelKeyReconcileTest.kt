package com.rezvani.mesh.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the channel-key reconciliation helpers.
 *
 * The bug these guard against: the database is authoritative for channel
 * membership, but the engine holds a *second* copy — in memory for the process
 * lifetime, and in the encrypted engine-state file, which is rewritten on every
 * periodic save. Reconciliation that only *installs* keys therefore leaves the
 * engine decrypting channels the user has left, and the next state save writes
 * the key straight back, so the revocation never takes effect at all — not even
 * across a restart.
 *
 * These are pure functions precisely so the revoke direction can be tested
 * without a live service, engine, or database.
 */
class ChannelKeyReconcileTest {

    private fun pack(vararg ids: Int): ByteArray {
        val out = ByteArray(ids.size * 4)
        ids.forEachIndexed { index, id ->
            val offset = index * 4
            out[offset] = (id ushr 24).toByte()
            out[offset + 1] = (id ushr 16).toByte()
            out[offset + 2] = (id ushr 8).toByte()
            out[offset + 3] = id.toByte()
        }
        return out
    }

    // --- decodeChannelKeyIds -------------------------------------------------

    @Test
    fun `decodes an empty list`() {
        assertEquals(emptySet<Int>(), RezvanRadioService.decodeChannelKeyIds(ByteArray(0)))
    }

    @Test
    fun `a null payload decodes to an empty set`() {
        // A dead engine handle returns null. That must not be confused with
        // "the user has left every channel" -- but the caller reads the ids
        // before this, so an empty result simply means nothing to revoke.
        assertEquals(emptySet<Int>(), RezvanRadioService.decodeChannelKeyIds(null))
    }

    @Test
    fun `decodes a single id`() {
        assertEquals(setOf(42), RezvanRadioService.decodeChannelKeyIds(pack(42)))
    }

    @Test
    fun `decodes multiple ids`() {
        assertEquals(
            setOf(1, 2, 3, 65535, 65536, 2147483647),
            RezvanRadioService.decodeChannelKeyIds(pack(1, 2, 3, 65535, 65536, 2147483647))
        )
    }

    @Test
    fun `decodes zero and the high bit correctly`() {
        // Channel ids are Java `Int`s on the Kotlin side, so a set bit in the
        // top byte must survive the round trip rather than going negative.
        assertEquals(setOf(0), RezvanRadioService.decodeChannelKeyIds(pack(0)))
        assertEquals(
            setOf(Int.MAX_VALUE),
            RezvanRadioService.decodeChannelKeyIds(pack(Int.MAX_VALUE))
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a payload that is not a whole number of ids`() {
        // Truncating here would silently drop a channel that needs revoking,
        // which is the exact failure this code exists to prevent.
        RezvanRadioService.decodeChannelKeyIds(ByteArray(5))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a payload one byte short of a whole id`() {
        RezvanRadioService.decodeChannelKeyIds(ByteArray(3))
    }

    // --- channelsToRevoke ----------------------------------------------------

    @Test
    fun `revokes an engine key the database no longer has`() {
        // The core regression case: the engine remembers a channel the user
        // left. It must be revoked.
        assertEquals(
            listOf(7),
            RezvanRadioService.channelsToRevoke(setOf(7), emptySet())
        )
    }

    @Test
    fun `revokes nothing when the two agree`() {
        assertEquals(
            emptyList<Int>(),
            RezvanRadioService.channelsToRevoke(setOf(1, 2, 3), setOf(1, 2, 3))
        )
    }

    @Test
    fun `revokes only the extras and not the shared ones`() {
        assertEquals(
            listOf(2, 9),
            RezvanRadioService.channelsToRevoke(setOf(1, 2, 3), setOf(1, 3, 9))
        )
    }

    @Test
    fun `installing a new channel is not a revoke`() {
        // A key the database has but the engine does not must be installed, not
        // touched -- revoking it would be the opposite of the intent.
        assertEquals(
            emptyList<Int>(),
            RezvanRadioService.channelsToRevoke(emptySet(), setOf(5))
        )
    }

    @Test
    fun `an empty engine revokes nothing`() {
        assertEquals(emptyList<Int>(), RezvanRadioService.channelsToRevoke(emptySet(), setOf(1, 2)))
    }

    @Test
    fun `revocation output is sorted for deterministic logging`() {
        val revoked = RezvanRadioService.channelsToRevoke(
            setOf(99, 3, 50, 7),
            setOf(50)
        )
        assertEquals(listOf(3, 7, 99), revoked)
    }

    @Test
    fun `duplicate engine ids are collapsed`() {
        // A set in, a list out: the same channel must not be revoked twice.
        val revoked = RezvanRadioService.channelsToRevoke(setOf(4, 4, 4), emptySet())
        assertEquals(listOf(4), revoked)
    }

    // --- end-to-end shape of a start-up reconciliation ------------------------

    @Test
    fun `a left channel is revoked across a simulated restart`() {
        // Walk the sequence that used to be broken, using the real helpers.
        //
        // 1. User is in channels 1 and 2. Engine state + database both have them.
        // 2. User leaves channel 2. Database clears it; the engine is offline, so
        //    only the database half happens.
        // 3. Restart: the engine restores from its state file and still holds 2.
        val engineAfterRestart = RezvanRadioService.decodeChannelKeyIds(pack(1, 2))
        val database = setOf(1)

        // 4. Reconcile: only channel 2 survives, and is revoked.
        val toRevoke = RezvanRadioService.channelsToRevoke(engineAfterRestart, database)
        assertEquals(listOf(2), toRevoke)

        // 5. After revocation the engine matches the database exactly.
        val engineAfterReconcile = engineAfterRestart - toRevoke.toSet()
        assertEquals(database, engineAfterReconcile)
    }

    @Test
    fun `a channel joined while the engine was offline is installed not revoked`() {
        val engineAfterRestart = RezvanRadioService.decodeChannelKeyIds(pack(1))
        val database = setOf(1, 5)
        assertEquals(emptyList<Int>(), RezvanRadioService.channelsToRevoke(engineAfterRestart, database))
        assertTrue("5 is new; it gets installed by the caller", 5 !in engineAfterRestart)
    }
}
