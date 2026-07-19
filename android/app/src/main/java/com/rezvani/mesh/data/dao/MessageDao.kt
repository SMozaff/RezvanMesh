package com.rezvani.mesh.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.rezvani.mesh.data.entities.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Insert
    suspend fun insert(message: MessageEntity)

    @Insert
    suspend fun insertAll(messages: List<MessageEntity>)

    @Update
    suspend fun update(message: MessageEntity)

    @Query("UPDATE messages SET status = :status WHERE id = :messageId")
    suspend fun updateStatus(messageId: String, status: Int)

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp DESC")
    fun getMessagesForConversation(conversationId: String): Flow<List<MessageEntity>>

    @Query("""
        SELECT * FROM messages 
        WHERE conversationId = :conversationId 
        ORDER BY timestamp DESC 
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getMessagesPaginated(
        conversationId: String,
        limit: Int,
        offset: Int
    ): List<MessageEntity>

    @Query("""
        SELECT DISTINCT conversationId FROM messages 
        ORDER BY (
            SELECT MAX(timestamp) FROM messages AS m2 
            WHERE m2.conversationId = messages.conversationId
        ) DESC
    """)
    fun getConversationIds(): Flow<List<String>>

    @Query("""
        SELECT * FROM messages 
        WHERE conversationId = :conversationId 
        AND timestamp = (
            SELECT MAX(timestamp) FROM messages 
            WHERE conversationId = :conversationId
        )
    """)
    suspend fun getLastMessageForConversation(conversationId: String): MessageEntity?

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId AND isOutgoing = 0 AND status != 3")
    suspend fun getUnreadCount(conversationId: String): Int

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId AND isOutgoing = 0 AND status != 3")
    fun getUnreadCountFlow(conversationId: String): Flow<Int>

    @Query("UPDATE messages SET status = 3 WHERE conversationId = :conversationId AND isOutgoing = 0")
    suspend fun markAllAsRead(conversationId: String)

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteById(messageId: String)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteConversation(conversationId: String)

    @Query("DELETE FROM messages WHERE timestamp < :olderThan")
    suspend fun deleteMessagesOlderThan(olderThan: Long): Int

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun getTotalMessageCount(): Int

    @Query("""
        SELECT SUM(LENGTH(content)) FROM messages 
        WHERE type = 0 OR type = 1
    """)
    suspend fun getTotalTextStorageUsed(): Long
}
