package com.rezvani.mesh.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rezvani.mesh.R
import com.rezvani.mesh.ui.viewmodel.ChannelsViewModel
import com.rezvani.mesh.utils.BarcodeUtils
import com.rezvani.mesh.utils.ChannelQrCodec
import kotlinx.coroutines.flow.StateFlow

/**
 * Channel-creation form. On success, shows the generated shared sender-key
 * (see ChannelsViewModel.createChannel / MeshEngine::create_channel_key) as
 * both a scannable QR code and hex text, for the user to share out-of-band
 * with other members -- there is no automated key-distribution mechanism
 * (see sender_key.rs's documented scope), so this is the manual fallback.
 * Also supports scanning another member's channel QR to join, via
 * RezvanMesh's own built-in scanner (see QrScannerScreen.kt).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateChannelScreen(
    onNavigateBack: () -> Unit,
    onChannelCreated: () -> Unit,
    onScanChannelQr: () -> Unit = {},
    channelQrScanResult: StateFlow<String?>? = null,
    viewModel: ChannelsViewModel = viewModel()
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var isPrivate by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var joinError by remember { mutableStateOf<String?>(null) }

    val createdKey by viewModel.lastCreatedChannelKey.collectAsState()

    val scanResult by (channelQrScanResult?.collectAsState() ?: remember { mutableStateOf(null) })
    LaunchedEffect(scanResult) {
        val scanned = scanResult
        if (scanned != null) {
            val share = ChannelQrCodec.decode(scanned)
            if (share != null) {
                viewModel.joinChannelWithKey(share.channelId, share.key)
                onChannelCreated()
            } else {
                joinError = scanned
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.create_channel)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { paddingValues ->
        if (createdKey != null) {
            // Channel was just created -- show the share key before leaving.
            val (channelId, key) = createdKey!!
            val keyHex = key.joinToString("") { "%02x".format(it) }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.channel_created_title),
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = stringResource(R.string.channel_created_share_key_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.channel_id_label, channelId),
                            style = MaterialTheme.typography.labelLarge
                        )
                        Spacer(Modifier.height(12.dp))
                        val qrPayload = remember(channelId, keyHex) { ChannelQrCodec.encode(channelId, key) }
                        val bmp = remember(qrPayload) { BarcodeUtils.generateQrCodeBitmap(qrPayload) }
                        bmp?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = stringResource(R.string.scan_channel_qr),
                                modifier = Modifier.size(220.dp)
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        SelectionContainerCompat(keyHex)
                    }
                }
                Button(
                    onClick = {
                        viewModel.clearLastCreatedChannelKey()
                        onChannelCreated()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.done))
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.channel_name_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.channel_description_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = isPrivate, onCheckedChange = { isPrivate = it })
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.private_channel))
                }
                if (isPrivate) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.channel_password_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
                Button(
                    onClick = {
                        viewModel.createChannel(
                            name = name,
                            description = description,
                            isPrivate = isPrivate,
                            password = password.ifBlank { null }
                        )
                    },
                    enabled = name.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.create_channel))
                }

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    HorizontalDivider(modifier = Modifier.weight(1f))
                    Text(
                        text = "  or  ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HorizontalDivider(modifier = Modifier.weight(1f))
                }

                OutlinedButton(
                    onClick = onScanChannelQr,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.join_with_qr))
                }
            }
        }

        if (joinError != null) {
            AlertDialog(
                onDismissRequest = { joinError = null },
                title = { Text(stringResource(R.string.invalid_channel_qr)) },
                text = { Text(joinError ?: "") },
                confirmButton = {
                    TextButton(onClick = { joinError = null }) { Text(stringResource(R.string.done)) }
                }
            )
        }
    }
}

/** Selectable, wrapping monospace text for the key hex -- kept as a small
 * local helper rather than pulling in a new dependency for text selection. */
@Composable
private fun SelectionContainerCompat(text: String) {
    androidx.compose.foundation.text.selection.SelectionContainer {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        )
    }
}
