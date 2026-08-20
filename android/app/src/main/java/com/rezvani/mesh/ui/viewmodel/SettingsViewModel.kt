package com.rezvani.mesh.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rezvani.mesh.BuildConfig
import com.rezvani.mesh.MeshServiceConnection
import com.rezvani.mesh.data.DbKeyProvider
import com.rezvani.mesh.data.repositories.MessageRepository
import com.rezvani.mesh.ui.components.PowerState
import com.rezvani.mesh.utils.LocaleHelper
import com.rezvani.mesh.utils.PowerProfileManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context = application.applicationContext

    // Per-install, Keystore-backed key -- see DbKeyProvider. Every ViewModel
    // that opens AppDatabase calls the same provider, so there is exactly one
    // source of truth for the passphrase instead of a literal repeated at
    // each call site.
    private val dbPassphrase = DbKeyProvider.getOrCreateKey(application)
    private val messageRepo = MessageRepository(application, dbPassphrase)

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
        observeAutoPowerState()
    }

    private fun loadSettings() {
        val prefs = context.getSharedPreferences("rezvan_settings", Context.MODE_PRIVATE)
        _uiState.value = _uiState.value.copy(
            darkMode = prefs.getBoolean("dark_mode", true),
            currentLanguage = LocaleHelper.getSavedLanguage(context),
            nodeId = "Unknown", // populated once the engine is up -- see observeAutoPowerState
            powerOverride = prefs.getString("power_override", null)?.let {
                runCatching { PowerState.valueOf(it) }.getOrNull()
            },
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            buildVariant = BuildConfig.BUILD_VARIANT
        )
        refreshStorageUsed()
    }

    /**
     * Polls the live engine power state so the "Auto" row reflects what the
     * radio is actually doing (battery-driven, possibly overridden), instead
     * of a fixed placeholder. Cancels automatically with viewModelScope.
     *
     * Also picks up the canonical Node ID (SHA-256 of the derived Ed25519
     * public key, from the engine itself) the first time the engine becomes
     * available, rather than independently recomputing it in Kotlin from the
     * raw seed -- that used to produce a *different* value than the one the
     * engine actually puts on the wire (security audit finding #8).
     */
    private fun observeAutoPowerState() {
        viewModelScope.launch {
            var nodeIdLoaded = false
            while (true) {
                val ptr = MeshServiceConnection.meshCorePtr.value
                if (ptr != null && ptr != 0L) {
                    val raw = com.rezvani.mesh.MeshCore.nativeGetPowerState(ptr)
                    PowerState.values().getOrNull(raw)?.let { live ->
                        if (_uiState.value.autoPowerState != live) {
                            _uiState.value = _uiState.value.copy(autoPowerState = live)
                        }
                    }
                    if (!nodeIdLoaded) {
                        com.rezvani.mesh.MeshCore.nativeGetNodeId(ptr)?.let { bytes ->
                            val hex = bytes.joinToString("") { "%02x".format(it) }
                            _uiState.value = _uiState.value.copy(nodeId = hex)
                            nodeIdLoaded = true
                        }
                    }
                }
                delay(2000L)
            }
        }
    }

    /**
     * Real on-disk usage includes the encrypted message database and the app
     * cache directory. Cache files are included so this remains accurate if a
     * future feature writes temporary media or diagnostics outside the DB.
     */
    fun refreshStorageUsed() {
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                val dbFile = context.getDatabasePath("rezvan_mesh.db")
                val dbBytes = if (dbFile.exists()) dbFile.length() else 0L
                val cacheBytes = runCatching {
                    context.cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                }.getOrDefault(0L)
                dbBytes + cacheBytes
            }
            _uiState.value = _uiState.value.copy(storageUsed = formatBytes(bytes))
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format("%.1f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> String.format("%.1f MB", bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> String.format("%.1f KB", bytes / (1L shl 10).toDouble())
        else -> "$bytes B"
    }

    fun toggleDarkMode() {
        val newMode = !_uiState.value.darkMode
        _uiState.value = _uiState.value.copy(darkMode = newMode)
        context.getSharedPreferences("rezvan_settings", Context.MODE_PRIVATE)
            .edit().putBoolean("dark_mode", newMode).apply()
    }

    fun setLanguage(code: String) {
        LocaleHelper.saveLanguage(context, code)
        _uiState.value = _uiState.value.copy(currentLanguage = code)
    }

    fun setPowerOverride(state: PowerState) {
        context.getSharedPreferences("rezvan_settings", Context.MODE_PRIVATE)
            .edit().putString("power_override", state.name).apply()
        _uiState.value = _uiState.value.copy(powerOverride = state)
    }

    fun clearPowerOverride() {
        context.getSharedPreferences("rezvan_settings", Context.MODE_PRIVATE)
            .edit().remove("power_override").apply()
        _uiState.value = _uiState.value.copy(powerOverride = null)
    }

    fun openSystemPowerSettings() {
        PowerProfileManager.openBatterySaverSettings(context)
    }

    /** Deletes messages older than 30 days, then refreshes the storage figure. */
    fun clearOldMessages() {
        viewModelScope.launch {
            val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)
            withContext(Dispatchers.IO) {
                messageRepo.cleanupOldMessages(cutoff)
            }
            refreshStorageUsed()
        }
    }
}

data class SettingsUiState(
    val darkMode: Boolean = true,
    val currentLanguage: String = "fa",
    val nodeId: String = "Unknown",
    val powerOverride: PowerState? = null,
    val autoPowerState: PowerState = PowerState.BALANCED,
    val storageUsed: String = "Calculating...",
    val versionName: String = "",
    val versionCode: Int = 0,
    val buildVariant: String = ""
)