package com.rezvani.mesh.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    indices = [Index(value = ["senderId", "protocolMessageId"], unique = true)]
)
data class MessageEntity(
    @PrimaryKey
    val id: String,                 // UUID
    val conversationId: String,     // Contact nodeId or "channel_xxx"
    val senderId: String,           // 8-char hex NodeId
    val timestamp: Long,
    val type: Int,                  // MessageType value: 0=TEXT, 1=VOICE, 2=FILE_METADATA, 3=FILE_CHUNK
    val content: String,            // Text content or file path
    val isOutgoing: Boolean,
    val status: Int,
    /** 32-character hex representation of the 16-byte Gate 1 wire ID. */
    val protocolMessageId: String? = null,
    /** Intended remote peer for outgoing direct messages. */
    val recipientNodeId: String? = null,
    /** Local observation time of a valid signed RECEIVED acknowledgement. */
    val remoteReceivedAtMs: Long? = null,
    val remoteAckSenderId: String? = null
)

/** Persistent lifecycle values. Each value is tied to explicit evidence; no
 * state implies remote receipt unless a matching signed Gate 1 ACK was valid. */
object MessageStatus {
    const val QUEUED = 0
    @Deprecated("Use QUEUED to avoid implying radio transmission")
    const val SENDING = QUEUED
    const val LOCAL_TRANSPORT_ACCEPTED = 1
    const val REMOTE_RECEIVED = 2
    const val READ = 3 // Reserved for a future explicit read-receipt protocol.
    const val FAILED = 4

    @Deprecated("Use LOCAL_TRANSPORT_ACCEPTED")
    const val SENT = LOCAL_TRANSPORT_ACCEPTED
    @Deprecated("Use REMOTE_RECEIVED")
    const val DELIVERED = REMOTE_RECEIVED
}
