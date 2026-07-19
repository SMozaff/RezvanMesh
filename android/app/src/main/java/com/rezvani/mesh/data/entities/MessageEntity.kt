package com.rezvani.mesh.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey
    val id: String,                 // UUID
    val conversationId: String,     // Contact nodeId or "channel_xxx"
    val senderId: String,           // 8-char hex NodeId
    val timestamp: Long,
    val type: Int,                  // MessageType value: 0=TEXT, 1=VOICE, 2=FILE_METADATA, 3=FILE_CHUNK
    val content: String,            // Text content or file path
    val isOutgoing: Boolean,
    val status: Int                 // 0=SENDING, 1=SENT, 2=DELIVERED, 3=READ, 4=FAILED
)

object MessageStatus {
    const val SENDING = 0
    const val SENT = 1
    const val DELIVERED = 2
    const val READ = 3
    const val FAILED = 4
}
