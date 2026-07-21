// android/app/src/main/java/com/rezvani/mesh/ui/screens/StatusScreen.kt

package com.rezvani.mesh.ui.screens

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rezvani.mesh.BuildConfig
import com.rezvani.mesh.ui.viewmodel.StatusViewModel
import com.rezvani.mesh.utils.DiagLogger
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AdvancedNetworkScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: StatusViewModel = viewModel()
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val uiState by viewModel.uiState.collectAsState()

    var activeTag by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var showErrorsOnly by remember { mutableStateOf(false) }

    val filteredLogs = remember(uiState.logLines, activeTag, searchQuery, showErrorsOnly) {
        uiState.logLines.filter { line ->
            val tagOk = activeTag == null || line.contains("[${activeTag}/")
            val queryOk = searchQuery.isEmpty() || line.contains(searchQuery, ignoreCase = true)
            val errOk = !showErrorsOnly || line.contains("ERROR")
            tagOk && queryOk && errOk
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Advanced Monitoring") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },

            )
        }
    ) { scaffoldPadding ->
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(scaffoldPadding)
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // Build provenance banner
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = "Build: ${BuildConfig.GIT_SHA}  •  ${BuildConfig.GIT_BRANCH}  •  ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(BuildConfig.BUILD_TIME))} UTC\nLoopback=${BuildConfig.DEBUG_LOOPBACK}   Inject=${BuildConfig.DEBUG_INJECT_PEERS}",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Mesh status banner (long‑press mock peer)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { },
                    onLongClick = {
                        if (BuildConfig.DEBUG_INJECT_PEERS) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.injectMockPeer()
                        }
                    }
                ),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(14.dp)
        ) {
            Row(
                modifier = Modifier.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(
                            if (uiState.active) Color(0xFF4CAF50) else Color(0xFFFF9800)
                        )
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = if (uiState.active) "Mesh Active" else "Mesh Solo",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = uiState.statusDetail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Live data cards
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AdvLiveDataCard(Modifier.weight(1f), uiState.nodeCount.toString(), "Nodes Seen", Color(0xFF4CAF50))
            AdvLiveDataCard(Modifier.weight(1f), uiState.signalStrength, "Best RSSI", Color(0xFFFF9800))
        }

        // Radio status row
        val radio = uiState.radioSnapshot
        if (radio.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text("Radio", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Text("Scan: ${if (radio["scanning"] == 1L) "✅" else "❌"}", style = MaterialTheme.typography.bodySmall)
                Text("Adv: ${if (radio["advertising"] == 1L) "✅" else "❌"}", style = MaterialTheme.typography.bodySmall)
                Text("RX:${radio["rx_total"]} (L:${radio["rx_loopback"]} P:${radio["rx_peer"]})", style = MaterialTheme.typography.bodySmall)
                Text("TX:${radio["tx_starts"]}", style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Diagnostic section header + tools
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Diagnostic Log",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = {
                val file = exportLogs(context)
                if (file != null) {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "Export diagnostic log"))
                }
            }) {
                Icon(Icons.Filled.Share, contentDescription = "Export", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // Tag filter chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val tags = listOf(null to "ALL", "BLE" to "BLE", "RUST" to "RUST", "UI" to "UI", "SVC" to "SVC", "CRASH" to "CRASH")
            tags.forEach { (tag, label) ->
                FilterChip(
                    selected = activeTag == tag,
                    onClick = { activeTag = if (activeTag == tag) null else tag },
                    label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        // Search bar + error‑only toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Search logs…") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall
            )
            FilterChip(
                selected = showErrorsOnly,
                onClick = { showErrorsOnly = !showErrorsOnly },
                label = { Text("Errors", style = MaterialTheme.typography.labelSmall) }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Log list
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            itemsIndexed(filteredLogs, key = { index, _ -> index }) { _, line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (line.contains("ERROR")) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
    }
}

@Composable
fun AdvLiveDataCard(modifier: Modifier, value: String, label: String, color: Color) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = color)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun exportLogs(context: Context): File? {
    return try {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "rezvan-diag-$ts-${BuildConfig.GIT_SHA}.txt")
        file.writeText(
            DiagLogger.entries.value.joinToString("\n") { it.formatted() }
        )
        file
    } catch (e: Exception) {
        Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
        null
    }
}