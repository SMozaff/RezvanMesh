package com.rezvani.mesh.data.repositories

import android.content.Context
import com.rezvani.mesh.data.AppDatabase
import com.rezvani.mesh.data.dao.MessageDao
import com.rezvani.mesh.data.entities.MessageEntity
import com.rezvani.mesh.data.entities.MessageStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * Repository for managing message data.
 * Provides a clean API for the UI layer to observe and modify messages.
 */
class MessageRepository(context: Context, passphrase: ByteArray) {

    private val messageDao: MessageDao = AppDatabase.getInstance(context, passphrase).messageDao()

    /**
     * Flow of all conversation IDs, ordered by most recent activity.
     */
    fun getConversationIds(): Flow<List<String>> = messageDao.getConversationIds()

    /**
     * Flow of messages for a specific conversation.
     */
    fun getMessages(conversationId: String): Flow<List<MessageEntity>> =
        messageDao.getMessagesForConversation(conversationId)

    /**
     * Flow of the last message in a conversation.
     */
    fun getLastMessage(conversationId: String): Flow<MessageEntity?> =
        messageDao.getMessagesForConversation(conversationId).map { messages ->
            messages.maxByOrNull { it.timestamp }
        }

    /**
     * Flow of unread count for a conversation.
     */
    fun getUnreadCount(conversationId: String): Flow<Int> =
        messageDao.getUnreadCountFlow(conversationId)

    /**
     * Inserts a new text message.
     */
    suspend fun insertTextMessage(
        conversationId: String,
        text: String,
        isOutgoing: Boolean
    ): String {
        val messageId = UUID.randomUUID().toString()
        val message = MessageEntity(
            id = messageId,
            conversationId = conversationId,
            senderId = "", // Will be set by native core
            timestamp = System.currentTimeMillis(),
            type = 0, // TEXT
            content = text,
            isOutgoing = isOutgoing,
            status = if (isOutgoing) MessageStatus.SENDING else MessageStatus.DELIVERED
        )
        messageDao.insert(message)
        return messageId
    }

    /**
     * Inserts a voice message.
     */
    suspend fun insertVoiceMessage(
        conversationId: String,
        filePath: String,
        durationSeconds: Int,
        isOutgoing: Boolean
    ): String {
        val messageId = UUID.randomUUID().toString()
        val content = "$filePath|$durationSeconds"
        val message = MessageEntity(
            id = messageId,
            conversationId = conversationId,
            senderId = "",
            timestamp = System.currentTimeMillis(),
            type = 1, // VOICE
            content = content,
            isOutgoing = isOutgoing,
            status = if (isOutgoing) MessageStatus.SENDING else MessageStatus.DELIVERED
        )
        messageDao.insert(message)
        return messageId
    }

    /**
     * Inserts a file message (metadata).
     */
    suspend fun insertFileMessage(
        conversationId: String,
        fileName: String,
        fileSize: Long,
        mimeType: String,
        filePath: String,
        isOutgoing: Boolean
    ): String {
        val messageId = UUID.randomUUID().toString()
        val content = "$fileName|$fileSize|$mimeType|$filePath"
        val message = MessageEntity(
            id = messageId,
            conversationId = conversationId,
            senderId = "",
            timestamp = System.currentTimeMillis(),
            type = 2, // FILE_METADATA
            content = content,
            isOutgoing = isOutgoing,
            status = if (isOutgoing) MessageStatus.SENDING else MessageStatus.DELIVERED
        )
        messageDao.insert(message)
        return messageId
    }

    /**
     * Inserts a received message (from broadcast).
     */
    suspend fun insertReceivedMessage(
        messageId: String,
        conversationId: String,
        senderId: String,
        timestamp: Long,
        type: Int,
        content: String
    ) {
        val message = MessageEntity(
            id = messageId,
            conversationId = conversationId,
            senderId = senderId,
            timestamp = timestamp,
            type = type,
            content = content,
            isOutgoing = false,
            status = MessageStatus.DELIVERED
        )
        messageDao.insert(message)
    }

    /**
     * Updates message status.
     */
    suspend fun updateStatus(messageId: String, status: Int) {
        messageDao.updateStatus(messageId, status)
    }

    /**
     * Marks all messages in a conversation as read.
     */
    suspend fun markConversationAsRead(conversationId: String) {
        messageDao.markAllAsRead(conversationId)
    }

    /**
     * Deletes a specific message.
     */
    suspend fun deleteMessage(messageId: String) {
        messageDao.deleteById(messageId)
    }

    /**
     * Deletes an entire conversation.
     */
    suspend fun deleteConversation(conversationId: String) {
        messageDao.deleteConversation(conversationId)
    }

    /**
     * Deletes messages older than the specified timestamp.
     */
    suspend fun cleanupOldMessages(olderThan: Long): Int {
        return messageDao.deleteMessagesOlderThan(olderThan)
    }

    /**
     * Gets paginated messages for a conversation.
     */
    suspend fun getMessagesPaginated(
        conversationId: String,
        limit: Int = 50,
        offset: Int = 0
    ): List<MessageEntity> {
        return messageDao.getMessagesPaginated(conversationId, limit, offset)
    }
}
