package com.rezvani.mesh.rust

data class DecryptedMessage(
    val conversationId: ByteArray,
    val senderId: ByteArray,
    val timestamp: Long,
    val messageType: Byte,
    val content: ByteArray
)
