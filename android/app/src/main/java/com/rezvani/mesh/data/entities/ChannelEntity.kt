package com.rezvani.mesh.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "channels")
data class ChannelEntity(
    @PrimaryKey
    val channelId: Int,
    val name: String,
    val description: String = "",
    val isPrivate: Boolean,
    val passwordHash: String? = null,
    val memberCount: Int = 0,
    val lastMessageTimestamp: Long = 0L,
    val isJoined: Boolean = false,
    /**
     * The 32-byte shared sender key for this channel, or null if we are not a
     * member (or have not been given the key yet).
     *
     * The key used to live only in the native engine's in-memory map, which
     * meant `isJoined = true` and "can actually decrypt this channel" were two
     * independent facts that could disagree -- most visibly after a restart,
     * where the row survived and the key did not, so a channel appeared in the
     * UI and silently failed to send or receive.
     *
     * Keeping it beside the membership flag makes those the same fact. The
     * native engine still holds its own copy for the lifetime of the process
     * and still persists it in the encrypted engine-state file; this column is
     * the authoritative record, and the service re-installs from it on start.
     *
     * Null is meaningful and preserved: a public channel the user discovered
     * but has not joined has no key, and must not gain one.
     */
    val senderKey: ByteArray? = null
) {
    /**
     * Kotlin would not generate these because we declare them, and a generated
     * `equals` would compare [senderKey] by *reference* (ByteArray's `equals` is
     * identity). Every Room query returns fresh instances, so reference
     * equality makes each row look changed on every emission and defeats
     * `StateFlow` conflation in the channel list.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChannelEntity) return false
        return channelId == other.channelId &&
            name == other.name &&
            description == other.description &&
            isPrivate == other.isPrivate &&
            passwordHash == other.passwordHash &&
            memberCount == other.memberCount &&
            lastMessageTimestamp == other.lastMessageTimestamp &&
            isJoined == other.isJoined &&
            bytesEqual(senderKey, other.senderKey)
    }

    override fun hashCode(): Int {
        var result = channelId
        result = 31 * result + name.hashCode()
        result = 31 * result + description.hashCode()
        result = 31 * result + isPrivate.hashCode()
        result = 31 * result + (passwordHash?.hashCode() ?: 0)
        result = 31 * result + memberCount
        result = 31 * result + lastMessageTimestamp.hashCode()
        result = 31 * result + isJoined.hashCode()
        result = 31 * result + (senderKey?.contentHashCode() ?: 0)
        return result
    }
}

/** Null-safe content comparison for the nullable key column. */
private fun bytesEqual(a: ByteArray?, b: ByteArray?): Boolean = when {
    a == null -> b == null
    b == null -> false
    else -> a.contentEquals(b)
}
