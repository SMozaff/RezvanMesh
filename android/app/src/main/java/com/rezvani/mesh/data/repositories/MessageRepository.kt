package com.rezvani.mesh.data.repositories

import android.content.Context
import androidx.room.withTransaction
import com.rezvani.mesh.data.AppDatabase
import com.rezvani.mesh.data.dao.MessageDao
import com.rezvani.mesh.data.entities.MessageEntity
import com.rezvani.mesh.data.entities.MessageStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * Repository for persisted messages. Gate 1 keeps the local primary key and
 * the wire protocol ID separate: local IDs are UI/database implementation
 * details while protocol IDs are immutable, 16-byte end-to-end identities.
 */
class MessageRepository(context: Context, passphrase: ByteArray) {

    private val database = AppDatabase.getInstance(context, passphrase)
    private val messageDao: MessageDao = database.messageDao()

    fun getConversationIds(): Flow<List<String>> = messageDao.getConversationIds()

    fun getMessages(conversationId: String): Flow<List<MessageEntity>> =
        messageDao.getMessagesForConversation(conversationId)

    fun getLastMessage(conversationId: String): Flow<MessageEntity?> =
        messageDao.getMessagesForConversation(conversationId).map { messages ->
            messages.maxByOrNull { it.timestamp }
        }

    fun getUnreadCount(conversationId: String): Flow<Int> =
        messageDao.getUnreadCountFlow(conversationId)

    /** Legacy/general text insert. Direct Gate 1 sends use [insertDirectTextMessage]. */
    suspend fun insertTextMessage(
        conversationId: String,
        text: String,
        isOutgoing: Boolean
    ): String {
        val messageId = UUID.randomUUID().toString()
        messageDao.insert(
            MessageEntity(
                id = messageId,
                conversationId = conversationId,
                senderId = "",
                timestamp = System.currentTimeMillis(),
                type = 0,
                content = text,
                isOutgoing = isOutgoing,
                status = if (isOutgoing) MessageStatus.QUEUED else MessageStatus.REMOTE_RECEIVED
            )
        )
        return messageId
    }

    /**
     * Creates and commits an outgoing Gate 1 direct message before native
     * submission. Retrying this row must reuse [MessageEntity.protocolMessageId].
     */
    suspend fun insertDirectTextMessage(
        conversationId: String,
        recipientNodeId: String,
        text: String
    ): MessageEntity {
        val localId = UUID.randomUUID().toString()
        val protocolMessageId = ProtocolMessageId.generateHex()
        val message = MessageEntity(
            id = localId,
            conversationId = conversationId,
            senderId = "",
            timestamp = System.currentTimeMillis(),
            type = 0,
            content = text,
            isOutgoing = true,
            status = MessageStatus.QUEUED,
            protocolMessageId = protocolMessageId,
            recipientNodeId = recipientNodeId
        )
        messageDao.insert(message)
        return message
    }

    /**
     * Atomically stores an inbound Gate 1 direct message. A duplicate from the
     * same sender and protocol ID is not displayed twice but remains eligible
     * for an acknowledgement retry after the caller sees [inserted] = false.
     */
    suspend fun storeReceivedDirectMessage(
        senderId: String,
        protocolMessageId: String,
        timestamp: Long,
        content: String
    ): InboundDirectMessageResult = database.withTransaction {
        val existing = messageDao.findBySenderAndProtocolMessageId(senderId, protocolMessageId)
        if (existing != null) {
            InboundDirectMessageResult(inserted = false, localMessageId = existing.id)
        } else {
            val message = MessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = senderId,
                senderId = senderId,
                timestamp = timestamp,
                type = 0,
                content = content,
                isOutgoing = false,
                status = MessageStatus.REMOTE_RECEIVED,
                protocolMessageId = protocolMessageId
            )
            messageDao.insert(message)
            InboundDirectMessageResult(inserted = true, localMessageId = message.id)
        }
    }

    suspend fun insertVoiceMessage(
        conversationId: String,
        filePath: String,
        durationSeconds: Int,
        isOutgoing: Boolean
    ): String {
        val messageId = UUID.randomUUID().toString()
        val content = "$filePath|$durationSeconds"
        messageDao.insert(
            MessageEntity(
                id = messageId,
                conversationId = conversationId,
                senderId = "",
                timestamp = System.currentTimeMillis(),
                type = 1,
                content = content,
                isOutgoing = isOutgoing,
                status = if (isOutgoing) MessageStatus.QUEUED else MessageStatus.REMOTE_RECEIVED
            )
        )
        return messageId
    }

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
        messageDao.insert(
            MessageEntity(
                id = messageId,
                conversationId = conversationId,
                senderId = "",
                timestamp = System.currentTimeMillis(),
                type = 2,
                content = content,
                isOutgoing = isOutgoing,
                status = if (isOutgoing) MessageStatus.QUEUED else MessageStatus.REMOTE_RECEIVED
            )
        )
        return messageId
    }

    /** Inserts a non-Gate-1 received channel, emergency, or legacy message. */
    suspend fun insertReceivedMessage(
        messageId: String,
        conversationId: String,
        senderId: String,
        timestamp: Long,
        type: Int,
        content: String
    ) {
        messageDao.insert(
            MessageEntity(
                id = messageId,
                conversationId = conversationId,
                senderId = senderId,
                timestamp = timestamp,
                type = type,
                content = content,
                isOutgoing = false,
                status = MessageStatus.REMOTE_RECEIVED
            )
        )
    }

    /** Returns true only when an authenticated ACK matches an outgoing message and peer. */
    suspend fun markRemoteReceived(
        protocolMessageId: String,
        ackSenderId: String,
        receivedAtMs: Long = System.currentTimeMillis()
    ): Boolean = messageDao.markRemoteReceived(
        protocolMessageId = protocolMessageId,
        ackSenderId = ackSenderId,
        receivedAtMs = receivedAtMs,
        remoteReceivedStatus = MessageStatus.REMOTE_RECEIVED
    ) > 0

    suspend fun updateStatus(messageId: String, status: Int) {
        messageDao.updateStatus(messageId, status)
    }

    suspend fun markConversationAsRead(conversationId: String) {
        messageDao.markAllAsRead(conversationId)
    }

    suspend fun deleteMessage(messageId: String) {
        messageDao.deleteById(messageId)
    }

    suspend fun deleteConversation(conversationId: String) {
        messageDao.deleteConversation(conversationId)
    }

    suspend fun cleanupOldMessages(olderThan: Long): Int =
        messageDao.deleteMessagesOlderThan(olderThan)

    suspend fun getMessagesPaginated(
        conversationId: String,
        limit: Int = 50,
        offset: Int = 0
    ): List<MessageEntity> = messageDao.getMessagesPaginated(conversationId, limit, offset)
}

data class InboundDirectMessageResult(
    val inserted: Boolean,
    val localMessageId: String
)

object ProtocolMessageId {
    private val hexPattern = Regex("^[0-9a-f]{32}$")

    fun generateHex(): String = UUID.randomUUID().toString().replace("-", "")

    fun toBytes(hex: String): ByteArray? {
        if (!hexPattern.matches(hex)) return null
        return ByteArray(16) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    fun fromBytes(bytes: ByteArray): String? {
        if (bytes.size != 16) return null
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
