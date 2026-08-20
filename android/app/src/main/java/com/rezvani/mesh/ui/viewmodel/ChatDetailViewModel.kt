// android/app/src/main/java/com/rezvani/mesh/ui/viewmodel/ChatDetailViewModel.kt

package com.rezvani.mesh.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rezvani.mesh.MeshServiceConnection
import com.rezvani.mesh.data.DbKeyProvider
import com.rezvani.mesh.data.entities.MessageEntity
import com.rezvani.mesh.data.entities.MessageStatus
import com.rezvani.mesh.data.repositories.MessageRepository
import com.rezvani.mesh.radio.SendResult
import com.rezvani.mesh.radio.failureMessage
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ChatDetailViewModel(application: Application) : AndroidViewModel(application) {

    private val dbPassphrase = DbKeyProvider.getOrCreateKey(application)
    private val messageRepo = MessageRepository(application, dbPassphrase)

    private val _messages = MutableStateFlow<List<MessageEntity>>(emptyList())
    val messages: StateFlow<List<MessageEntity>> = _messages.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    private val _sendError = MutableStateFlow<String?>(null)
    val sendError: StateFlow<String?> = _sendError.asStateFlow()

    fun loadMessages(conversationId: String) {
        viewModelScope.launch {
            messageRepo.getMessages(conversationId).collect {
                _messages.value = it
            }
        }
    }

    fun clearSendError() {
        _sendError.value = null
    }

    /**
     * Persists the local message first for offline-first continuity, then
     * marks it failed if the local mesh cannot accept it. A queued message is
     * intentionally not promoted to sent or delivered.
     */
    fun sendMessage(conversationId: String, text: String) {
        viewModelScope.launch {
            if (_isSending.value) return@launch
            _isSending.value = true
            _sendError.value = null
            try {
                val recipient = hexToBytes(conversationId)
                if (recipient == null || recipient.size != 8) {
                    _sendError.value = "This contact has an invalid mesh ID. Message was not queued."
                    return@launch
                }

                val messageId = messageRepo.insertTextMessage(
                    conversationId = conversationId,
                    text = text,
                    isOutgoing = true
                )
                val result = MeshServiceConnection.activeService
                    ?.sendMessage(recipient, text.toByteArray())
                    ?: SendResult.NotReady

                if (result !is SendResult.Queued) {
                    messageRepo.updateStatus(messageId, MessageStatus.FAILED)
                    _sendError.value = result.failureMessage()
                }
            } catch (e: Exception) {
                _sendError.value = e.message ?: "Message could not be queued"
            } finally {
                _isSending.value = false
            }
        }
    }

    /**
     * Retries the same persisted outgoing record after a local submission
     * failure. A retry never upgrades the message beyond [MessageStatus.QUEUED]
     * because the current protocol has no remote acknowledgement signal.
     */
    fun retryMessage(message: MessageEntity) {
        if (!message.isOutgoing || message.status != MessageStatus.FAILED) return

        viewModelScope.launch {
            if (_isSending.value) return@launch
            _isSending.value = true
            _sendError.value = null
            try {
                val recipient = hexToBytes(message.conversationId)
                if (recipient == null || recipient.size != 8) {
                    _sendError.value = "This contact has an invalid mesh ID. Message was not queued."
                    return@launch
                }

                // Reset to the truthful local pending state before attempting
                // resubmission. Any rejection below restores FAILED.
                messageRepo.updateStatus(message.id, MessageStatus.QUEUED)
                val result = MeshServiceConnection.activeService
                    ?.sendMessage(recipient, message.content.toByteArray())
                    ?: SendResult.NotReady

                if (result !is SendResult.Queued) {
                    messageRepo.updateStatus(message.id, MessageStatus.FAILED)
                    _sendError.value = result.failureMessage()
                }
            } catch (error: Exception) {
                messageRepo.updateStatus(message.id, MessageStatus.FAILED)
                _sendError.value = error.message ?: "Message could not be queued"
            } finally {
                _isSending.value = false
            }
        }
    }

    private fun hexToBytes(hex: String): ByteArray? {
        if (!hex.matches(Regex("^[0-9A-Fa-f]{16}$"))) return null
        return ByteArray(hex.length / 2) {
            hex.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
    }
}
