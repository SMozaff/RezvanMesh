package com.rezvani.mesh.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rezvani.mesh.R
import com.rezvani.mesh.data.entities.ChannelEntity
import com.rezvani.mesh.ui.components.PasswordInputDialog
import com.rezvani.mesh.ui.viewmodel.ChannelsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelsScreen(
    onChannelClick: (Int, String) -> Unit,
    onCreateChannel: () -> Unit,
    viewModel: ChannelsViewModel = viewModel()
) {
    val channels by viewModel.allChannels.collectAsState(initial = emptyList())
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    var selectedChannel by remember { mutableStateOf<ChannelEntity?>(null) }
    var showPasswordDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.refreshChannels()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.channels)) },
                actions = {
                    IconButton(
                        onClick = { viewModel.refreshChannels() },
                        enabled = !isRefreshing
                    ) {
                        if (isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.refresh)
                            )
                        }
                    }
                    IconButton(onClick = onCreateChannel) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = stringResource(R.string.create_channel)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (channels.isEmpty() && !isRefreshing) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.no_channels_found),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = { viewModel.refreshChannels() }
                        ) {
                            Text(stringResource(R.string.scan_for_channels))
                        }
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(channels, key = { it.channelId }) { channel ->
                        ChannelListItem(
                            channel = channel,
                            onClick = {
                                if (channel.isPrivate) {
                                    selectedChannel = channel
                                    showPasswordDialog = true
                                } else {
                                    onChannelClick(channel.channelId, channel.name)
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (showPasswordDialog && selectedChannel != null) {
        PasswordInputDialog(
            channelName = selectedChannel!!.name,
            onConfirm = { password ->
                viewModel.joinPrivateChannel(
                    channelId = selectedChannel!!.channelId,
                    password = password,
                    onSuccess = {
                        showPasswordDialog = false
                        onChannelClick(selectedChannel!!.channelId, selectedChannel!!.name)
                    },
                    onError = { /* Show error */ }
                )
            },
            onDismiss = {
                showPasswordDialog = false
                selectedChannel = null
            }
        )
    }
}

@Composable
fun ChannelListItem(
    channel: ChannelEntity,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (channel.isPrivate) Icons.Default.Lock else Icons.Default.Public,
                contentDescription = if (channel.isPrivate)
                    stringResource(R.string.private_channel)
                else
                    stringResource(R.string.public_channel),
                tint = if (channel.isPrivate)
                    MaterialTheme.colorScheme.secondary
                else
                    MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.titleMedium
                )
                if (channel.description.isNotBlank()) {
                    Text(
                        text = channel.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.members_count, channel.memberCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (channel.isPrivate) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                text = stringResource(R.string.private_caps),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            Text(
                text = "#${channel.channelId}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
