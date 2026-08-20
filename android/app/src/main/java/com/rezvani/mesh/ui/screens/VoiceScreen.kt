// android/app/src/main/java/com/rezvani/mesh/ui/screens/VoiceScreen.kt

package com.rezvani.mesh.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Voice/PTT is intentionally unavailable until its authenticated envelope,
 * production fragmentation/reassembly, receive policy, and physical-device
 * verification are complete. Keeping the route safe prevents a UI control from
 * creating false confidence in an unverified safety feature.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceScreen() {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Voice Broadcast") }) }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(40.dp)
                    )
                    Text("Voice broadcast is unavailable", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Voice and push-to-talk will be enabled only after the complete send, receive, and delivery-status pipeline has been validated on physical devices.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
