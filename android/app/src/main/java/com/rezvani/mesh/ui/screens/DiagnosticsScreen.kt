// android/app/src/main/java/com/rezvani/mesh/ui/screens/DiagnosticsScreen.kt

@file:OptIn(ExperimentalMaterial3Api::class)
package com.rezvani.mesh.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rezvani.mesh.ui.viewmodel.DiagnosticsViewModel

@Composable
fun DiagnosticsScreen(
    onNavigateBack: (() -> Unit)? = null,
    viewModel: DiagnosticsViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics") },
                navigationIcon = {
                    if (onNavigateBack != null) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Self‑Test Harness",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Button(
                onClick = { viewModel.runAllAutomatable() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
            ) {
                Text("Run All Automatable Tests")
            }

            HorizontalDivider()
            Text(
                text = "A. Crypto / wire pipeline (no second device needed)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            TestButton("Fragment + reassemble", uiState.fragStatus) {
                viewModel.runFragmentationTest()
            }
            TestButton("1:1 crypto round-trip (A → B)", uiState.cryptoStatus) {
                viewModel.runTwoEngineCryptoTest()
            }
            TestButton("KeyAnnouncement spoofing rejection", uiState.spoofRejectStatus) {
                viewModel.runSpoofRejectionTest()
            }
            TestButton("Beacon MAC authentication", uiState.beaconAuthStatus) {
                viewModel.runBeaconAuthTest()
            }
            TestButton("Emergency broadcast round-trip", uiState.broadcastStatus) {
                viewModel.runBroadcastTest()
            }
            TestButton("Channel messaging round-trip", uiState.channelStatus) {
                viewModel.runChannelTest()
            }
            TestButton("Wire-version mismatch rejection", uiState.versionGateStatus) {
                viewModel.runVersionGateTest()
            }
            TestButton("One-time-key rotation", uiState.otkRotationStatus) {
                viewModel.runOtkRotationTest()
            }

            HorizontalDivider()
            Text(
                text = "B. Persistence",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            TestButton("Database passphrase + open", uiState.dbStatus) {
                viewModel.runDbTest()
            }

            HorizontalDivider()
            Text(
                text = "C. Radio / device capability",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            TestButton("BLE capability + permissions", uiState.bleCapabilityStatus) {
                viewModel.checkBleCapability()
            }
            TestButton("WiFi-Direct capability", uiState.wifiDirectCapabilityStatus) {
                viewModel.checkWifiDirectCapability()
            }
            TestButton("Full permission audit", uiState.permissionsStatus) {
                viewModel.checkAllPermissions()
            }
            TestButton("Loopback capture (10s, needs 2nd device)", uiState.loopbackStatus) {
                viewModel.runLoopbackTest()
            }
            TestButton("Inject 5 mock peers", uiState.injectStatus) {
                viewModel.injectMockPeers(5)
            }
            TestButton("Show routing table", uiState.routingStatus) {
                viewModel.showRoutingTable()
            }

            HorizontalDivider()
            Text(
                text = "D. QR / channel codec",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            TestButton("Channel QR codec round-trip", uiState.qrCodecStatus) {
                viewModel.runQrCodecTest()
            }
            TestButton("QR bitmap generation", uiState.qrGenerateStatus) {
                viewModel.runQrGenerateTest()
            }

            if (uiState.outputText.isNotBlank()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = uiState.outputText,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun TestButton(label: String, status: TestStatus, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        enabled = status != TestStatus.RUNNING
    ) {
        Text(label)
        Spacer(Modifier.width(8.dp))
        when (status) {
            TestStatus.IDLE -> {}
            TestStatus.RUNNING -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            TestStatus.PASS -> Text("✅")
            TestStatus.FAIL -> Text("❌")
        }
    }
}

enum class TestStatus { IDLE, RUNNING, PASS, FAIL }