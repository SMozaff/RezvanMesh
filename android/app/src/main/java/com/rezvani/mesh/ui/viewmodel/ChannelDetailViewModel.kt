// android/app/src/main/java/com/rezvani/mesh/ui/viewmodel/ChannelDetailViewModel.kt

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

class ChannelDetailViewModel(application: Application) : AndroidViewModel(application) {

    private val dbPassphrase = DbKeyProvider.getOrCreateKey(application)
    private val messageRepo = MessageRepository(application, dbPassphrase)

    private val _messages = MutableStateFlow<List<MessageEntity>>(emptyList())
    val messages: StateFlow<List<MessageEntity>> = _messages.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

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

    fun sendMessage(channelId: Int, text: String) {
        viewModelScope.launch {
            _isSending.value = true
            try {
                // 1. Persist OUR message locally so it shows in the chat immediately.
                messageRepo.insertTextMessage(
                    conversationId = conversationId(channelId),
                    text = text,
                    isOutgoing = true
                )
                // 2. Hand off to the radio to encrypt (sender-key) + broadcast.
                MeshServiceConnection.activeService?.sendChannelMessage(channelId, text.toByteArray())
            } finally {
                _isSending.value = false
            }
        }
    }
}
