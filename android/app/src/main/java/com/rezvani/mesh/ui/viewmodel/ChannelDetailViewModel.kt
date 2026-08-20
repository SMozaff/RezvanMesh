// android/app/src/main/java/com/rezvani/mesh/ui/viewmodel/ChannelDetailViewModel.kt

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

class ChannelDetailViewModel(application: Application) : AndroidViewModel(application) {

    private val dbPassphrase = DbKeyProvider.getOrCreateKey(application)
    private val messageRepo = MessageRepository(application, dbPassphrase)

    private val _messages = MutableStateFlow<List<MessageEntity>>(emptyList())
    val messages: StateFlow<List<MessageEntity>> = _messages.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    private val _sendError = MutableStateFlow<String?>(null)
    val sendError: StateFlow<String?> = _sendError.asStateFlow()

    /** Conversation key used for persistence -- must match engine.rs's 0x06
     * handler and MeshServiceConnection.addReceivedMessage's convention. */
    private fun conversationId(channelId: Int) = "channel_$channelId"

    fun loadMessages(channelId: Int) {
        viewModelScope.launch {
            messageRepo.getMessages(conversationId(channelId)).collect {
                _messages.value = it
            }
        }
    }

    fun clearSendError() {
        _sendError.value = null
    }

    fun retryMessage(message: MessageEntity) {
        if (!message.isOutgoing || message.status != MessageStatus.FAILED) return

        viewModelScope.launch {
            if (_isSending.value) return@launch
            _isSending.value = true
            _sendError.value = null
            try {
                val channelId = message.conversationId
                    .removePrefix("channel_")
                    .toIntOrNull()
                if (channelId == null || !message.conversationId.startsWith("channel_")) {
                    _sendError.value = "This channel message has an invalid channel ID."
                    return@launch
                }

                messageRepo.updateStatus(message.id, MessageStatus.QUEUED)
                val result = MeshServiceConnection.activeService
                    ?.sendChannelMessage(channelId, message.content.toByteArray())
                    ?: SendResult.NotReady
                if (result !is SendResult.Queued) {
                    messageRepo.updateStatus(message.id, MessageStatus.FAILED)
                    _sendError.value = result.failureMessage()
                }
            } catch (error: Exception) {
                messageRepo.updateStatus(message.id, MessageStatus.FAILED)
                _sendError.value = error.message ?: "Channel message could not be queued"
            } finally {
                _isSending.value = false
            }
        }
    }

    fun sendMessage(channelId: Int, text: String) {
        viewModelScope.launch {
            if (_isSending.value) return@launch
            _isSending.value = true
            _sendError.value = null
            try {
                val id = messageRepo.insertTextMessage(
                    conversationId = conversationId(channelId),
                    text = text,
                    isOutgoing = true
                )
                val result = MeshServiceConnection.activeService
                    ?.sendChannelMessage(channelId, text.toByteArray())
                    ?: SendResult.NotReady
                if (result !is SendResult.Queued) {
                    messageRepo.updateStatus(id, MessageStatus.FAILED)
                    _sendError.value = result.failureMessage()
                }
            } catch (e: Exception) {
                _sendError.value = e.message ?: "Channel message could not be queued"
            } finally {
                _isSending.value = false
            }
        }
    }
}
