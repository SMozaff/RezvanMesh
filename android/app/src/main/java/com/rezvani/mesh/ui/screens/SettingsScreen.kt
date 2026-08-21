package com.rezvani.mesh.ui.screens

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rezvani.mesh.R
import com.rezvani.mesh.ui.components.ConfirmationDialog
import com.rezvani.mesh.ui.components.PowerState
import com.rezvani.mesh.ui.theme.MeshDimens
import com.rezvani.mesh.ui.theme.MeshGreen
import com.rezvani.mesh.ui.theme.MeshPurple
import com.rezvani.mesh.ui.theme.SemCritical
import com.rezvani.mesh.ui.theme.SemWarning
import com.rezvani.mesh.ui.theme.SignalBlue
import com.rezvani.mesh.ui.viewmodel.SettingsViewModel
import com.rezvani.mesh.utils.LocaleHelper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToAdvanced: (() -> Unit)? = null,
    onNavigateToDiagnostics: (() -> Unit)? = null,
    viewModel: SettingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()

    var showLanguageDialog by remember { mutableStateOf(false) }
    var showPowerDialog by remember { mutableStateOf(false) }
    var showClearDataDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showCrashInfoDialog by remember { mutableStateOf(false) }

    // Hidden developer section: tap version 5x to reveal
    var versionTapCount by remember { mutableStateOf(0) }
    val showDeveloperSection = versionTapCount >= 5

    Scaffold(
        topBar = {
            // Settings is a tab destination - no back arrow
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
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
                .verticalScroll(scrollState)
        ) {
            // ---- Identity ----
            SettingsSection(title = stringResource(R.string.identity)) {
                SettingsItem(
                    icon = Icons.Default.Person,
                    title = stringResource(R.string.node_id),
                    subtitle = uiState.nodeId,
                    tint = MeshGreen
                )
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // ---- Appearance ----
            SettingsSection(title = stringResource(R.string.appearance)) {
                SettingsItem(
                    icon = Icons.Default.DarkMode,
                    title = stringResource(R.string.theme),
                    tint = MeshPurple,
                    subtitle = if (uiState.darkMode) stringResource(R.string.dark) else stringResource(R.string.light),
                    onClick = { viewModel.toggleDarkMode() }
                )
                SettingsItem(
                    icon = Icons.Default.Language,
                    title = stringResource(R.string.language),
                    tint = SignalBlue,
                    subtitle = when (uiState.currentLanguage) {
                        "fa" -> stringResource(R.string.language_farsi)
                        "ar" -> stringResource(R.string.language_arabic)
                        "ur" -> stringResource(R.string.language_urdu)
                        "ks" -> stringResource(R.string.language_kashmiri)
                        else -> stringResource(R.string.language_english)
                    },
                    onClick = { showLanguageDialog = true }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // ---- Network ----
            SettingsSection(title = "Network") {
                SettingsItem(
                    icon = Icons.Default.Analytics,
                    title = "Advanced Network Monitoring",
                    tint = MeshGreen,
                    subtitle = "Live radio stats, peers, and diagnostic logs",
                    onClick = { onNavigateToAdvanced?.invoke() }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // ---- Power & Battery ----
            SettingsSection(title = stringResource(R.string.power_battery)) {
                SettingsItem(
                    icon = Icons.Default.BatteryStd,
                    title = stringResource(R.string.power_profile),
                    tint = SemWarning,
                    subtitle = when (uiState.powerOverride ?: uiState.autoPowerState) {
                        PowerState.EMERGENCY  -> stringResource(R.string.power_emergency)
                        PowerState.ACTIVE     -> stringResource(R.string.power_active)
                        PowerState.BALANCED   -> stringResource(R.string.power_balanced)
                        PowerState.POWER_SAVER -> stringResource(R.string.power_saver)
                        PowerState.MINIMAL    -> stringResource(R.string.power_minimal)
                        PowerState.HIBERNATION -> stringResource(R.string.power_hibernation)
                        PowerState.DEAD       -> stringResource(R.string.power_dead)
                    },
                    onClick = { showPowerDialog = true }
                )
                if (uiState.powerOverride != null) {
                    TextButton(
                        onClick = { viewModel.clearPowerOverride() },
                        modifier = Modifier.padding(start = 72.dp)
                    ) {
                        Text(stringResource(R.string.reset_to_auto))
                    }
                }
                SettingsItem(
                    icon = Icons.Default.Settings,
                    title = stringResource(R.string.system_power_settings),
                    subtitle = stringResource(R.string.system_power_settings_description),
                    onClick = { viewModel.openSystemPowerSettings() }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // ---- Storage ----
            SettingsSection(title = stringResource(R.string.storage)) {
                SettingsItem(
                    icon = Icons.Default.Storage,
                    title = stringResource(R.string.storage_used),
                    subtitle = uiState.storageUsed
                )
                SettingsItem(
                    icon = Icons.Default.Delete,
                    title = stringResource(R.string.clear_old_messages),
                    subtitle = stringResource(R.string.clear_messages_description),
                    onClick = { showClearDataDialog = true },
                    tint = SemCritical
                )
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // ---- About ----
            SettingsSection(title = stringResource(R.string.about)) {
                SettingsItem(
                    icon = Icons.Default.Info,
                    title = stringResource(R.string.app_version),
                    subtitle = uiState.versionName,
                    onClick = {
                        versionTapCount++
                        showAboutDialog = true
                    }
                )
                SettingsItem(
                    icon = Icons.Default.Tag,
                    title = stringResource(R.string.build_info),
                    subtitle = "${uiState.versionCode} (${uiState.buildVariant})"
                )
                SettingsItem(
                    icon = Icons.Default.BugReport,
                    title = stringResource(R.string.crash_logs),
                    subtitle = stringResource(R.string.crash_logs_description),
                    onClick = { showCrashInfoDialog = true }
                )
            }

            // ---- Developer (revealed after five version taps) ----
            if (showDeveloperSection) {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingsSection(title = "Developer") {
                    SettingsItem(
                        icon = Icons.Default.Build,
                        title = "Diagnostics",
                        subtitle = "Self-tests, pipeline harness, and radio counters",
                        onClick = { onNavigateToDiagnostics?.invoke() }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // ---- Language Dialog (all 5 languages) ----
    if (showLanguageDialog) {
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text(stringResource(R.string.select_language)) },
            text = {
                Column {
                    listOf(
                        "en" to stringResource(R.string.language_english),
                        "fa" to stringResource(R.string.language_farsi),
                        "ar" to stringResource(R.string.language_arabic),
                        "ur" to stringResource(R.string.language_urdu),
                        "ks" to stringResource(R.string.language_kashmiri)
                    ).forEach { (code, name) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setLanguage(code)
                                    LocaleHelper.saveLanguage(context, code)
                                    showLanguageDialog = false
                                    // Recreate the activity so attachBaseContext applies
                                    // locale direction and resources immediately.
                                    (context as? Activity)?.recreate()
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = uiState.currentLanguage == code, onClick = null)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(text = name, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLanguageDialog = false }) {
                    Text(stringResource(R.string.close))
                }
            }
        )
    }

    // ---- Power Profile Dialog ----
    if (showPowerDialog) {
        AlertDialog(
            onDismissRequest = { showPowerDialog = false },
            title = { Text(stringResource(R.string.select_power_profile)) },
            text = {
                Column {
                    PowerState.values().filter { it != PowerState.DEAD }.forEach { state ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setPowerOverride(state)
                                    showPowerDialog = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = uiState.powerOverride == state, onClick = null)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = when (state) {
                                        PowerState.EMERGENCY  -> stringResource(R.string.power_emergency)
                                        PowerState.ACTIVE     -> stringResource(R.string.power_active)
                                        PowerState.BALANCED   -> stringResource(R.string.power_balanced)
                                        PowerState.POWER_SAVER -> stringResource(R.string.power_saver)
                                        PowerState.MINIMAL    -> stringResource(R.string.power_minimal)
                                        PowerState.HIBERNATION -> stringResource(R.string.power_hibernation)
                                        else -> ""
                                    }
                                )
                                Text(
                                    text = getPowerStateDescription(state),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    if (uiState.powerOverride != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.clearPowerOverride(); showPowerDialog = false }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = false, onClick = null)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = stringResource(R.string.auto_adaptive),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPowerDialog = false }) {
                    Text(stringResource(R.string.close))
                }
            }
        )
    }

    // ---- Clear Data Confirmation ----
    if (showClearDataDialog) {
        ConfirmationDialog(
            title = stringResource(R.string.clear_old_messages),
            message = stringResource(R.string.clear_messages_confirmation),
            confirmText = stringResource(R.string.clear),
            cancelText = stringResource(R.string.cancel),
            onConfirm = { viewModel.clearOldMessages(); showClearDataDialog = false },
            onDismiss = { showClearDataDialog = false },
            isDestructive = true
        )
    }

    // ---- About Dialog ----
    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = { Text(stringResource(R.string.about_rezvan_mesh)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.about_description))
                    Text(
                        text = stringResource(R.string.version_format, uiState.versionName),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = stringResource(R.string.open_source_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (showDeveloperSection) {
                        Text(
                            text = "🛠 Developer mode enabled",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) {
                    Text(stringResource(R.string.close))
                }
            }
        )
    }

    // ---- Crash Log Info Dialog ----
    if (showCrashInfoDialog) {
        AlertDialog(
            onDismissRequest = { showCrashInfoDialog = false },
            title = { Text(stringResource(R.string.crash_logs)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.crash_logs_info))
                    Text(
                        text = "📁 ${stringResource(R.string.documents_folder)}\n📁 ${stringResource(R.string.downloads_folder)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showCrashInfoDialog = false }) {
                    Text(stringResource(R.string.close))
                }
            }
        )
    }
}

@Composable
fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(vertical = MeshDimens.compactGap)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = MeshDimens.screenHorizontal, vertical = MeshDimens.compactGap)
        )
        content()
    }
}

@Composable
fun SettingsItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Row(
        modifier = if (onClick != null)
            Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(horizontal = MeshDimens.screenHorizontal, vertical = 14.dp)
        else
            Modifier
                .fillMaxWidth()
                .padding(horizontal = MeshDimens.screenHorizontal, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = tint.copy(alpha = 0.14f),
            modifier = Modifier.size(MeshDimens.iconContainerSmall)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(MeshDimens.itemGap))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (onClick != null) {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

fun getPowerStateDescription(state: PowerState): String = when (state) {
    PowerState.EMERGENCY  -> "2-4 hours battery, maximum range"
    PowerState.ACTIVE     -> "4-6 hours, high performance"
    PowerState.BALANCED   -> "8-12 hours, default"
    PowerState.POWER_SAVER -> "24-36 hours, reduced range"
    PowerState.MINIMAL    -> "48+ hours, listen only"
    PowerState.HIBERNATION -> "7+ days, beacon only"
    PowerState.DEAD       -> "Critical reserve"
}