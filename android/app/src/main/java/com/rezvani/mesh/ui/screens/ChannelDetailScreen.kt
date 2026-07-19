package com.rezvani.mesh.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rezvani.mesh.R
import com.rezvani.mesh.ui.viewmodel.ChannelDetailViewModel
import kotlinx.coroutines.launch

/**
 * Channel (group) chat screen. Mirrors ChatDetailScreen's structure and
 * reuses its MessageBubble/MessageStatusIndicator composables -- the only
 * difference from a 1:1 chat is the conversation key ("channel_<id>" instead
 * of a peer NodeId hex) and that sending goes through
 * ChannelDetailViewModel.sendMessage, which calls the sender-key-encrypted
 * broadcast path (engine.rs's 0x06 packet type) instead of the Olm 1:1 path.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelDetailScreen(
    channelId: Int,
    channelName: String,
    onNavigateBack: () -> Unit,
    viewModel: ChannelDetailViewModel = viewModel()
) {
    val messages by viewModel.messages.collectAsState(initial = emptyList())
    val isSending by viewModel.isSending.collectAsState()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var messageText by remember { mutableStateOf("") }

    LaunchedEffect(channelId) { viewModel.loadMessages(channelId) }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(0)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "# $channelName",
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        bottomBar = {
            Surface(modifier = Modifier.fillMaxWidth(), tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .navigationBarsPadding()
                        .imePadding(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = messageText,
                        onValueChange = { messageText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(stringResource(R.string.type_message)) },
                        shape = RoundedCornerShape(24.dp),
                        maxLines = 4,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                        )
                    )
                    FilledIconButton(
                        onClick = {
                            if (messageText.isNotBlank() && !isSending) {
                                coroutineScope.launch {
                                    viewModel.sendMessage(channelId, messageText)
                                    messageText = ""
                                }
                            }
                        },
                        enabled = messageText.isNotBlank() && !isSending
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.send))
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (messages.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.no_messages_yet),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(messages, key = { it.id }) { message ->
                        // Reused directly from ChatDetailScreen.kt -- same
                        // MessageEntity shape, same bubble rendering.
                        MessageBubble(message = message)
                    }
                }
            }
        }
    }
}
