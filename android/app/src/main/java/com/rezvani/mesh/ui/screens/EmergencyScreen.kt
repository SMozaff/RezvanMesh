package com.rezvani.mesh.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rezvani.mesh.R
import com.rezvani.mesh.ui.components.SeverityPicker
import com.rezvani.mesh.ui.viewmodel.EmergencyViewModel
import com.rezvani.mesh.ui.viewmodel.EmergencySendStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmergencyScreen(viewModel: EmergencyViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    var showConfirmDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.emergency_title), fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    titleContentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Compact severity picker
            SeverityPicker(
                selectedLevel = uiState.selectedSeverity,
                onLevelSelected = { viewModel.updateSeverity(it) },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.weight(1f))

            // Status feedback (compact)
            when (val status = uiState.sendStatus) {
                is EmergencySendStatus.Sending -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.sending_emergency),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is EmergencySendStatus.Success -> {
                    Surface(shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(status.message, style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                textAlign = TextAlign.Center)
                            // Remediation #1: SOS/emergency broadcasts are always
                            // signed-only, never encrypted (MeshEngine::send_broadcast,
                            // packet type 0x03) -- by design, so the alert reaches
                            // everyone immediately without needing a prior secure
                            // session. Make that visible here rather than leaving it
                            // as an assumption only documented in code.
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.emergency_sent_unencrypted_badge),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
                is EmergencySendStatus.Failed -> {
                    Surface(shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(status.message, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp),
                            textAlign = TextAlign.Center)
                    }
                }
                else -> {}
            }

            // Single compact broadcast button
            Button(
                onClick = { showConfirmDialog = true },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                shape = MaterialTheme.shapes.medium
            ) {
                Icon(Icons.Default.Warning, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.send_emergency), fontWeight = FontWeight.Bold)
            }

            Text(
                stringResource(R.string.emergency_disclaimer),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }

    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            icon = { Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.confirm_emergency)) },
            text = {
                Column {
                    Text(stringResource(R.string.emergency_confirmation_message))
                    Spacer(Modifier.height(12.dp))
                    // Remediation #1: surface the plaintext-broadcast tradeoff at
                    // the point of decision, not just after the fact.
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Text(
                            stringResource(R.string.emergency_unencrypted_notice),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showConfirmDialog = false; viewModel.sendEmergencyAlert() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                    Text(stringResource(R.string.send_emergency), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}
