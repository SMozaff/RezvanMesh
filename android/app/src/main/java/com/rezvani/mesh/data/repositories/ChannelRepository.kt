package com.rezvani.mesh.data.repositories

import android.content.Context
import com.rezvani.mesh.data.AppDatabase
import com.rezvani.mesh.data.dao.ChannelDao
import com.rezvani.mesh.data.entities.ChannelEntity
import kotlinx.coroutines.flow.Flow
import java.security.MessageDigest

/**
 * Repository for managing channel data.
 */
class ChannelRepository(context: Context, passphrase: ByteArray) {

    private val channelDao: ChannelDao = AppDatabase.getInstance(context, passphrase).channelDao()

    /**
     * Flow of all known channels.
     */
    fun getAllChannels(): Flow<List<ChannelEntity>> = channelDao.getAllChannels()

    /**
     * Flow of joined channels only.
     */
    fun getJoinedChannels(): Flow<List<ChannelEntity>> = channelDao.getJoinedChannels()

    /**
     * Flow of discoverable channels (not joined).
     */
    fun getDiscoverableChannels(): Flow<List<ChannelEntity>> = channelDao.getDiscoverableChannels()

    /**
     * Flow of public channels.
     */
    fun getPublicChannels(): Flow<List<ChannelEntity>> = channelDao.getPublicChannels()

    /**
     * Gets a specific channel by ID.
     */
    suspend fun getChannel(channelId: Int): ChannelEntity? = channelDao.getChannelById(channelId)

    /**
     * Flow of a specific channel.
     */
    fun getChannelFlow(channelId: Int): Flow<ChannelEntity?> = channelDao.getChannelByIdFlow(channelId)

    /**
     * Searches channels by name or description.
     */
    fun searchChannels(query: String): Flow<List<ChannelEntity>> = channelDao.searchChannels(query)

    /**
     * Adds or updates a discovered channel.
     */
    suspend fun discoverChannel(
        channelId: Int,
        name: String,
        description: String = "",
        isPrivate: Boolean = false,
        memberCount: Int = 1
    ) {
        val existing = channelDao.getChannelById(channelId)
        val channel = if (existing != null) {
            existing.copy(
                name = name,
                description = description,
                memberCount = memberCount
            )
        } else {
            ChannelEntity(
                channelId = channelId,
                name = name,
                description = description,
                isPrivate = isPrivate,
                passwordHash = null,
                memberCount = memberCount,
                isJoined = false
            )
        }
        channelDao.insert(channel)
    }

    /**
     * Creates a new channel.
     */
    suspend fun createChannel(
        name: String,
        description: String = "",
        isPrivate: Boolean = false,
        password: String? = null
    ): Int {
        val channelId = generateChannelId(name)
        val passwordHash = if (isPrivate && password != null) {
            hashPassword(password)
        } else {
            null
        }

        val channel = ChannelEntity(
            channelId = channelId,
            name = name,
            description = description,
            isPrivate = isPrivate,
            passwordHash = passwordHash,
            memberCount = 1,
            isJoined = true
        )
        channelDao.insert(channel)
        return channelId
    }

    /**
     * Joins a public channel.
     */
    suspend fun joinChannel(channelId: Int) {
        channelDao.markAsJoined(channelId)
        channelDao.getChannelById(channelId)?.let { channel ->
            channelDao.updateMemberCount(channelId, channel.memberCount + 1)
        }
    }

    /**
     * Joins a private channel with password verification.
     */
    suspend fun joinPrivateChannel(channelId: Int, password: String): Boolean {
        val channel = channelDao.getChannelById(channelId) ?: return false
        val expectedHash = channel.passwordHash ?: return true

        return if (verifyPassword(password, expectedHash)) {
            channelDao.markAsJoined(channelId)
            channelDao.updateMemberCount(channelId, channel.memberCount + 1)
            true
        } else {
            false
        }
    }

    /**
     * Leaves a channel.
     */
    suspend fun leaveChannel(channelId: Int) {
        channelDao.markAsLeft(channelId)
        channelDao.getChannelById(channelId)?.let { channel ->
            channelDao.updateMemberCount(channelId, maxOf(0, channel.memberCount - 1))
        }
    }

    /**
     * Updates channel member count.
     */
    suspend fun updateMemberCount(channelId: Int, count: Int) {
        channelDao.updateMemberCount(channelId, count)
    }

    /**
     * Updates last message timestamp.
     */
    suspend fun updateLastMessageTimestamp(channelId: Int, timestamp: Long) {
        channelDao.updateLastMessageTimestamp(channelId, timestamp)
    }

    /**
     * Deletes a channel.
     */
    suspend fun deleteChannel(channelId: Int) {
        channelDao.deleteById(channelId)
    }

    /**
     * Clears all discovered (non-joined) channels.
     */
    suspend fun clearDiscoveredChannels() {
        channelDao.deleteDiscoveredChannels()
    }

    private fun generateChannelId(name: String): Int {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(name.toByteArray())
        // Use first 4 bytes as Int ID (positive only)
        return ((hash[0].toInt() and 0xFF) shl 24) or
                ((hash[1].toInt() and 0xFF) shl 16) or
                ((hash[2].toInt() and 0xFF) shl 8) or
                (hash[3].toInt() and 0xFF) and 0x7FFFFFFF
    }

    private fun hashPassword(password: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(password.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun verifyPassword(password: String, expectedHash: String): Boolean {
        return hashPassword(password) == expectedHash
    }
}
