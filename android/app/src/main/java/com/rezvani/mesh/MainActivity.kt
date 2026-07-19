package com.rezvani.mesh

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.rezvani.mesh.backup.IdentityBackupHelper
import com.rezvani.mesh.radio.RezvanRadioService
import com.rezvani.mesh.ui.navigation.MainScreenWithBottomNav
import com.rezvani.mesh.ui.theme.RezvanMeshTheme
import com.rezvani.mesh.utils.DiagLogger
import com.rezvani.mesh.utils.LocaleHelper
import com.rezvani.mesh.utils.PowerProfileManager
import kotlinx.coroutines.*

class MainActivity : ComponentActivity() {

    private var boundService: RezvanRadioService? = null
    private val isServiceBound: MutableState<Boolean> = mutableStateOf(false)

    private var serviceStarted = false

    // Live permission + radio state for the blocking UI
    private val permState = mutableStateOf(PermissionCheckResult())

    // Set if Keystore-backed secure storage is unavailable on this device --
    // we cannot safely create or load the user's identity/DB key in that case,
    // so we show a blocking error screen instead of silently degrading to
    // unencrypted storage (see IdentityStorageException / security audit
    // finding #6) or proceeding with no identity at all.
    private val identityStorageError = mutableStateOf<String?>(null)

    // Listens for BT on/off changes even when user toggles via quick-settings tile
    // without leaving the app (onResume doesn't fire in that case).
    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            recheckAndStart()
        }
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        Log.i(TAG, if (isGranted) "Location granted" else "Location denied")
        recheckAndStart()
    }

    private val bluetoothScanLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        Log.i(TAG, if (isGranted) "BLUETOOTH_SCAN granted" else "BLUETOOTH_SCAN denied")
        recheckAndStart()
    }

    private val bluetoothAdvertiseLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        Log.i(TAG, if (isGranted) "BLUETOOTH_ADVERTISE granted" else "BLUETOOTH_ADVERTISE denied")
        recheckAndStart()
    }

    private val bluetoothConnectLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        Log.i(TAG, if (isGranted) "BLUETOOTH_CONNECT granted" else "BLUETOOTH_CONNECT denied")
        recheckAndStart()
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? RezvanRadioService.LocalBinder
            boundService = binder?.getService()
            if (boundService != null) {
                MeshServiceConnection.onServiceConnected(boundService!!)
                isServiceBound.value = true
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            MeshServiceConnection.onServiceDisconnected()
            boundService = null
            isServiceBound.value = false
        }
    }

    override fun attachBaseContext(newBase: Context) {
        val context = LocaleHelper.setLocale(newBase, LocaleHelper.getSavedLanguage(newBase))
        super.attachBaseContext(context)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        DiagLogger.log(this, "Manufacturer: ${Build.MANUFACTURER}, Model: ${Build.MODEL}")
        if (Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true))
            DiagLogger.log(this, "MIUI detected - verify Autostart + Battery unrestricted")

        lifecycleScope.launch {
            try {
                ensureIdentityExists()
                recheckAndStart()
            } catch (e: com.rezvani.mesh.backup.IdentityStorageException) {
                DiagLogger.log(this@MainActivity, "IDENTITY STORAGE FAILED: ${e.message}")
                identityStorageError.value = e.message ?: "Secure storage unavailable on this device."
            }
        }

        val prefs = getSharedPreferences("rezvan_settings", Context.MODE_PRIVATE)
        val powerOverride = prefs.getString("power_override", null)?.let { PowerState.valueOf(it) }
        if (powerOverride != null) PowerProfileManager.applyPowerState(this, powerOverride)

        // Register BT state receiver so the gate reacts immediately to BT toggle.
        // API 33+ requires an explicit export flag or registration throws.
        val btFilter = android.content.IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(btStateReceiver, btFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(btStateReceiver, btFilter)
        }

        setContent {
            val darkMode = getDarkModeState()
            RezvanMeshTheme(darkTheme = darkMode) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val storageError by identityStorageError
                    val perm by permState
                    if (storageError != null) {
                        IdentityStorageErrorScreen(message = storageError!!)
                    } else if (!perm.allGranted) {
                        PermissionGate(
                            result = perm,
                            onRequestPermissions = { requestMissingPermissions() },
                            onOpenBtSettings = { openBtSettings() },
                            onOpenLocationSettings = { openLocationSettings() },
                            onOpenAppSettings = { openAppSettings() },
                            onOpenBattery = { openBatteryOptimization() }
                        )
                    } else {
                        MainScreenWithBottomNav()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        recheckAndStart()
    }

    @Composable
    fun getDarkModeState(): Boolean {
        val prefs = remember { getSharedPreferences("rezvan_settings", Context.MODE_PRIVATE) }
        return remember { mutableStateOf(prefs.getBoolean("dark_mode", true)) }.value
    }

    private fun recheckAndStart() {
        permState.value = checkAll()
        if (permState.value.allGranted) {
            tryStartRadioService()
        }
    }

    private fun checkAll(): PermissionCheckResult {
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
        val btEnabled = btManager?.adapter?.isEnabled == true
        val locEnabled = (getSystemService(Context.LOCATION_SERVICE) as? LocationManager)?.let {
            it.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            it.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } == true
        val locPerm = hasLocationPermission()
        val scanPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) hasScanPermission() else true
        val advPerm  = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) hasAdvertisePermission() else true
        val connPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) hasConnectPermission() else true
        val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val batteryOk = pm?.isIgnoringBatteryOptimizations(packageName) ?: true
        DiagLogger.log(this, "PERM CHECK: bt=$btEnabled loc=$locEnabled " +
            "locPerm=$locPerm scan=$scanPerm adv=$advPerm conn=$connPerm " +
            "battery=$batteryOk")
        return PermissionCheckResult(
            btEnabled = btEnabled,
            locationEnabled = locEnabled,
            locationPermission = locPerm,
            btScanPermission = scanPerm,
            btAdvertisePermission = advPerm,
            btConnectPermission = connPerm,
            batteryUnrestricted = batteryOk
        )
    }

    private fun requestMissingPermissions() {
        if (!hasLocationPermission())
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasScanPermission())      bluetoothScanLauncher.launch(Manifest.permission.BLUETOOTH_SCAN)
            if (!hasAdvertisePermission()) bluetoothAdvertiseLauncher.launch(Manifest.permission.BLUETOOTH_ADVERTISE)
            if (!hasConnectPermission())   bluetoothConnectLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    private fun openBtSettings() {
        startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun openLocationSettings() {
        startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.fromParts("package", packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun openBatteryOptimization() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = android.net.Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            // Some OEMs block the direct request; fall back to the list screen
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun tryStartRadioService() {
        if (serviceStarted) return
        val seed = try {
            IdentityBackupHelper.loadSeed(this) ?: run {
                DiagLogger.log(this, "Service start deferred: no identity seed yet")
                return
            }
        } catch (e: com.rezvani.mesh.backup.IdentityStorageException) {
            DiagLogger.log(this, "IDENTITY STORAGE FAILED: ${e.message}")
            identityStorageError.value = e.message ?: "Secure storage unavailable on this device."
            return
        }
        if (!checkAll().allGranted) {
            DiagLogger.log(this, "Service start deferred: waiting for permissions")
            return
        }
        serviceStarted = true
        try {
            val intent = Intent(this, RezvanRadioService::class.java)
            startForegroundService(intent)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start radio service", e)
            serviceStarted = false
        }
    }

    private fun hasLocationPermission() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun hasScanPermission() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
    private fun hasAdvertisePermission() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
    private fun hasConnectPermission() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    private fun hasBluetoothPermission() = hasScanPermission() && hasAdvertisePermission() && hasConnectPermission()

    private suspend fun ensureIdentityExists() {
        val existing = withContext(Dispatchers.IO) { IdentityBackupHelper.loadSeed(this@MainActivity) }
        if (existing == null) {
            // Identity is always a securely generated random seed -- never
            // derived from a hardware identifier like the MAC address (see
            // security audit finding #2: MAC-derived keys are guessable and
            // let an attacker recompute this device's private identity keys).
            val seed = IdentityBackupHelper.generateSeed()
            IdentityBackupHelper.saveSeed(this, seed)
            DiagLogger.log(this, "Identity seed saved")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(btStateReceiver) } catch (_: Exception) {}
    }

    companion object { private const val TAG = "MainActivity" }
}

// ── Blocking identity-storage error screen ────────────────────────────────────
@Composable
fun IdentityStorageErrorScreen(message: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
    ) {
        Icon(
            Icons.Default.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(64.dp)
        )
        Text(
            "Secure Storage Unavailable",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Text(
            "Rezvan Mesh cannot safely create or load your identity and message " +
                "database without Android's Keystore-backed secure storage. To " +
                "protect you, the app will not fall back to storing this key " +
                "material unencrypted.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

// ── Permission state model ────────────────────────────────────────────────────
data class PermissionCheckResult(
    val btEnabled: Boolean = false,
    val locationEnabled: Boolean = false,
    val locationPermission: Boolean = false,
    val btScanPermission: Boolean = false,
    val btAdvertisePermission: Boolean = false,
    val btConnectPermission: Boolean = false,
    val batteryUnrestricted: Boolean = false
) {
    // Core requirements that block the app; battery is strongly recommended
    // but checked here as part of the full gate. WRITE_SETTINGS was dropped
    // from both the manifest and this gate: no feature in the app actually
    // writes to Settings.System (see security audit finding #7 / Fix 7),
    // so requiring it was unnecessary attack surface with no functional need.
    val allGranted get() = btEnabled && locationEnabled &&
        locationPermission && btScanPermission && btAdvertisePermission &&
        btConnectPermission && batteryUnrestricted
}

// ── Blocking permission gate UI ───────────────────────────────────────────────
@Composable
fun PermissionGate(
    result: PermissionCheckResult,
    onRequestPermissions: () -> Unit,
    onOpenBtSettings: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenBattery: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_rezvan_logo),
            contentDescription = "Rezvan Mesh",
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
        )
        Text("Rezvan Mesh", style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold)
        Text("The following are required for the mesh to function.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center)

        Spacer(Modifier.height(8.dp))

        // Bluetooth toggle
        PermissionRow(
            icon = Icons.Default.Bluetooth,
            label = "Bluetooth",
            description = "Required for device discovery and messaging",
            granted = result.btEnabled,
            actionLabel = "Turn On",
            onAction = onOpenBtSettings
        )

        // Location toggle
        PermissionRow(
            icon = Icons.Default.LocationOn,
            label = "Location Services",
            description = "Required for BLE scanning on Android",
            granted = result.locationEnabled,
            actionLabel = "Turn On",
            onAction = onOpenLocationSettings
        )

        // Permissions
        if (!result.locationPermission || !result.btScanPermission ||
            !result.btAdvertisePermission || !result.btConnectPermission) {
            PermissionRow(
                icon = Icons.Default.Security,
                label = "Nearby Devices + Location",
                description = "Bluetooth Scan, Advertise, Connect, and Location permissions",
                granted = result.locationPermission && result.btScanPermission &&
                          result.btAdvertisePermission && result.btConnectPermission,
                actionLabel = "Grant",
                onAction = onRequestPermissions
            )
        }

        // Battery optimization
        PermissionRow(
            icon = Icons.Default.BatteryAlert,
            label = "Unrestricted Battery",
            description = "Keeps the mesh running in the background",
            granted = result.batteryUnrestricted,
            actionLabel = "Allow",
            onAction = onOpenBattery
        )

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = onRequestPermissions,
            modifier = Modifier.fillMaxWidth(),
            enabled = !result.allGranted
        ) {
            Text("Grant Permissions")
        }

        if (!result.btScanPermission || !result.btAdvertisePermission || !result.btConnectPermission) {
            OutlinedButton(onClick = onOpenAppSettings, modifier = Modifier.fillMaxWidth()) {
                Text("Open App Settings")
            }
        }
    }
}

@Composable
private fun PermissionRow(
    icon: ImageVector,
    label: String,
    description: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (granted) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(icon, null,
                tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(24.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (granted) {
                Icon(Icons.Default.CheckCircle, null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            } else {
                FilledTonalButton(onClick = onAction, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                    Text(actionLabel, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}