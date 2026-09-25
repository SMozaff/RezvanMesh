package com.rezvani.mesh.data.entities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Tests for [ChannelEntity]'s hand-written equality.
 *
 * The `senderKey` column is a `ByteArray`, and `ByteArray.equals` is identity,
 * not content. If the data class used its generated `equals`, two rows read back
 * from Room with identical key *contents* would compare unequal, so
 * `StateFlow` conflation would see a change on every emission and the channel
 * list would re-render continuously. `hashCode` has to agree with `equals` for
 * the same reason.
 */
class ChannelEntityTest {

    private fun entity(
        id: Int = 7,
        key: ByteArray? = null,
        joined: Boolean = true
    ) = ChannelEntity(
        channelId = id,
        name = "Test channel",
        description = "desc",
        isPrivate = true,
        passwordHash = "hash",
        memberCount = 3,
        lastMessageTimestamp = 1_000L,
        isJoined = joined,
        senderKey = key
    )

    @Test
    fun `equal contents compare equal`() {
        val key = ByteArray(32) { it.toByte() }
        assertEquals(entity(key = key), entity(key = key.copyOf()))
    }

    @Test
    fun `hash codes agree for equal contents`() {
        val key = ByteArray(32) { 7 }
        assertEquals(
            entity(key = key).hashCode(),
            entity(key = key.copyOf()).hashCode()
        )
    }

    @Test
    fun `two null keys compare equal`() {
        assertEquals(entity(key = null), entity(key = null))
        assertEquals(entity(key = null).hashCode(), entity(key = null).hashCode())
    }

    @Test
    fun `a null key and a present key are not equal`() {
        assertNotEquals(entity(key = null), entity(key = ByteArray(32)))
        assertNotEquals(entity(key = ByteArray(32)), entity(key = null))
    }

    @Test
    fun `different key contents are not equal`() {
        assertNotEquals(entity(key = ByteArray(32) { 1 }), entity(key = ByteArray(32) { 2 }))
    }

    @Test
    fun `same contents but different lengths are not equal`() {
        assertNotEquals(entity(key = ByteArray(32) { 1 }), entity(key = ByteArray(31) { 1 }))
    }

    @Test
    fun `identity equality still short circuits`() {
        val a = entity(key = ByteArray(32))
        assertEquals(a, a)
    }

    @Test
    fun `every scalar field participates in equality`() {
        val base = entity()
        assertNotEquals(base, base.copy(channelId = 8))
        assertNotEquals(base, base.copy(name = "other"))
        assertNotEquals(base, base.copy(description = "other"))
        assertNotEquals(base, base.copy(isPrivate = false))
        assertNotEquals(base, base.copy(passwordHash = "other"))
        assertNotEquals(base, base.copy(passwordHash = null))
        assertNotEquals(base, base.copy(memberCount = 4))
        assertNotEquals(base, base.copy(lastMessageTimestamp = 2_000L))
        assertNotEquals(base, base.copy(isJoined = false))
    }

    @Test
    fun `copy preserves the key and stays equal`() {
        val key = ByteArray(32) { 3 }
        val copy = entity(key = key).copy(memberCount = 9)
        assertEquals(9, copy.memberCount)
        assertEquals(key.size, copy.senderKey?.size)
    }

    @Test
    fun `not equal to a different type`() {
        // Called via equals() rather than assertNotEquals so overload
        // resolution cannot pick a numeric overload for a String argument.
        assertFalse(entity().equals("not an entity"))
        assertFalse(entity().equals(null))
    }
}
