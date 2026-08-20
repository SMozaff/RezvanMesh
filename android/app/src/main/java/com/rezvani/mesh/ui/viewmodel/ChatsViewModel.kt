package com.rezvani.mesh.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rezvani.mesh.data.ContactsRepository
import com.rezvani.mesh.data.DbKeyProvider
import com.rezvani.mesh.data.repositories.MessageRepository
import com.rezvani.mesh.ui.components.PowerState
import com.rezvani.mesh.ui.screens.ConversationItem
import com.rezvani.mesh.ui.screens.MessageStatus
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ChatsViewModel(application: Application) : AndroidViewModel(application) {

    private val dbPassphrase = DbKeyProvider.getOrCreateKey(application)
    private val messageRepo = MessageRepository(application, dbPassphrase)
    private val contactsRepo = ContactsRepository(application)

    private val _conversations = MutableStateFlow<List<ConversationItem>>(emptyList())
    val conversations: StateFlow<List<ConversationItem>> = _conversations.asStateFlow()

    private val _powerState = MutableStateFlow(PowerState.BALANCED)
    val powerState: StateFlow<PowerState> = _powerState.asStateFlow()

    private val _batteryLevel = MutableStateFlow(100)
    val batteryLevel: StateFlow<Int> = _batteryLevel.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    // Multi-select state
    private val _selectedConversations = MutableStateFlow<Set<String>>(emptySet())
    val selectedConversations: StateFlow<Set<String>> = _selectedConversations.asStateFlow()

    val isInSelectionMode: StateFlow<Boolean> = _selectedConversations
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    init {
        loadConversations()
    }

    private fun loadConversations() {
        viewModelScope.launch {
            combine(
                messageRepo.getConversationIds(),
                contactsRepo.contacts
            ) { ids, contacts ->
                ids to contacts
            }.collect { (ids, contacts) ->
                val contactMap = contacts.associateBy { it.nodeIdHex }
                val items = ids.map { convId ->
                    val lastMsg = messageRepo.getLastMessage(convId).first()
                    val unread = messageRepo.getUnreadCount(convId).first()
                    // Resolve display name: use saved contact name if known,
                    // otherwise fall back to the first 8 chars of the node ID.
                    val displayName = contactMap[convId]?.name ?: convId.take(8)
                    ConversationItem(
                        conversationId = convId,
                        contactName = displayName,
                        lastMessage = lastMsg?.content ?: "",
                        lastMessageTime = lastMsg?.timestamp ?: System.currentTimeMillis(),
                        unreadCount = unread,
                        status = when (lastMsg?.status) {
                            com.rezvani.mesh.data.entities.MessageStatus.QUEUED -> MessageStatus.SENDING
                            com.rezvani.mesh.data.entities.MessageStatus.FAILED -> MessageStatus.FAILED
                            com.rezvani.mesh.data.entities.MessageStatus.DELIVERED -> MessageStatus.DELIVERED
                            com.rezvani.mesh.data.entities.MessageStatus.READ -> MessageStatus.READ
                            else -> MessageStatus.SENT
                        }
                    )
                }
                _conversations.value = items
            }
        }
    }

    fun toggleSelection(conversationId: String) {
        val current = _selectedConversations.value.toMutableSet()
        if (conversationId in current) current.remove(conversationId) else current.add(conversationId)
        _selectedConversations.value = current
    }

    fun clearSelection() {
        _selectedConversations.value = emptySet()
    }

    fun deleteSelectedConversations() {
        viewModelScope.launch {
            _selectedConversations.value.forEach { id ->
                messageRepo.deleteConversation(id)
            }
            clearSelection()
        }
    }
}