package com.rezvani.mesh.data.repositories

import android.content.Context
import com.rezvani.mesh.data.AppDatabase
import com.rezvani.mesh.data.dao.ChannelDao
import com.rezvani.mesh.data.entities.ChannelEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest

/**
 * Repository for managing channel data.
 */
class ChannelRepository(context: Context, passphrase: ByteArray) {

    private val channelDao: ChannelDao = AppDatabase.getInstance(context, passphrase).channelDao()

    /**
     * Serialises private-channel join attempts.
     *
     * The throttle check, the password verification, and recording the outcome
     * must not interleave with another attempt on the same channel, or the
     * failure counter could be raced back down.
     *
     * This is a coroutine [Mutex] rather than `synchronized` because the
     * critical section contains suspend DAO calls, and `kotlin.synchronized`
     * takes a non-suspend lambda. A `Mutex` suspends rather than blocking a
     * thread while waiting, which is the right trade for work that ends in a
     * 210k-iteration PBKDF2.
     */
    private val joinLock = kotlinx.coroutines.sync.Mutex()

    /**
     * Per-channel failure history for private-channel joins.
     *
     * Only ever touched while holding [joinLock], which is what makes its own
     * lack of internal synchronisation acceptable.
     */
    private val joinThrottle = JoinThrottle()


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
     *
     * A private channel MUST have a password. Previously a private channel could
     * be created with a null password, which left `passwordHash` null, and
     * `joinPrivateChannel` treated a null hash as "no password required" -- so
     * a channel explicitly marked private was joinable by anyone who found it.
     *
     * @throws IllegalArgumentException if `isPrivate` is set without a password.
     */
    suspend fun createChannel(
        name: String,
        description: String = "",
        isPrivate: Boolean = false,
        password: String? = null
    ): Int {
        require(!isPrivate || !password.isNullOrEmpty()) {
            "A private channel requires a password"
        }
        val channelId = generateChannelId(name)
        val passwordHash = if (isPrivate) hashPassword(password!!) else null

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
     *
     * A channel with no stored hash is NOT open to everyone. The old code
     * returned `true` in that case on the assumption that a null hash only
     * happened for public channels -- but `discoverChannel` writes rows with
     * `isPrivate = true, passwordHash = null`, so any peer able to advertise a
     * channel could mint exactly that row and be joined without a password.
     * Refusing is the safe reading of "no hash": we cannot verify the caller,
     * so we do not let them in.
     *
     * Repeated failures are throttled per channel. Without this, a user (or
     * something driving the UI) could hammer guesses freely, and each one pays
     * a deliberate 210k-iteration PBKDF2 -- so a flood of attempts becomes a
     * battery/CPU denial of service against the device trying to defend
     * itself.
     */
    suspend fun joinPrivateChannel(channelId: Int, password: String): Boolean {
        if (password.isEmpty()) return false

        // `withLock` takes a suspend lambda, so the DAO calls below are legal
        // here where they would not be inside `synchronized`.
        return joinLock.withLock {
            if (!joinThrottle.tryAttempt(channelId)) return@withLock false

            val channel = channelDao.getChannelById(channelId) ?: return@withLock false
            val expectedHash = channel.passwordHash ?: return@withLock false

            if (!verifyPassword(password, expectedHash)) {
                joinThrottle.recordFailure(channelId)
                return@withLock false
            }

            joinThrottle.recordSuccess(channelId)

            // Opportunistically upgrade a legacy bare-SHA256 row now that we
            // hold the plaintext. The stored value is a one-way digest, so a
            // successful join is the only moment a re-hash is possible.
            if (ChannelPasswordHasher.needsRehash(expectedHash)) {
                runCatching {
                    channelDao.updatePasswordHash(channelId, hashPassword(password))
                }
            }

            channelDao.markAsJoined(channelId)
            channelDao.updateMemberCount(channelId, channel.memberCount + 1)
            true
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
        // Use first 4 bytes as positive Int ID (mask sign bit only on first byte)
        return ((hash[0].toInt() and 0x7F) shl 24) or
                ((hash[1].toInt() and 0xFF) shl 16) or
                ((hash[2].toInt() and 0xFF) shl 8) or
                (hash[3].toInt() and 0xFF)
    }

    /**
     * Hashes a channel password for storage.
     *
     * Delegates to [ChannelPasswordHasher] (PBKDF2-HMAC-SHA256, per-channel
     * random salt). See that file for why the previous bare unsalted SHA-256
     * was unsuitable.
     */
    private fun hashPassword(password: String): String =
        ChannelPasswordHasher.hash(password)

    /**
     * Verifies [password] against a stored hash, failing closed on anything
     * unrecognisable. Legacy bare-SHA256 rows are still accepted so existing
     * private channels are not locked out of their own members.
     */
    private fun verifyPassword(password: String, expectedHash: String): Boolean =
        ChannelPasswordHasher.verify(password, expectedHash)
}
