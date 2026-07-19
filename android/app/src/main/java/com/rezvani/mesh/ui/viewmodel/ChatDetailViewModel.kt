// android/app/src/main/java/com/rezvani/mesh/ui/viewmodel/ChatDetailViewModel.kt

package com.rezvani.mesh.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rezvani.mesh.MeshServiceConnection
import com.rezvani.mesh.data.DbKeyProvider
import com.rezvani.mesh.data.entities.MessageEntity
import com.rezvani.mesh.data.repositories.MessageRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ChatDetailViewModel(application: Application) : AndroidViewModel(application) {

    private val dbPassphrase = DbKeyProvider.getOrCreateKey(application)
    private val messageRepo = MessageRepository(application, dbPassphrase)

    private val _messages = MutableStateFlow<List<MessageEntity>>(emptyList())
    val messages: StateFlow<List<MessageEntity>> = _messages.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    fun loadMessages(conversationId: String) {
        viewModelScope.launch {
            messageRepo.getMessages(conversationId).collect {
                _messages.value = it
            }
        }
    }

    fun sendMessage(conversationId: String, text: String) {
        viewModelScope.launch {
            _isSending.value = true
            try {
                val recipient = hexToBytes(conversationId)
                if (recipient != null) {
                    // 1. Persist OUR message locally so it shows in the chat immediately.
                    //    conversationId here is the recipient's node-id hex (16 chars),
                    //    the same key the receiver will use (their senderId == our node id,
                    //    our recipient == their node id -> both sides key by the OTHER party).
                    messageRepo.insertTextMessage(
                        conversationId = conversationId,
                        text = text,
                        isOutgoing = true
                    )
                    // 2. Hand off to the radio to encrypt + transmit.
                    MeshServiceConnection.activeService?.sendMessage(recipient, text.toByteArray())
                }
            } finally {
                _isSending.value = false
            }
        }
    }

    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        return try {
            ByteArray(hex.length / 2) {
                hex.substring(it * 2, it * 2 + 2).toInt(16).toByte()
            }
        } catch (e: Exception) {
            null
        }
    }
}