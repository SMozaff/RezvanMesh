// android/app/src/main/java/com/rezvani/mesh/ui/screens/VoiceScreen.kt

package com.rezvani.mesh.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rezvani.mesh.R
import com.rezvani.mesh.ui.components.SeverityPicker
import com.rezvani.mesh.ui.viewmodel.VoiceViewModel
import com.rezvani.mesh.ui.viewmodel.VoiceUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceScreen(
    onNavigateBack: () -> Unit,
    viewModel: VoiceViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.onPermissionGranted()
        } else {
            viewModel.onPermissionDenied()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.voice_broadcast_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.voice_broadcast_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.emergency_reception), modifier = Modifier.weight(1f))
            Switch(
                checked = uiState.receptionEnabled,
                onCheckedChange = { viewModel.toggleReception(it) }
            )
        }

        SeverityPicker(
            selectedLevel = uiState.severityLevel,
            onLevelSelected = { viewModel.setSeverity(it) },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                if (uiState.isRecording) {
                    viewModel.stopRecording()
                } else {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        viewModel.startRecording()
                    } else {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            },
            enabled = uiState.canRecord,
            modifier = Modifier
                .size(200.dp),
            shape = MaterialTheme.shapes.extraLarge,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (uiState.isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (uiState.isRecording) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        color = MaterialTheme.colorScheme.onError,
                        strokeWidth = 4.dp
                    )
                    Text(stringResource(R.string.release_to_send), color = MaterialTheme.colorScheme.onError)
                } else {
                    Text(stringResource(R.string.hold_label), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.to_talk_label), style = MaterialTheme.typography.headlineSmall)
                }
            }
        }

        val status = uiState.status
        when (status) {
            is VoiceUiState.Status.Ready -> {}
            is VoiceUiState.Status.Recording -> Text(stringResource(R.string.recording_status), color = MaterialTheme.colorScheme.error)
            is VoiceUiState.Status.Sending -> Text(stringResource(R.string.sending_status))
            is VoiceUiState.Status.Sent -> Text(stringResource(R.string.sent_status), color = MaterialTheme.colorScheme.primary)
            is VoiceUiState.Status.Error -> Text(status.message, color = MaterialTheme.colorScheme.error)
        }
    }
    }
}