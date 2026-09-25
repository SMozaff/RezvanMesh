package com.rezvani.mesh.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.rezvani.mesh.data.entities.ChannelEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChannelDao {

    @Insert
    suspend fun insert(channel: ChannelEntity)

    @Insert
    suspend fun insertAll(channels: List<ChannelEntity>)

    @Update
    suspend fun update(channel: ChannelEntity)

    @Query("SELECT * FROM channels ORDER BY memberCount DESC, name COLLATE NOCASE ASC")
    fun getAllChannels(): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE isJoined = 1 ORDER BY lastMessageTimestamp DESC")
    fun getJoinedChannels(): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE isJoined = 0 ORDER BY memberCount DESC")
    fun getDiscoverableChannels(): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE channelId = :channelId")
    suspend fun getChannelById(channelId: Int): ChannelEntity?

    @Query("SELECT * FROM channels WHERE channelId = :channelId")
    fun getChannelByIdFlow(channelId: Int): Flow<ChannelEntity?>

    @Query("""
        SELECT * FROM channels 
        WHERE name LIKE '%' || :query || '%' 
        OR description LIKE '%' || :query || '%'
        ORDER BY memberCount DESC
    """)
    fun searchChannels(query: String): Flow<List<ChannelEntity>>

    @Query("UPDATE channels SET isJoined = 1 WHERE channelId = :channelId")
    suspend fun markAsJoined(channelId: Int)

    /**
     * Store the shared sender key and mark the channel joined, atomically.
     *
     * Both in one statement so there is no window in which `isJoined` is true
     * but the key is missing -- which is the exact state that made joined
     * channels silently dead across a restart.
     */
    @Query("UPDATE channels SET isJoined = 1, senderKey = :senderKey WHERE channelId = :channelId")
    suspend fun markAsJoinedWithKey(channelId: Int, senderKey: ByteArray)

    @Query("SELECT * FROM channels WHERE senderKey IS NOT NULL")
    suspend fun getChannelsWithKeys(): List<ChannelEntity>

    @Query("SELECT senderKey FROM channels WHERE channelId = :channelId")
    suspend fun getSenderKey(channelId: Int): ByteArray?

    @Query("UPDATE channels SET isJoined = 0 WHERE channelId = :channelId")
    suspend fun markAsLeft(channelId: Int)

    /**
     * Leave a channel and revoke the shared sender key, in one statement.
     *
     * Clearing the key is the point, not a side effect. If it stayed, we would
     * retain the ability to decrypt every future broadcast for a channel we
     * claimed to have left, and the native engine still holds the key in memory
     * for the process lifetime.
     *
     * Old messages stay readable: they were decrypted on arrival and are stored
     * in `messages`, so the key is not needed for history.
     */
    @Query("UPDATE channels SET isJoined = 0, senderKey = NULL WHERE channelId = :channelId")
    suspend fun markAsLeftAndRevokeKey(channelId: Int)

    @Query("UPDATE channels SET memberCount = :count WHERE channelId = :channelId")
    suspend fun updateMemberCount(channelId: Int, count: Int)

    @Query("UPDATE channels SET lastMessageTimestamp = :timestamp WHERE channelId = :channelId")
    suspend fun updateLastMessageTimestamp(channelId: Int, timestamp: Long)

    @Query("UPDATE channels SET name = :name, description = :description WHERE channelId = :channelId")
    suspend fun updateChannelInfo(channelId: Int, name: String, description: String)

    /**
     * Replace a stored password hash.
     *
     * Used only to migrate a legacy bare-SHA256 hash to the current salted
     * PBKDF2 format at the moment the plaintext password happens to be
     * available (a successful join). There is no way to re-hash offline,
     * because the stored value is one-way.
     */
    @Query("UPDATE channels SET passwordHash = :passwordHash WHERE channelId = :channelId")
    suspend fun updatePasswordHash(channelId: Int, passwordHash: String)

    @Query("DELETE FROM channels WHERE channelId = :channelId")
    suspend fun deleteById(channelId: Int)

    @Query("DELETE FROM channels WHERE isJoined = 0")
    suspend fun deleteDiscoveredChannels()

    @Query("SELECT COUNT(*) FROM channels WHERE isJoined = 1")
    suspend fun getJoinedChannelCount(): Int

    @Query("SELECT * FROM channels WHERE isPrivate = 0")
    fun getPublicChannels(): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE isPrivate = 1 AND isJoined = 1")
    fun getPrivateJoinedChannels(): Flow<List<ChannelEntity>>
}
