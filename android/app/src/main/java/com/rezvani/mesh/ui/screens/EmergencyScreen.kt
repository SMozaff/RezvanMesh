package com.rezvani.mesh.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rezvani.mesh.R
import com.rezvani.mesh.ui.components.SeverityPicker
import com.rezvani.mesh.ui.theme.MeshDimens
import com.rezvani.mesh.ui.theme.SemCritical
import com.rezvani.mesh.ui.theme.SignalBlue
import com.rezvani.mesh.ui.viewmodel.EmergencyViewModel
import com.rezvani.mesh.ui.viewmodel.EmergencySendStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmergencyScreen(viewModel: EmergencyViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val isSubmitting = uiState.sendStatus is EmergencySendStatus.Submitting
    var showConfirmDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.emergency_title), fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(
                    horizontal = MeshDimens.screenHorizontal,
                    vertical = MeshDimens.screenTop
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MeshDimens.itemGap)
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
                is EmergencySendStatus.Submitting -> {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = SemCritical,
                        trackColor = SemCritical.copy(alpha = 0.18f)
                    )
                    Text("Submitting alert to the local mesh…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is EmergencySendStatus.Queued -> {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = SignalBlue.copy(alpha = 0.14f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(status.message, style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = SignalBlue,
                                textAlign = TextAlign.Center)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Emergency broadcasts are signed but not encrypted. Queued does not mean delivered.",
                                style = MaterialTheme.typography.labelSmall,
                                color = SignalBlue.copy(alpha = 0.86f),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
                is EmergencySendStatus.Failed -> {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = SemCritical.copy(alpha = 0.14f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(status.message, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.padding(MeshDimens.cardPadding),
                            textAlign = TextAlign.Center)
                    }
                }
                else -> {}
            }

            // Single compact broadcast button
            Button(
                onClick = { showConfirmDialog = true },
                enabled = !isSubmitting,
                modifier = Modifier.fillMaxWidth().height(MeshDimens.primaryButtonHeight),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SemCritical,
                    contentColor = Color.White
                ),
                shape = MaterialTheme.shapes.large
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
            icon = { Icon(Icons.Default.Warning, null, tint = SemCritical) },
            title = { Text(stringResource(R.string.confirm_emergency)) },
            text = {
                Column {
                    Text(stringResource(R.string.emergency_confirmation_message))
                    Spacer(Modifier.height(12.dp))
                    // Remediation #1: surface the plaintext-broadcast tradeoff at
                    // the point of decision, not just after the fact.
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = SemCritical.copy(alpha = 0.14f)
                    ) {
                        Text(
                            stringResource(R.string.emergency_unencrypted_notice),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(MeshDimens.cardPadding)
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showConfirmDialog = false; viewModel.sendEmergencyAlert() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SemCritical,
                        contentColor = Color.White
                    )) {
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
