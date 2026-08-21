package com.rezvani.mesh.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rezvani.mesh.MeshServiceConnection
import com.rezvani.mesh.R
import com.rezvani.mesh.ui.theme.MeshAlpha
import com.rezvani.mesh.ui.theme.MeshDimens
import com.rezvani.mesh.ui.theme.MeshGreen
import com.rezvani.mesh.ui.theme.SemWarning
import com.rezvani.mesh.ui.viewmodel.PeerUiModel
import com.rezvani.mesh.ui.viewmodel.StatusViewModel
import com.rezvani.mesh.utils.BarcodeUtils
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkScreen(
    onOpenAdvanced: () -> Unit = {},
    viewModel: StatusViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    var showQrDialog by remember { mutableStateOf(false) }
    val ownNodeId by MeshServiceConnection.ownNodeId.collectAsState()
    val ownNodeIdHex = ownNodeId?.joinToString("") { "%02x".format(it) }.orEmpty()
    val batteryUnrestricted = rememberBatteryOptimisationExempt()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    // Battery in Network screen (moved from Chats)
                    BatteryStatusChip(
                        level = uiState.batteryLevel,
                        charging = uiState.isCharging
                    )
                    Spacer(Modifier.width(4.dp))
                    if (ownNodeIdHex.isNotBlank()) {
                        IconButton(onClick = { showQrDialog = true }) {
                            Icon(Icons.Default.QrCode2, contentDescription = stringResource(R.string.my_mesh_id))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                horizontal = MeshDimens.screenHorizontal,
                vertical = MeshDimens.screenTop
            ),
            verticalArrangement = Arrangement.spacedBy(MeshDimens.sectionGap)
        ) {
            if (!batteryUnrestricted) {
                item { BackgroundReliabilityWarning() }
            }
            item { MeshHero(active = uiState.active, peerCount = uiState.nodeCount) }
            item {
                StatusSummaryCard(
                    active = uiState.active,
                    peerCount = uiState.nodeCount,
                    detail = uiState.statusDetail,
                    signal = uiState.signalStrength
                )
            }
            item {
                Text(stringResource(R.string.nearby), style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface)
            }
            if (uiState.peers.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.no_devices_nearby),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(uiState.peers, key = { it.nodeIdHex }) { peer ->
                    PeerRow(peer = peer)
                }
            }
            item {
                OutlinedButton(onClick = onOpenAdvanced, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Analytics, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.advanced_network_monitoring))
                }
            }
        }
    }

    if (showQrDialog && ownNodeIdHex.isNotBlank()) {
        AlertDialog(
            onDismissRequest = { showQrDialog = false },
            title = { Text(stringResource(R.string.my_mesh_id)) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val bmp = remember(ownNodeIdHex) { BarcodeUtils.generateQrCodeBitmap(ownNodeIdHex) }
                    bmp?.let {
                        androidx.compose.foundation.Image(bitmap = it.asImageBitmap(),
                            contentDescription = stringResource(R.string.my_mesh_id), modifier = Modifier.size(200.dp))
                    }
                    Text(ownNodeIdHex, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { showQrDialog = false }) { Text(stringResource(R.string.close)) } }
        )
    }
}

@Composable
private fun rememberBatteryOptimisationExempt(): Boolean {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var exempt by remember(context) { mutableStateOf(isBatteryOptimisationExempt(context)) }

    DisposableEffect(context, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                exempt = isBatteryOptimisationExempt(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return exempt
}

private fun isBatteryOptimisationExempt(context: android.content.Context): Boolean =
    context.getSystemService(android.os.PowerManager::class.java)
        ?.isIgnoringBatteryOptimizations(context.packageName) == true

@Composable
private fun BackgroundReliabilityWarning() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = SemWarning.copy(alpha = 0.14f)
        )
    ) {
        Row(
            modifier = Modifier.padding(MeshDimens.cardPaddingLarge),
            horizontalArrangement = Arrangement.spacedBy(MeshDimens.itemGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.BatteryAlert,
                contentDescription = null,
                tint = SemWarning
            )
            Column {
                Text(
                    stringResource(R.string.background_reliability_limited),
                    style = MaterialTheme.typography.titleSmall,
                    color = SemWarning
                )
                Text(
                    stringResource(R.string.background_reliability_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = SemWarning
                )
            }
        }
    }
}

@Composable
fun BatteryStatusChip(level: Int, charging: Boolean) {
    val color = when {
        charging -> MaterialTheme.colorScheme.primary
        level <= 15 -> MaterialTheme.colorScheme.error
        level <= 30 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = color.copy(alpha = 0.14f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = if (charging) Icons.Default.BatteryChargingFull else
                    when {
                        level <= 15 -> Icons.Default.Battery1Bar
                        level <= 30 -> Icons.Default.Battery2Bar
                        level <= 60 -> Icons.Default.Battery4Bar
                        else -> Icons.Default.BatteryFull
                    },
                contentDescription = stringResource(R.string.battery),
                tint = color,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = "$level%",
                style = MaterialTheme.typography.labelSmall,
                color = color
            )
        }
    }
}

@Composable
private fun MeshHero(active: Boolean, peerCount: Int) {
    val statusDescription = if (active) {
        if (peerCount == 1) stringResource(R.string.peer_found)
        else stringResource(R.string.peers_found, peerCount)
    } else {
        stringResource(R.string.scanning)
    }
    // Scan rings: faster + brighter when peers found
    val ringSpeed = if (active) 1400 else 2200
    val ringAlpha = if (active) 0.55f else 0.30f
    val transition = rememberInfiniteTransition(label = "scan")
    val pulse by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(ringSpeed, easing = LinearEasing), RepeatMode.Restart),
        label = "pulse"
    )
    // Center node: breathes when active
    val centerScale by transition.animateFloat(
        initialValue = 1f, targetValue = if (active) 1.35f else 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "centerBreath"
    )
    // Peer node glow pulse
    val peerGlow by transition.animateFloat(
        initialValue = 6f, targetValue = if (active) 10f else 7f,
        animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "peerGlow"
    )
    val ringColor = MaterialTheme.colorScheme.primary
    val nodeColor = MaterialTheme.colorScheme.primary
    val centerColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
    val edgeColor = MaterialTheme.colorScheme.outline

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = statusDescription },
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = size.width / 2f; val cy = size.height / 2f
                val maxR = size.minDimension / 2f * 0.9f
                listOf(0f, 0.33f, 0.66f).forEach { phase ->
                    val p = (pulse + phase) % 1f
                    drawCircle(ringColor.copy(alpha = (1f - p) * ringAlpha), maxR * p, Offset(cx, cy), style = Stroke(if (active) 2.5f else 1.5f))
                }
                val shown = peerCount.coerceIn(0, 8)
                val orbit = maxR * 0.6f
                for (i in 0 until shown) {
                    val ang = (2.0 * Math.PI * i / maxOf(shown, 1)).toFloat()
                    val px = cx + orbit * cos(ang); val py = cy + orbit * sin(ang)
                    // Glowing edge line
                    drawLine(edgeColor.copy(alpha = if (active) 0.8f else 0.4f), Offset(cx, cy), Offset(px, py), if (active) 2f else 1.5f)
                    // Peer node with breathing glow halo
                    if (active) drawCircle(nodeColor.copy(alpha = 0.25f), peerGlow + 4f, Offset(px, py))
                    drawCircle(nodeColor, peerGlow, Offset(px, py))
                }
                // Center node with breathing scale
                val cr = 12f * centerScale
                if (active) drawCircle(centerColor.copy(alpha = 0.3f), cr + 6f, Offset(cx, cy))
                drawCircle(centerColor, cr, Offset(cx, cy))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(110.dp))
                // Animated peer count badge
                AnimatedContent(
                    targetState = if (active) {
                        if (peerCount == 1) stringResource(R.string.peer_found)
                        else stringResource(R.string.peers_found, peerCount)
                    } else {
                        stringResource(R.string.scanning)
                    },
                    transitionSpec = { fadeIn(tween(300)) + slideInVertically { it/2 } togetherWith fadeOut(tween(200)) },
                    label = "statusText"
                ) { text ->
                    Text(text,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (active) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
                        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun StatusSummaryCard(active: Boolean, peerCount: Int, detail: String, signal: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (active) MeshGreen.copy(alpha = 0.10f)
            else MaterialTheme.colorScheme.surface
        ),
        border = if (active) CardDefaults.outlinedCardBorder() else null
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(MeshDimens.cardPaddingLarge),
            verticalAlignment = Alignment.CenterVertically) {
            // Pulsing status dot - breathes when connected
        val dotTransition = rememberInfiniteTransition(label = "dot")
        val dotScale by dotTransition.animateFloat(
            initialValue = 1f, targetValue = if (active) 1.5f else 1f,
            animationSpec = infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "dotScale"
        )
        val dotColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
        Box(modifier = Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                // Outer glow halo
                if (active) drawCircle(dotColor.copy(alpha = 0.3f), 8f * dotScale)
                // Solid dot
                drawCircle(dotColor, 5f * dotScale)
            }
        }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (active) stringResource(R.string.nearby_count, peerCount)
                    else stringResource(R.string.no_peers_yet),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(signal, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PeerRow(peer: PeerUiModel) {
    val abbreviatedId = "${peer.nodeIdHex.take(4)}…${peer.nodeIdHex.takeLast(4)}"
    val connectionLabel = if (peer.connected) {
        stringResource(R.string.connected)
    } else {
        stringResource(R.string.nearby_connecting)
    }
    val signal = peer.rssi?.let { "$it dBm" } ?: "--"
    ListItem(
        headlineContent = { Text(stringResource(R.string.peer_format, abbreviatedId)) },
        supportingContent = { Text(connectionLabel, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant) },
        leadingContent = {
            Surface(
                shape = CircleShape,
                color = MeshGreen.copy(alpha = 0.14f),
                modifier = Modifier.size(MeshDimens.iconContainerSmall)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Smartphone, null, Modifier.size(18.dp),
                        tint = MeshGreen)
                }
            }
        },
        trailingContent = { Text(signal, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant) }
    )
}
