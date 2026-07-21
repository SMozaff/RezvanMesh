package com.rezvani.mesh.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rezvani.mesh.R
import com.rezvani.mesh.ui.components.EmptyState
import com.rezvani.mesh.ui.components.PowerState
import com.rezvani.mesh.ui.components.PowerStateIndicator
import com.rezvani.mesh.ui.viewmodel.ChatsViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatsScreen(
    onConversationClick: (String, String) -> Unit,
    onNewMessageClick: () -> Unit,
    onNewChannelClick: () -> Unit,
    onEmergencyClick: () -> Unit,
    onVoiceClick: () -> Unit,
    viewModel: ChatsViewModel = viewModel()
) {
    val conversations by viewModel.conversations.collectAsState(initial = emptyList())
    val powerState by viewModel.powerState.collectAsState(initial = PowerState.BALANCED)
    val isRefreshing by viewModel.isRefreshing.collectAsState(initial = false)
    val selectedConversations by viewModel.selectedConversations.collectAsState()
    val isInSelectionMode by viewModel.isInSelectionMode.collectAsState()

    // Intercept back press during selection mode
    if (isInSelectionMode) {
        androidx.activity.compose.BackHandler { viewModel.clearSelection() }
    }

    Scaffold(
        topBar = {
            if (isInSelectionMode) {
                // Selection-mode top bar
                TopAppBar(
                    title = { Text(stringResource(R.string.n_selected, selectedConversations.size)) },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel_selection))
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.deleteSelectedConversations() }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.delete_selected),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            } else {
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.chats),
                                style = MaterialTheme.typography.titleLarge
                            )
                            PowerStateIndicator(powerState = powerState)
                        }
                    },
                    actions = {
                        IconButton(onClick = onNewChannelClick) {
                            Icon(Icons.Default.Forum, contentDescription = stringResource(R.string.new_channel))
                        }
                        IconButton(onClick = onVoiceClick) {
                            Icon(Icons.Default.Mic, contentDescription = stringResource(R.string.voice_broadcast_title))
                        }
                        IconButton(onClick = onEmergencyClick) {
                            Icon(Icons.Default.Warning, contentDescription = stringResource(R.string.emergency_title))
                        }
                        IconButton(onClick = onNewMessageClick) {
                            Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.new_message))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        },
        floatingActionButton = {
            if (!isInSelectionMode) {
                FloatingActionButton(
                    onClick = onNewMessageClick,
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.new_message))
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                isRefreshing && conversations.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                conversations.isEmpty() -> {
                    EmptyState(
                        icon = Icons.Default.ChatBubbleOutline,
                        message = stringResource(R.string.no_conversations_yet),
                        actionText = stringResource(R.string.new_message),
                        onAction = onNewMessageClick
                    )
                }
                else -> {
                    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                        items(
                            items = conversations,
                            key = { it.conversationId }
                        ) { conversation ->
                            val isSelected = conversation.conversationId in selectedConversations
                            ConversationListItem(
                                conversation = conversation,
                                isSelected = isSelected,
                                isInSelectionMode = isInSelectionMode,
                                onClick = {
                                    if (isInSelectionMode) {
                                        viewModel.toggleSelection(conversation.conversationId)
                                    } else {
                                        onConversationClick(
                                            conversation.conversationId,
                                            conversation.contactName
                                        )
                                    }
                                },
                                onLongClick = {
                                    viewModel.toggleSelection(conversation.conversationId)
                                }
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 72.dp),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                        }
                    }
                }
            }
        }
    }
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConversationListItem(
    conversation: ConversationItem,
    isSelected: Boolean,
    isInSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val dateString = dateFormat.format(Date(conversation.lastMessageTime))
    val hasUnread = conversation.unreadCount > 0

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                hasUnread  -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                else       -> MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar / checkbox
            if (isInSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = null,
                    modifier = Modifier.size(48.dp)
                )
            } else {
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = if (hasUnread)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = conversation.contactName.take(1).uppercase(),
                            style = MaterialTheme.typography.titleLarge,
                            color = if (hasUnread)
                                MaterialTheme.colorScheme.onPrimary
                            else
                                MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = conversation.contactName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (hasUnread) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = dateString,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = conversation.lastMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (hasUnread)
                            MaterialTheme.colorScheme.onSurface
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    when {
                        hasUnread -> {
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                                Text(
                                    text = conversation.unreadCount.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                        conversation.status == MessageStatus.SENDING -> {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        }
                        conversation.status == MessageStatus.FAILED -> {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = stringResource(R.string.failed),
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

data class ConversationItem(
    val conversationId: String,
    val contactName: String,
    val lastMessage: String,
    val lastMessageTime: Long,
    val unreadCount: Int,
    val status: MessageStatus
)

enum class MessageStatus { SENDING, SENT, DELIVERED, READ, FAILED }