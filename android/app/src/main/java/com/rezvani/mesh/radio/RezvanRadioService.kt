// android/app/src/main/java/com/rezvani/mesh/radio/RezvanRadioService.kt

package com.rezvani.mesh.radio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.rezvani.mesh.MeshServiceConnection
import com.rezvani.mesh.ui.components.PowerState
import com.rezvani.mesh.backup.IdentityBackupHelper
import com.rezvani.mesh.utils.DiagLogger
import com.rezvani.mesh.radio.failureMessage
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

class RezvanRadioService : Service() {

    companion object {
        const val CHANNEL_ID = "rezvan_mesh"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.rezvani.mesh.STOP_SERVICE"
        private const val SEED_RETRY_DELAY_MS = 500L
        private const val SEED_MAX_RETRIES = 10
    }

    private lateinit var notificationManager: NotificationManager
    private lateinit var powerManager: PowerManager
    private var wakeLock: PowerManager.WakeLock? = null

    private var radioController: RadioControllerImpl? = null
    private var enginePtr: Long = 0L
    var ownNodeId: ByteArray? = null
        private set
    private var meshConnection: MeshServiceConnection? = null

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var tickJob: Job? = null
    private var isDestroyed = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        acquireWakeLock()

        radioController = RadioControllerImpl(this)
        DiagLogger.ble("Controllers built")
        val scanOk = checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val advOk = checkSelfPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        DiagLogger.ble("Permissions scan=$scanOk adv=$advOk sdk=${Build.VERSION.SDK_INT}")
        radioController?.startBleScan(1000, 1000)
        DiagLogger.ble("BLE scan requested")

        loadIdentityAndInitEngine()
        startPeriodicTick()
        DiagLogger.ble("onCreate COMPLETE")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Rezvan Mesh",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Mesh networking service" }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, RezvanRadioService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Rezvan Mesh active")
            .setContentText("Scanning & advertising")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .build()
    }

    private fun acquireWakeLock() {
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RezvanMesh::Service").apply {
            acquire(10 * 60 * 1000L)
        }
        DiagLogger.ble("WakeLock acquired")
    }

    private fun loadIdentityAndInitEngine() {
        serviceScope.launch {
            var retries = 0
            while (retries < SEED_MAX_RETRIES && !isDestroyed.get()) {
                try {
                    val seed = IdentityBackupHelper.loadSeed(this@RezvanRadioService)
                    if (seed != null) {
                        DiagLogger.ble("Service seed bytes: ${seed.size}")
                        initMeshEngine(seed)
                        return@launch
                    }
                } catch (e: com.rezvani.mesh.backup.IdentityStorageException) {
                    // Secure storage is unavailable on this device -- retrying won't
                    // help. Log and stop; MainActivity's blocking error screen is the
                    // user-facing signal for this, not the background service.
                    DiagLogger.err("SERVICE", "Identity storage unavailable: ${e.message}", e)
                    return@launch
                } catch (e: Exception) {
                    DiagLogger.err("SERVICE", "Error loading identity: ${e.message}", e)
                }
                retries++
                DiagLogger.ble("Seed not ready, retry $retries/$SEED_MAX_RETRIES")
                delay(SEED_RETRY_DELAY_MS)
            }
            DiagLogger.ble("Seed loading failed after $SEED_MAX_RETRIES retries")
        }
    }

    private fun initMeshEngine(seed: ByteArray) {
        if (enginePtr != 0L) return
        try {
            val initializedPtr = com.rezvani.mesh.MeshCore.tryNativeInit(seed, filesDir.absolutePath)
            if (initializedPtr == null || initializedPtr == 0L) {
                DiagLogger.err(
                    "SERVICE",
                    "Mesh engine unavailable: native JNI symbols are missing or the library failed to load"
                )
                MeshServiceConnection.meshCorePtr.value = 0L
                return
            }
            enginePtr = initializedPtr
            // Canonical Node ID comes from the engine itself (SHA-256 of the
            // derived Ed25519 public key) -- this is the exact value the
            // engine puts in the `originator` field of every packet it sends.
            // Previously this was independently recomputed in Kotlin from
            // SHA-256(seed), which is a *different* value and silently broke
            // self-loopback detection (see security audit finding #8).
            ownNodeId = com.rezvani.mesh.MeshCore.nativeGetNodeId(enginePtr)
            MeshServiceConnection.ownNodeId.value = ownNodeId?.copyOf()
            if (ownNodeId != null) {
                radioController?.setOwnNodeId(ownNodeId!!)
            }
            DiagLogger.ble("initializeMeshEngine called")
            DiagLogger.ble("MeshCore initialised, ptr=$enginePtr")
            MeshServiceConnection.meshCorePtr.value = enginePtr

            // Power management: feed battery to the engine and apply any
            // Settings override, live.
            registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            getSharedPreferences("rezvan_settings", Context.MODE_PRIVATE)
                .registerOnSharedPreferenceChangeListener(prefsListener)
            applyPowerOverride()
        } catch (e: Exception) {
            DiagLogger.err("SERVICE", "Engine init failed: ${e.message}", e)
        }
    }

    /** Pushes the latest battery reading into the engine; also re-applies the
     *  current power override so the effective state stays correct. */
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (enginePtr == 0L || i == null) return
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val pct = (level * 100 / scale).coerceIn(0, 100)
            val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            com.rezvani.mesh.MeshCore.nativeUpdateBattery(enginePtr, pct, charging)
            // Push to UI StateFlows so NetworkScreen + any other screen can read it
            com.rezvani.mesh.MeshServiceConnection.batteryLevel.value = pct
            com.rezvani.mesh.MeshServiceConnection.isCharging.value = charging
            applyPowerOverride()
        }
    }

    /** Reads the saved power override and applies it (or clears it) on the engine. */
    private fun applyPowerOverride() {
        if (enginePtr == 0L) return
        val ov = getSharedPreferences("rezvan_settings", Context.MODE_PRIVATE)
            .getString("power_override", null)
        if (ov == null) {
            com.rezvani.mesh.MeshCore.nativeClearPowerOverride(enginePtr)
        } else {
            runCatching { PowerState.valueOf(ov).ordinal }.getOrNull()?.let {
                com.rezvani.mesh.MeshCore.nativeSetPowerOverride(enginePtr, it)
            }
        }
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "power_override") applyPowerOverride()
    }

    private fun startPeriodicTick() {
        tickJob = serviceScope.launch {
            while (isActive && !isDestroyed.get()) {
                delay(1000L)
                if (enginePtr == 0L) continue
                try {
                    val result = withContext(Dispatchers.IO) {
                        com.rezvani.mesh.MeshCore.nativeTick(enginePtr)
                    }
                    if (result != null) {
                        radioController?.let { ActionDispatcher.dispatch(result, it) }
                    }
                } catch (e: Exception) {
                    DiagLogger.err("SERVICE", "Tick error: ${e.message}", e)
                }
            }
        }
    }

    fun onPacketReceived(data: ByteArray, rssi: Int) {
        if (enginePtr == 0L) return
        serviceScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    com.rezvani.mesh.MeshCore.nativeProcessIncoming(enginePtr, data, rssi, System.currentTimeMillis() * 1000)
                }
                if (result != null && result.size >= 1) {
                    val actionCount = result[0].toInt() and 0xFF
                    var offset = 1
                    for (i in 0 until actionCount) {
                        if (offset + 3 > result.size) break
                        val actionType = result[offset].toInt() and 0xFF
                        val payloadLen = ((result[offset + 1].toInt() and 0xFF) shl 8) or (result[offset + 2].toInt() and 0xFF)
                        offset += 3
                        if (offset + payloadLen > result.size) break
                        val payload = result.copyOfRange(offset, offset + payloadLen)
                        offset += payloadLen
                        when (actionType) {
                            0x05 -> {
                                if (payload.size < 38) continue
                                val contentLen = ((payload[33].toInt() and 0xFF) shl 24) or
                                        ((payload[34].toInt() and 0xFF) shl 16) or
                                        ((payload[35].toInt() and 0xFF) shl 8) or
                                        (payload[36].toInt() and 0xFF)
                                val contentEnd = 37L + contentLen.toLong()
                                if (contentLen < 0 || contentEnd + 1 > payload.size) continue
                                val idFlagOffset = contentEnd.toInt()
                                val protocolMessageId = when (payload[idFlagOffset].toInt() and 0xFF) {
                                    0 -> if (idFlagOffset + 1 == payload.size) null else continue
                                    1 -> if (idFlagOffset + 17 == payload.size) {
                                        payload.copyOfRange(idFlagOffset + 1, idFlagOffset + 17)
                                    } else continue
                                    else -> continue
                                }
                                val msg = com.rezvani.mesh.rust.DecryptedMessage(
                                    conversationId = payload.copyOfRange(0, 16),
                                    senderId = payload.copyOfRange(16, 24),
                                    timestamp = ((payload[24].toLong() and 0xFF) shl 56) or
                                            ((payload[25].toLong() and 0xFF) shl 48) or
                                            ((payload[26].toLong() and 0xFF) shl 40) or
                                            ((payload[27].toLong() and 0xFF) shl 32) or
                                            ((payload[28].toLong() and 0xFF) shl 24) or
                                            ((payload[29].toLong() and 0xFF) shl 16) or
                                            ((payload[30].toLong() and 0xFF) shl 8) or
                                            (payload[31].toLong() and 0xFF),
                                    messageType = payload[32],
                                    protocolMessageId = protocolMessageId,
                                    content = payload.copyOfRange(37, idFlagOffset)
                                )
                                val receipt = meshConnection?.addReceivedMessage(msg)
                                if (receipt != null) {
                                    sendReceivedAcknowledgement(receipt.originalSender, receipt.protocolMessageId)
                                }
                            }
                            0x07 -> if (payload.size == 24) {
                                meshConnection?.onMessageAcknowledged(
                                    protocolMessageId = payload.copyOfRange(0, 16),
                                    ackSender = payload.copyOfRange(16, 24)
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                DiagLogger.err("SERVICE", "Packet error: ${e.message}", e)
            }
        }
    }

    fun setConnection(conn: MeshServiceConnection?) {
        meshConnection = conn
    }

    /**
     * Submits a direct message to the native engine and local radio queue.
     * A successful result is intentionally limited to local queue acceptance;
     * the current protocol does not prove remote delivery here.
     */
    suspend fun sendMessage(
        recipient: ByteArray,
        protocolMessageId: ByteArray,
        createdAtMs: Long,
        plaintext: ByteArray
    ): SendResult {
        if (enginePtr == 0L || radioController == null) return SendResult.NotReady
        if (protocolMessageId.size != 16) return SendResult.Failed("Invalid persistent message identity")
        return try {
            val result = withContext(Dispatchers.IO) {
                com.rezvani.mesh.MeshCore.nativeSendMessageV1(
                    enginePtr, recipient, protocolMessageId, createdAtMs, 0, plaintext
                )
            } ?: return SendResult.Failed("Mesh engine did not create a message packet")
            ActionDispatcher.dispatch(result, radioController!!)
        } catch (e: Exception) {
            DiagLogger.err("SERVICE", "Gate 1 send error: ${e.message}", e)
            SendResult.Failed(e.message ?: "Could not submit message to the mesh")
        }
    }

    /** Sends a receipt only after [MeshServiceConnection] reports durable inbound storage. */
    private suspend fun sendReceivedAcknowledgement(originalSender: ByteArray, messageId: ByteArray) {
        if (enginePtr == 0L || radioController == null || originalSender.size != 8 || messageId.size != 16) return
        try {
            val result = withContext(Dispatchers.IO) {
                com.rezvani.mesh.MeshCore.nativeBuildMessageReceivedAck(
                    enginePtr, originalSender, messageId, System.currentTimeMillis()
                )
            } ?: return
            val dispatch = ActionDispatcher.dispatch(result, radioController!!)
            if (dispatch !is SendResult.Queued) {
                DiagLogger.ble("Receipt acknowledgement not locally queued: ${dispatch.failureMessage()}")
            }
        } catch (e: Exception) {
            DiagLogger.err("SERVICE", "Receipt acknowledgement error: ${e.message}", e)
        }
    }

    /** Submits a signed emergency broadcast to the local mesh transport. */
    suspend fun sendBroadcast(message: ByteArray): SendResult {
        if (enginePtr == 0L || radioController == null) return SendResult.NotReady
        return try {
            val result = withContext(Dispatchers.IO) {
                com.rezvani.mesh.MeshCore.nativeSendBroadcast(enginePtr, message)
            } ?: return SendResult.Failed("Mesh engine did not create an emergency packet")
            ActionDispatcher.dispatch(result, radioController!!)
        } catch (e: Exception) {
            DiagLogger.err("SERVICE", "Broadcast error: ${e.message}", e)
            SendResult.Failed(e.message ?: "Could not submit emergency alert to the mesh")
        }
    }

    /** Submits a sender-key channel message to the local mesh transport. */
    suspend fun sendChannelMessage(channelId: Int, message: ByteArray): SendResult {
        if (enginePtr == 0L || radioController == null) return SendResult.NotReady
        return try {
            val result = withContext(Dispatchers.IO) {
                com.rezvani.mesh.MeshCore.nativeSendChannelMessage(enginePtr, channelId, message)
            } ?: return SendResult.Failed("No channel key is available for this channel")
            ActionDispatcher.dispatch(result, radioController!!)
        } catch (e: Exception) {
            DiagLogger.err("SERVICE", "Channel send error: ${e.message}", e)
            SendResult.Failed(e.message ?: "Could not submit channel message to the mesh")
        }
    }

    /** Generates and returns a new shared key for a channel we just created. */
    fun createChannelKey(channelId: Int): ByteArray? {
        if (enginePtr == 0L) return null
        return com.rezvani.mesh.MeshCore.nativeCreateChannelKey(enginePtr, channelId)
    }

    /** Stores a channel key obtained out-of-band (joining an existing channel). */
    fun setChannelKey(channelId: Int, key: ByteArray): Boolean {
        if (enginePtr == 0L) return false
        return com.rezvani.mesh.MeshCore.nativeSetChannelKey(enginePtr, channelId, key)
    }

    /**
     * Voice transport is deliberately unavailable until the authenticated voice
     * envelope and end-to-end receive path are implemented. Raw recordings
     * must not be injected into the mesh packet transport.
     */
    suspend fun sendVoiceBroadcast(packet: ByteArray): SendResult {
        DiagLogger.ble("Voice broadcast blocked pending transport verification, size=${packet.size}")
        return SendResult.Failed("Voice broadcast is unavailable while transport verification is in progress")
    }

    fun getRadioController(): RadioControllerImpl? = radioController

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = LocalBinder()

    inner class LocalBinder : android.os.Binder() {
        fun getService(): RezvanRadioService = this@RezvanRadioService
    }

    override fun onDestroy() {
        isDestroyed.set(true)
        runCatching { unregisterReceiver(batteryReceiver) }
        runCatching {
            getSharedPreferences("rezvan_settings", Context.MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(prefsListener)
        }
        if (enginePtr != 0L) {
            com.rezvani.mesh.MeshCore.nativeDestroy(enginePtr)
        }
        MeshServiceConnection.ownNodeId.value = null
        MeshServiceConnection.meshCorePtr.value = null
        radioController?.onDestroy()
        wakeLock?.release()
        DiagLogger.ble("Service destroyed")
        super.onDestroy()
    }
}