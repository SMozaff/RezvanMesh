package com.rezvani.mesh.radio

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.rezvani.mesh.BuildConfig
import com.rezvani.mesh.MeshServiceConnection
import com.rezvani.mesh.utils.DiagLogger
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class RadioControllerImpl(private val context: Context) : RadioController {

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter
    private var bleScanner: BluetoothLeScanner? = bluetoothAdapter?.bluetoothLeScanner
    private var bleAdvertiser: BluetoothLeAdvertiser? = bluetoothAdapter?.bluetoothLeAdvertiser
    private var gattServer: BluetoothGattServer? = null
    private var gattServerStartAttempted = false

    private val bleGattMap = ConcurrentHashMap<String, BluetoothGatt>()
    private val bleSenderMap = ConcurrentHashMap<String, BlePacketSender>()
    private val cachedRssiMap = ConcurrentHashMap<String, Int>()
    private val nodeIdToMac = ConcurrentHashMap<String, String>()

    /**
     * Packets addressed to a peer we've discovered (know their MAC) but
     * don't yet have a live GATT connection+sender for -- connect/MTU-
     * negotiate/service-discovery takes real time (see connectToPeer's
     * docs). Keyed by MAC address (matching bleGattMap/bleSenderMap), and
     * flushed automatically in onServicesDiscovered once the sender for
     * that peer becomes ready.
     *
     * Previously (RezvanRadioService.pendingPackets, now removed) this
     * concept existed but nothing ever wrote into it -- onServicesDiscovered
     * called radioService.dequeuePendingPackets(), which always returned
     * empty, so any message sent during the connect window was silently
     * dropped. This is the real, write-and-read version.
     */
    private val pendingPacketsByMac = ConcurrentHashMap<String, MutableList<ByteArray>>()

    private val isScanning = AtomicBoolean(false)
    private val scanHandler = Handler(Looper.getMainLooper())

    private val isAdvertising = AtomicBoolean(false)
    private var advertisingSet: AdvertisingSet? = null
    private var pendingAdvertiseData: ByteArray? = null

    private var ownNodeId: ByteArray? = null

    private val rxTotal = AtomicLong(0)
    private val rxLoopback = AtomicLong(0)
    private val rxPeer = AtomicLong(0)
    private val rxScanAll = AtomicLong(0)
    private val txStarts = AtomicLong(0)

    private val rawLogLimit = AtomicLong(5)

    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            DiagLogger.ble(
                "heartbeat",
                "scan" to isScanning.get().toString(),
                "adv" to isAdvertising.get().toString(),
                "rx" to rxTotal.get().toString(),
                "self" to rxLoopback.get().toString(),
                "peer" to rxPeer.get().toString(),
                "all" to rxScanAll.get().toString(),
                "tx" to txStarts.get().toString()
            )
            // GAP 6+7: push unique peer count (nodeIdToMac.size, not raw rxPeer)
            // and best RSSI to the StateFlows the UI observes.
            val uniquePeers = nodeIdToMac.size
            MeshServiceConnection.nodeCount.value = uniquePeers
            if (uniquePeers > 0) {
                val bestRssi = cachedRssiMap.values.maxOrNull() ?: -100
                MeshServiceConnection.signalStrength.value = "$bestRssi dBm"
            } else {
                MeshServiceConnection.signalStrength.value = "--"
            }
            heartbeatHandler.postDelayed(this, 10_000L)
        }
    }

    private val wifiP2pManager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var wifiP2pChannel: WifiP2pManager.Channel? = null
    private var wifiDirectReceiver: BroadcastReceiver? = null

    private var serverSocket: ServerSocket? = null
    private val wifiClients = ConcurrentHashMap<String, Socket>()
    private val serverThread = AtomicReference<Thread>()

    private val radioService: RezvanRadioService? =
        if (context is RezvanRadioService) context else null

    private val MESH_SERVICE_UUID = UUID.fromString("0000a1b2-0000-1000-8000-00805f9b34fb")
    private val MESH_CHARACTERISTIC_WRITE_UUID = UUID.fromString("0000a1b3-0000-1000-8000-00805f9b34fb")
    private val MESH_CHARACTERISTIC_NOTIFY_UUID = UUID.fromString("0000a1b4-0000-1000-8000-00805f9b34fb")

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state == BluetoothAdapter.STATE_ON) {
                    DiagLogger.ble("Bluetooth re-enabled, restarting GATT server")
                    startGattServerIfPermitted()
                    bleScanner = bluetoothAdapter?.bluetoothLeScanner
                    bleAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser
                    if (isScanning.get()) {
                        // Resume whatever duty cycle was active before BT
                        // was disabled, instead of resetting to a hardcoded
                        // continuous scan -- startBleScan now genuinely
                        // honors these parameters (see its docs), so
                        // silently discarding the current power state's
                        // interval/window here would undo that.
                        isScanning.set(false) // let startBleScan's "already scanning" guard not short-circuit this restart
                        startBleScan(currentScanIntervalMs, currentScanWindowMs)
                    }
                    if (isAdvertising.get() && pendingAdvertiseData != null) {
                        startLegacyAdvertising(pendingAdvertiseData!!)
                    }
                }
            }
        }
    }

    fun setOwnNodeId(nodeId: ByteArray) {
        if (nodeId.size != NODE_ID_LEN) {
            DiagLogger.ble("setOwnNodeId WRONG LENGTH: got ${nodeId.size}, expected $NODE_ID_LEN")
            return
        }
        ownNodeId = nodeId.copyOf()
        DiagLogger.ble("ownNodeId set", "prefix" to nodeId.take(4).joinToString("") { "%02x".format(it) })
    }

    fun isCurrentlyAdvertising(): Boolean = isAdvertising.get()
    fun isCurrentlyScanning(): Boolean = isScanning.get()

    fun snapshotCounters(): Map<String, Long> = mapOf(
        "rx_total" to rxTotal.get(), "rx_loopback" to rxLoopback.get(),
        "rx_peer" to rxPeer.get(), "tx_starts" to txStarts.get(),
        "scanning" to (if (isScanning.get()) 1L else 0L),
        "advertising" to (if (isAdvertising.get()) 1L else 0L)
    )

    init {
        DiagLogger.ble(
            "RadioController init",
            "adapter" to (bluetoothAdapter != null).toString(),
            "scanner" to (bleScanner != null).toString(),
            "advertiser" to (bleAdvertiser != null).toString(),
            "wifiP2p" to (wifiP2pManager != null).toString()
        )
        if (wifiP2pManager != null) {
            wifiP2pChannel = wifiP2pManager?.initialize(context, Looper.getMainLooper(), null)
            setupWifiDirectReceiver()
            startWifiServer()
        }
        startHeartbeat()
        context.registerReceiver(btStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        // GATT server is deferred to startBleScan or tick – avoids Samsung null-parameter crash
    }

    /**
     * Bug fix: this function used to accept `intervalMs`/`windowMs` and
     * then completely ignore both, always running a single continuous
     * `SCAN_MODE_LOW_LATENCY` scan regardless of what was requested. The
     * Rust power-state machine (`rezvan-core::power::get_scan_params`)
     * computes real per-power-state interval/window pairs (e.g. Active:
     * 1000/250ms, Balanced: 5000/250ms, PowerSaver: 30000/100ms, Minimal:
     * 120000/50ms) and correctly dispatches them here via
     * `Action::UpdateScanInterval` -> `ActionDispatcher` -> this function
     * (see engine.rs's `tick()` and ActionDispatcher.kt's action type
     * 0x04) -- but this function threw them away, so the phone always
     * scanned like it was in the most aggressive power state, regardless
     * of battery level. This is very likely a real contributor to reports
     * of excess battery drain and to peer discovery not "hopping" between
     * active/idle the way the power-state design intends.
     *
     * Android's public `BluetoothLeScanner`/`ScanSettings` API has no
     * direct interval/window knob (only the coarse `ScanMode` enum:
     * LOW_POWER/BALANCED/LOW_LATENCY/OPPORTUNISTIC) -- so real duty-cycling
     * is implemented here at the app level: scan continuously for
     * `windowMs`, then stop and idle for the remainder of `intervalMs`,
     * repeat. This is the standard approach recommended for apps needing
     * power-aware BLE scanning beyond what `ScanMode` alone provides.
     */
    override fun startBleScan(intervalMs: Long, windowMs: Long) {
        if (!hasScanPermission()) {
            DiagLogger.ble("startBleScan ABORT: missing BLUETOOTH_SCAN permission")
            return
        }
        if (bluetoothAdapter?.isEnabled != true) {
            DiagLogger.ble("startBleScan ABORT: BT disabled or null adapter")
            return
        }
        if (bleScanner == null) {
            DiagLogger.ble("startBleScan ABORT: scanner is null")
            return
        }
        // Retry GATT server if it failed earlier (safe context now)
        if (gattServer == null && !gattServerStartAttempted) {
            startGattServerIfPermitted()
        }
        if (isScanning.get()) {
            // Already running a duty cycle -- update its parameters in
            // place for the next cycle rather than starting a second,
            // overlapping one. Restarting immediately with the new
            // parameters keeps behavior responsive to power-state changes
            // instead of waiting out however much of the old cycle
            // remains.
            currentScanIntervalMs = intervalMs
            currentScanWindowMs = windowMs
            DiagLogger.ble("startBleScan: already scanning, updated duty cycle", "interval" to intervalMs.toString(), "window" to windowMs.toString())
            return
        }
        isScanning.set(true)
        currentScanIntervalMs = intervalMs
        currentScanWindowMs = windowMs

        DiagLogger.ble("BLE scan starting -- duty cycle", "interval" to intervalMs.toString(), "window" to windowMs.toString())
        scanHandler.removeCallbacksAndMessages(null)
        scanHandler.post(scanDutyCycleRunnable)
    }

    private var currentScanIntervalMs: Long = 1000
    private var currentScanWindowMs: Long = 1000

    /**
     * A window <= 0 or >= interval means "scan continuously" (matches the
     * old always-on behavior, and covers PowerState::Emergency/Active-like
     * cases where the caller wants no idle gap at all). Otherwise: start
     * the radio scan, run it for `currentScanWindowMs`, stop it, wait out
     * the remaining `currentScanIntervalMs - currentScanWindowMs`, repeat.
     */
    private val scanDutyCycleRunnable: Runnable = object : Runnable {
        override fun run() {
            if (!isScanning.get()) return // stopBleScan() was called mid-cycle

            val window = currentScanWindowMs
            val interval = currentScanIntervalMs
            val continuous = window <= 0 || window >= interval

            beginRadioScan()

            if (continuous) {
                // No duty-cycling requested for this power state -- just
                // keep the radio scan running; nothing more to schedule.
                return
            }

            scanHandler.postDelayed({
                if (!isScanning.get()) return@postDelayed
                endRadioScan()
                val idleMs = (interval - window).coerceAtLeast(0)
                scanHandler.postDelayed(scanDutyCycleRunnable, idleMs)
            }, window)
        }
    }

    private fun beginRadioScan() {
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()
        try {
            bleScanner?.startScan(null, settings, scanCallback)
        } catch (e: SecurityException) {
            DiagLogger.err("BLE", "Scan start failed: permission missing", e)
            isScanning.set(false)
        }
    }

    private fun endRadioScan() {
        try { bleScanner?.stopScan(scanCallback) } catch (t: Throwable) {
            DiagLogger.ble("endRadioScan (duty-cycle pause) threw: ${t.message}")
        }
    }

    override fun stopBleScan() {
        if (!isScanning.get()) return
        isScanning.set(false)
        scanHandler.removeCallbacksAndMessages(null)
        try { bleScanner?.stopScan(scanCallback) } catch (t: Throwable) {
            DiagLogger.ble("stopBleScan threw: ${t.message}")
        }
        DiagLogger.ble("BLE scan stopped")
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            rxScanAll.incrementAndGet()

            val scanRecord = result.scanRecord ?: return

            // Log only the first few raw advertisements for diagnostic purposes
            if (rawLogLimit.get() > 0) {
                val mfrData = scanRecord.getManufacturerSpecificData()
                val ids = mutableListOf<Int>()
                if (mfrData != null) {
                    for (i in 0 until mfrData.size()) {
                        ids.add(mfrData.keyAt(i))
                    }
                }
                DiagLogger.ble("raw_scan",
                    "addr" to result.device.address.takeLast(5),
                    "rssi" to result.rssi.toString(),
                    "mfr_ids" to ids.joinToString(","))
                rawLogLimit.decrementAndGet()
            }

            val manufacturerData = scanRecord.getManufacturerSpecificData(MANUFACTURER_ID) ?: return
            if (manufacturerData.size < 24) return  // AdvBeaconExt is 24 bytes

            rxTotal.incrementAndGet()

            val isSelf = ownNodeId?.let { own ->
                var match = true
                for (i in 0 until NODE_ID_LEN) {
                    if (manufacturerData[NODE_ID_OFFSET + i] != own[i]) { match = false; break }
                }
                match
            } ?: false

            if (isSelf) {
                rxLoopback.incrementAndGet()
                if (BuildConfig.DEBUG_LOOPBACK) {
                    DiagLogger.ble("LOOPBACK rx",
                        "rssi" to result.rssi.toString(),
                        "size" to manufacturerData.size.toString())
                    radioService?.onPacketReceived(manufacturerData, result.rssi)
                }
                return
            }

            rxPeer.incrementAndGet()
            DiagLogger.ble("BLE rx peer",
                "rssi" to result.rssi.toString(),
                "size" to manufacturerData.size.toString())
            cachedRssiMap[result.device.address] = result.rssi

            val nodeIdHex = manufacturerData.copyOfRange(NODE_ID_OFFSET, NODE_ID_OFFSET + NODE_ID_LEN)
                .joinToString("") { "%02x".format(it) }
            val wasNew = nodeIdToMac.put(nodeIdHex, result.device.address) == null

            // GAP 5: push immediately on discovery (don't wait 10s for heartbeat)
            MeshServiceConnection.nodeCount.value = nodeIdToMac.size
            val bestRssi = cachedRssiMap.values.maxOrNull() ?: result.rssi
            MeshServiceConnection.signalStrength.value = "$bestRssi dBm"

            radioService?.onPacketReceived(manufacturerData, result.rssi)


            // -- Establish a GATT client link so we can actually SEND to this peer.
            // sendBroadcastPacket() iterates bleSenderMap, which is only populated
            // once a GATT connection + service discovery completes. Without this
            // call, bleSenderMap stays empty and every sent message is silently
            // dropped.
            //
            // Dual-connect guard: if both phones connect simultaneously you get
            // two redundant links and a race. Rule: only the node with the
            // LOWER node id initiates the connection; the higher-id node waits
            // to be connected to (it still receives via its GATT server).
            if (wasNew) {
                val shouldInitiate = ownNodeId?.let { own ->
                    val ownHex = own.joinToString("") { "%02x".format(it) }
                    ownHex < nodeIdHex   // lexicographic compare of 16-hex node ids
                } ?: true
                if (shouldInitiate) {
                    DiagLogger.ble("Initiating GATT (lower id)", "peer" to result.device.address.takeLast(5))
                    connectToPeer(result.device.address)
                } else {
                    DiagLogger.ble("Awaiting GATT (higher id)", "peer" to result.device.address.takeLast(5))
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            DiagLogger.ble("BLE scan FAILED", "code" to errorCode.toString())
        }
    }

    override fun startBleAdvertising(adData: ByteArray, intervalMs: Int) {
        if (!hasAdvertisePermission()) {
            DiagLogger.ble("startBleAdvertising ABORT: missing BLUETOOTH_ADVERTISE permission")
            return
        }
        if (bluetoothAdapter?.isEnabled != true) {
            DiagLogger.ble("startBleAdvertising ABORT: BT disabled")
            return
        }
        if (bleAdvertiser == null) {
            // MIUI/Xiaomi and some OEMs return null for the advertiser at early
            // startup. Re-fetch it lazily here instead of giving up permanently.
            bleAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser
            DiagLogger.ble("startBleAdvertising: advertiser was null, re-fetched",
                "nowNull" to (bleAdvertiser == null).toString(),
                "multiAdvSupported" to (bluetoothAdapter?.isMultipleAdvertisementSupported ?: false).toString())
        }
        if (bleAdvertiser == null) {
            DiagLogger.ble("startBleAdvertising ABORT: advertiser still null after re-fetch")
            return
        }
        if (isAdvertising.get()) return

        pendingAdvertiseData = adData
        startLegacyAdvertising(adData)
    }

    private fun startLegacyAdvertising(adData: ByteArray) {
        // Legacy BLE budget: 31 - 3 (flags) - 4 (mfr envelope) = 24 bytes.
        val truncated = if (adData.size > 24) adData.copyOf(24) else adData
        DiagLogger.ble("Legacy adv starting",
            "payload" to truncated.size.toString(),
            "dropped" to (adData.size - truncated.size).toString())

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addManufacturerData(MANUFACTURER_ID, truncated)
            .build()

        try {
            bleAdvertiser?.startAdvertising(settings, data, legacyAdvertiseCallback)
        } catch (t: Throwable) {
            DiagLogger.ble("Legacy startAdvertising threw: ${t.message}")
            // Retry is handled by the periodic tick loop – no extra scheduling
        }
    }

    override fun stopBleAdvertising() {
        if (!isAdvertising.get()) return
        try {
            bleAdvertiser?.stopAdvertising(legacyAdvertiseCallback)
        } catch (t: Throwable) {
            DiagLogger.ble("stopAdvertising threw: ${t.message}")
        }
        isAdvertising.set(false)
    }

    private val legacyAdvertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            isAdvertising.set(true)
            txStarts.incrementAndGet()
            DiagLogger.ble("Legacy adv STARTED")
        }

        override fun onStartFailure(errorCode: Int) {
            isAdvertising.set(false)
            DiagLogger.ble("Legacy adv FAILED status=$errorCode – will retry on next tick")
            // No manual retry here – the tick loop naturally retries every second
        }
    }

    private fun startGattServerIfPermitted() {
        if (!hasScanPermission()) {
            DiagLogger.ble("GATT server start deferred: BLUETOOTH_SCAN permission missing")
            return
        }
        gattServerStartAttempted = true
        startGattServer()
    }

    private fun startGattServer() {
        try {
            gattServer?.close()
        } catch (_: Throwable) {}
        try {
            gattServer = bluetoothManager.openGattServer(
                context.applicationContext,
                gattServerCallback
            )
            val service = BluetoothGattService(MESH_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            val writeChar = BluetoothGattCharacteristic(
                MESH_CHARACTERISTIC_WRITE_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            service.addCharacteristic(writeChar)
            val notifyChar = BluetoothGattCharacteristic(
                MESH_CHARACTERISTIC_NOTIFY_UUID,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ
            )
            service.addCharacteristic(notifyChar)
            gattServer?.addService(service)
            DiagLogger.ble("GATT server started")
        } catch (e: SecurityException) {
            DiagLogger.err("BLE", "GATT server start failed: permission missing", e)
        } catch (e: IllegalArgumentException) {
            DiagLogger.err("BLE", "GATT server start failed: ${e.message} — will retry when Bluetooth restarts", e)
        } catch (e: Exception) {
            DiagLogger.err("BLE", "GATT server start failed: ${e.message}", e)
        }
    }

    private fun hasScanPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED

    private fun hasAdvertisePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (characteristic.uuid == MESH_CHARACTERISTIC_WRITE_UUID) {
                DiagLogger.ble("GATT write rx", "addr" to device.address.takeLast(5), "len" to value.size.toString())
                radioService?.onPacketReceived(value, cachedRssiMap[device.address] ?: -100)
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            }
        }
    }

    override fun connectToPeer(peerMacAddress: String): Boolean {
        if (bleGattMap.containsKey(peerMacAddress)) return true
        val device = bluetoothAdapter?.getRemoteDevice(peerMacAddress) ?: return false
        DiagLogger.ble("Connecting GATT to $peerMacAddress")
        val gatt = device.connectGatt(context, false, gattClientCallback)
        bleGattMap[peerMacAddress] = gatt
        return true
    }

    override fun sendBlePacket(peerMacAddress: String, data: ByteArray): Boolean {
        val sender = bleSenderMap[peerMacAddress]
        if (sender == null) {
            DiagLogger.ble("sendBlePacket: no sender for $peerMacAddress, queuing after connect")
            return false
        }
        DiagLogger.ble("GATT write tx", "peer" to peerMacAddress.takeLast(5), "len" to data.size.toString())
        sender.send(data)
        return true
    }

    override fun getMacForNodeId(nodeIdHex: String): String? = nodeIdToMac[nodeIdHex]

    override fun sendToNodeId(nodeIdHex: String, data: ByteArray): Boolean {
        val mac = nodeIdToMac[nodeIdHex]
        if (mac == null) {
            DiagLogger.ble("sendToNodeId: peer $nodeIdHex not yet discovered, dropping")
            return false
        }

        val sender = bleSenderMap[mac]
        if (sender != null) {
            DiagLogger.ble("GATT write tx (direct)", "peer" to mac.takeLast(5), "len" to data.size.toString())
            sender.send(data)
            return true
        }

        // No live sender yet: queue the packet and make sure a GATT
        // connection is (or gets) established, so onServicesDiscovered's
        // flush picks this up once the sender becomes ready.
        DiagLogger.ble("sendToNodeId: queuing for $nodeIdHex (${mac.takeLast(5)}), no sender yet")
        pendingPacketsByMac.getOrPut(mac) { mutableListOf() }.add(data)
        connectToPeer(mac)
        return true
    }

    override fun disconnectPeer(peerMacAddress: String) {
        bleGattMap.remove(peerMacAddress)?.close()
        bleSenderMap.remove(peerMacAddress)?.close()
        pendingPacketsByMac.remove(peerMacAddress)
    }

    override fun sendBroadcastPacket(data: ByteArray) {
        bleSenderMap.keys.forEach { peer ->
            sendBlePacket(peer, data)
        }
    }

    private val gattClientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (gatt == null) return
            val addr = gatt.device.address
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                DiagLogger.ble("GATT connected to $addr, requesting MTU")
                // Negotiate a large MTU BEFORE discovering services. Olm pre-key
                // messages (~200B) and KeyAnnouncements (90B) exceed the default
                // 23-byte ATT MTU, so the single-write transport needs the bump.
                // Service discovery is deferred to onMtuChanged so the larger MTU
                // is in effect before we start sending.
                if (!gatt.requestMtu(517)) {
                    DiagLogger.ble("requestMtu failed to start; falling back to discoverServices")
                    gatt.discoverServices()
                }
            } else {
                DiagLogger.ble("GATT disconnected $addr")
                bleSenderMap.remove(addr)?.close()
                bleGattMap.remove(addr)?.close()
                pendingPacketsByMac.remove(addr)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            if (gatt == null) return
            DiagLogger.ble("GATT MTU negotiated: $mtu (status=$status) for ${gatt.device.address}")
            // Proceed to discovery regardless of status; discovery works at any MTU.
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (gatt == null || status != BluetoothGatt.GATT_SUCCESS) return
            val service = gatt.getService(MESH_SERVICE_UUID) ?: return
            val writeChar = service.getCharacteristic(MESH_CHARACTERISTIC_WRITE_UUID) ?: return
            val sender = BlePacketSender(gatt)
            sender.setCharacteristic(writeChar)
            bleSenderMap[gatt.device.address] = sender
            DiagLogger.ble("GATT service discovered, sender ready for ${gatt.device.address}")
            val pending = pendingPacketsByMac.remove(gatt.device.address) ?: emptyList()
            if (pending.isNotEmpty()) {
                DiagLogger.ble("Flushing ${pending.size} queued packet(s) for ${gatt.device.address.takeLast(5)}")
            }
            pending.forEach { sender.send(it) }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            if (gatt == null) return
            val sender = bleSenderMap[gatt.device.address] ?: return
            sender.onWriteComplete(status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    override fun isWifiDirectSupported() = wifiP2pManager != null

    override fun startWifiDirectDiscovery() {
        val manager = wifiP2pManager
        val channel = wifiP2pChannel
        if (manager == null || channel == null) {
            DiagLogger.ble("startWifiDirectDiscovery: WiFi Direct not available")
            return
        }
        if (!hasWifiDirectPermission()) {
            DiagLogger.ble("startWifiDirectDiscovery ABORT: missing location/NEARBY_WIFI_DEVICES permission")
            return
        }
        try {
            manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    wifiDiscoveryActive.set(true)
                    DiagLogger.ble("WiFi Direct discovery started")
                }
                override fun onFailure(reason: Int) {
                    DiagLogger.ble("WiFi Direct discovery failed: reason=$reason")
                }
            })
        } catch (e: SecurityException) {
            DiagLogger.ble("startWifiDirectDiscovery SecurityException: ${e.message}")
        }
    }

    override fun stopWifiDirectDiscovery() {
        val manager = wifiP2pManager
        val channel = wifiP2pChannel
        if (manager == null || channel == null) return
        if (!hasWifiDirectPermission()) return
        try {
            manager.stopPeerDiscovery(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    wifiDiscoveryActive.set(false)
                    DiagLogger.ble("WiFi Direct discovery stopped")
                }
                override fun onFailure(reason: Int) {
                    DiagLogger.ble("WiFi Direct stopDiscovery failed: reason=$reason")
                }
            })
        } catch (e: SecurityException) {
            DiagLogger.ble("stopWifiDirectDiscovery SecurityException: ${e.message}")
        }
    }

    override fun connectWifiDirect(peerMacAddress: String): Boolean {
        val manager = wifiP2pManager
        val channel = wifiP2pChannel
        if (manager == null || channel == null) return false
        if (!hasWifiDirectPermission()) {
            DiagLogger.ble("connectWifiDirect ABORT: missing permission")
            return false
        }
        val config = WifiP2pConfig().apply { deviceAddress = peerMacAddress }
        return try {
            manager.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    DiagLogger.ble("WiFi Direct connect requested: $peerMacAddress")
                    // Actual group formation + GO/client role + IP resolution
                    // arrives asynchronously via WIFI_P2P_CONNECTION_CHANGED_ACTION,
                    // handled in setupWifiDirectReceiver.
                }
                override fun onFailure(reason: Int) {
                    DiagLogger.ble("WiFi Direct connect failed: reason=$reason")
                }
            })
            true
        } catch (e: SecurityException) {
            DiagLogger.ble("connectWifiDirect SecurityException: ${e.message}")
            false
        }
    }

    private val wifiSenders = ConcurrentHashMap<String, WifiPacketSender>()

    override fun sendWifiPacket(peerIpAddress: String, port: Int, data: ByteArray): Boolean {
        return try {
            val sender = wifiSenders.getOrPut(peerIpAddress) { WifiPacketSender(peerIpAddress, port) }
            // WifiPacketSender.send() does blocking I/O (socket connect +
            // write) -- must not run on the calling thread if that's the
            // main thread. Callers in this codebase (ActionDispatcher, via
            // RezvanRadioService's serviceScope) already run on Dispatchers.IO,
            // but sendWifiPacket itself has no coroutine context to hop into
            // without changing this interface to suspend; run it on a plain
            // background thread here as a safety net for any future caller
            // that doesn't already guarantee that.
            val result = AtomicReference<Boolean>()
            val t = Thread {
                result.set(sender.send(data))
            }
            t.isDaemon = true
            t.start()
            t.join(WIFI_SEND_TIMEOUT_MS)
            result.get() ?: false
        } catch (e: Exception) {
            DiagLogger.err("WIFI", "sendWifiPacket failed to $peerIpAddress:$port: ${e.message}", e)
            false
        }
    }

    override fun disconnectWifiDirect(peerIpAddress: String) {
        wifiSenders.remove(peerIpAddress)?.close()
        wifiClients.remove(peerIpAddress)?.let {
            try { it.close() } catch (_: IOException) {}
        }
    }

    private var wifiGroupOwnerAddress: String? = null
    private var isWifiGroupOwner = false
    private val wifiDiscoveryActive = AtomicBoolean(false)

    private fun setupWifiDirectReceiver() {
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        val channel = wifiP2pChannel ?: return
                        if (!hasWifiDirectPermission()) return
                        try {
                            wifiP2pManager?.requestPeers(channel) { peers: WifiP2pDeviceList ->
                                DiagLogger.ble("WiFi Direct peers changed: ${peers.deviceList.size} peer(s)")
                            }
                        } catch (e: SecurityException) {
                            DiagLogger.ble("requestPeers SecurityException: ${e.message}")
                        }
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val channel = wifiP2pChannel ?: return
                        if (!hasWifiDirectPermission()) return
                        try {
                            wifiP2pManager?.requestConnectionInfo(channel) { info ->
                                if (info == null || !info.groupFormed) {
                                    wifiGroupOwnerAddress = null
                                    isWifiGroupOwner = false
                                    return@requestConnectionInfo
                                }
                                isWifiGroupOwner = info.isGroupOwner
                                wifiGroupOwnerAddress = info.groupOwnerAddress?.hostAddress
                                DiagLogger.ble(
                                    "WiFi Direct group formed",
                                    "isGroupOwner" to isWifiGroupOwner.toString(),
                                    "groupOwner" to (wifiGroupOwnerAddress ?: "unknown")
                                )
                                // The group owner runs the ServerSocket (started once,
                                // in init{}, regardless of role -- harmless if unused
                                // on the client side). Clients connect out to the GO's
                                // address via WifiPacketSender, created lazily in
                                // sendWifiPacket() the first time it's needed.
                            }
                        } catch (e: SecurityException) {
                            DiagLogger.ble("requestConnectionInfo SecurityException: ${e.message}")
                        }
                    }
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        DiagLogger.ble("WiFi P2P state changed: $state")
                    }
                }
            }
        }
        wifiDirectReceiver = receiver
        context.registerReceiver(receiver, filter)
    }

    private fun hasWifiDirectPermission(): Boolean {
        // WifiP2pManager peer/connection APIs require ACCESS_FINE_LOCATION
        // pre-API33, and NEARBY_WIFI_DEVICES from API33+ (already declared in
        // the manifest). Reuse the same location-permission check already
        // used for BLE scanning, since the underlying requirement is the same.
        return hasLocationPermission()
    }

    /**
     * Starts a background thread listening for incoming WiFi-Direct
     * connections. Only the group owner's ServerSocket actually gets
     * connections from peers (WifiPacketSender clients connect to the GO's
     * address, resolved via the ConnectionInfoListener above). Started
     * unconditionally in init{} since we don't know our GO/client role until
     * a group actually forms -- harmless to have a listening socket if we
     * end up being the client instead.
     */
    private fun startWifiServer() {
        val thread = Thread {
            try {
                val server = ServerSocket(WIFI_PORT)
                serverSocket = server
                DiagLogger.ble("WiFi Direct server listening on port $WIFI_PORT")
                while (!Thread.currentThread().isInterrupted) {
                    val client = try {
                        server.accept()
                    } catch (e: IOException) {
                        break // socket closed, e.g. via onDestroy
                    }
                    val addr = client.inetAddress?.hostAddress ?: "unknown"
                    wifiClients[addr] = client
                    DiagLogger.ble("WiFi Direct client connected: $addr")
                    Thread { handleWifiClient(addr, client) }.apply {
                        isDaemon = true
                        start()
                    }
                }
            } catch (e: IOException) {
                DiagLogger.err("WIFI", "WiFi Direct server failed to start: ${e.message}", e)
            }
        }
        thread.isDaemon = true
        serverThread.set(thread)
        thread.start()
    }

    /**
     * Reads length-prefixed packets from one connected WiFi-Direct client,
     * matching WifiPacketSender's exact framing (2-byte big-endian length
     * prefix, then that many bytes of packet data). Feeds each complete
     * packet into the same onPacketReceived entry point BLE GATT packets
     * use, with rssi=0 as a placeholder (WiFi has no RSSI-equivalent signal
     * quality metric used anywhere in the current routing logic).
     */
    private fun handleWifiClient(addr: String, socket: Socket) {
        try {
            val input = DataInputStream(socket.getInputStream())
            while (!socket.isClosed) {
                val len = input.readUnsignedShort()
                val buf = ByteArray(len)
                input.readFully(buf)
                radioService?.onPacketReceived(buf, 0)
            }
        } catch (e: EOFException) {
            DiagLogger.ble("WiFi Direct client disconnected: $addr")
        } catch (e: IOException) {
            DiagLogger.ble("WiFi Direct client read error ($addr): ${e.message}")
        } finally {
            wifiClients.remove(addr)
            try { socket.close() } catch (_: IOException) {}
        }
    }
    /**
     * Stops the WiFi Direct server thread and closes its listening socket +
     * any connected clients. Was previously an empty stub (`{}`) that did
     * nothing -- `onDestroy()` happened to do the equivalent cleanup work
     * inline instead of calling this, so there was no actual resource leak
     * in the one place this was invoked from, but the stub itself was dead,
     * misleading code: its doc-implied contract ("stop the server") was
     * never fulfilled, and any future caller relying on it to actually stop
     * the server (e.g. to restart on a WiFi Direct group re-formation,
     * which this class does not currently do but plausibly could) would
     * have silently gotten a no-op. Implemented for real here, and
     * `onDestroy()` below now calls this instead of duplicating the same
     * cleanup inline.
     */
    private fun stopWifiServer() {
        serverThread.get()?.interrupt()
        try { serverSocket?.close() } catch (_: IOException) {}
        serverSocket = null
        wifiClients.values.forEach { try { it.close() } catch (_: IOException) {} }
        wifiClients.clear()
        DiagLogger.ble("WiFi Direct server stopped")
    }

    override fun getCurrentRssi(peerMacAddress: String): Int {
        // Bug fix: this was hardcoded to always return Int.MIN_VALUE, even
        // though the real, live RSSI cache (`cachedRssiMap`, populated in
        // scanCallback.onScanResult above) already exists in this same
        // class. Nothing calls this method today (confirmed: it's declared
        // on the RadioController interface but has no callers anywhere in
        // the app), so this was a landmine for any future caller rather
        // than an active bug -- but a public method silently ignoring the
        // working data sitting right next to it is exactly the kind of
        // thing that causes a confusing regression later. Int.MIN_VALUE is
        // kept as the not-found fallback (same sentinel the old code always
        // returned), now only for a MAC we've genuinely never heard from.
        return cachedRssiMap[peerMacAddress] ?: Int.MIN_VALUE
    }
    override fun setBleTxPower(dbm: Int) {}
    override fun setWifiTxPower(dbm: Int) {}

    private fun startHeartbeat() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        heartbeatHandler.postDelayed(heartbeatRunnable, 10_000L)
    }

    private fun stopHeartbeat() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
    }

    override fun onDestroy() {
        stopHeartbeat()
        stopBleScan()
        stopBleAdvertising()
        bleGattMap.values.forEach { it.close() }
        gattServer?.close()
        try { context.unregisterReceiver(btStateReceiver) } catch (_: Throwable) {}
        try { wifiDirectReceiver?.let { context.unregisterReceiver(it) } } catch (_: Throwable) {}
        stopWifiServer()
        wifiSenders.values.forEach { it.close() }
        wifiSenders.clear()
        try { wifiP2pChannel?.let { wifiP2pManager?.stopPeerDiscovery(it, null) } } catch (_: Throwable) {}
        DiagLogger.ble("RadioController destroyed")
    }

    companion object {
        private const val TAG = "RadioControllerImpl"
        private const val MANUFACTURER_ID = 0xFFFF
        private const val NODE_ID_OFFSET = 3
        private const val NODE_ID_LEN = 8
        private val BLE_SERVICE_UUID = ParcelUuid(UUID.fromString("0000a1b2-0000-1000-8000-00805f9b34fb"))
        const val WIFI_PORT = 4237
        private const val WIFI_SEND_TIMEOUT_MS = 5000L
    }
}