package com.rezvani.mesh.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rezvani.mesh.MeshCore
import com.rezvani.mesh.MeshServiceConnection
import com.rezvani.mesh.radio.PeerSnapshot
import com.rezvani.mesh.radio.RadioControllerImpl
import com.rezvani.mesh.utils.DiagLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class StatusUiState(
    val active: Boolean = false,
    val statusDetail: String = "No peers detected - Seeking devices...",
    val signalStrength: String = "--",
    val nodeCount: Int = 0,
    val batteryLevel: Int = 100,
    val isCharging: Boolean = false,
    val logLines: List<String> = emptyList(),
    val radioSnapshot: Map<String, Long> = emptyMap(),
    val peers: List<PeerUiModel> = emptyList()
)

data class PeerUiModel(
    val nodeIdHex: String,
    val rssi: Int?,
    val connected: Boolean
)

class StatusViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(StatusUiState())
    val uiState: StateFlow<StatusUiState> = _uiState.asStateFlow()

    private val _radioState = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val _peerState = MutableStateFlow<List<PeerUiModel>>(emptyList())

    private var mockCounter = 0

    init {
        viewModelScope.launch {
            val logFlow = DiagLogger.entries.map { list -> list.map { it.formatted() } }
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

            val baseFlow = combine(
                MeshServiceConnection.nodeCount,
                MeshServiceConnection.signalStrength,
                MeshServiceConnection.isServiceConnected,
                MeshServiceConnection.batteryLevel,
                MeshServiceConnection.isCharging
            ) { values -> values }
            combine(baseFlow, logFlow, _radioState, _peerState) { base, logs, radio, peers ->
                val count     = base[0] as Int
                val strength  = base[1] as String
                val connected = base[2] as Boolean
                val battery   = base[3] as Int
                val charging  = base[4] as Boolean
                val active = connected && count > 0
                StatusUiState(
                    active = active,
                    statusDetail = if (active) {
                        "$count device${if (count > 1) "s" else ""} connected"
                    } else if (connected) {
                        "Listening for devices..."
                    } else {
                        "Service disconnected"
                    },
                    signalStrength = strength,
                    nodeCount = count,
                    batteryLevel = battery,
                    isCharging = charging,
                    logLines = logs,
                    radioSnapshot = radio,
                    peers = peers
                )
            }.collect { _uiState.value = it }
        }

        // Poll radio counters every 2 seconds
        viewModelScope.launch {
            while (isActive) {
                delay(2000)
                try {
                    val ctrl = MeshServiceConnection.activeService
                        ?.getRadioController() as? RadioControllerImpl
                    if (ctrl != null) {
                        _radioState.value = ctrl.snapshotCounters()
                        _peerState.value = ctrl.snapshotPeers().map { it.toUiModel() }
                    } else {
                        _radioState.value = emptyMap()
                        _peerState.value = emptyList()
                    }
                } catch (_: Exception) { }
            }
        }
    }

    private fun PeerSnapshot.toUiModel() = PeerUiModel(
        nodeIdHex = nodeIdHex,
        rssi = rssi,
        connected = connected
    )

    fun injectMockPeer() {
        viewModelScope.launch {
            try {
                val originator = ByteArray(8) { (0xA0 + mockCounter + it).toByte() }
                mockCounter++

                // AdvBeaconExt layout (rezvan-common/src/lib.rs), NOT the old
                // MeshPacketHeader-shaped 62-byte format this used to build --
                // packet_type 0x01 is strictly the 24-byte beacon format,
                // checked by EXACT length in process_incoming (anything else
                // for type 0x01 falls through unparsed and does nothing).
                // The 7-byte MAC field is left zero, so this will be treated
                // as an unverified/unknown-sender beacon (discovery-only, no
                // route added) -- expected for a mock peer with no real key,
                // see engine.rs::process_beacon's `verified` gate.
                val beacon = ByteArray(24).apply {
                    this[0] = 0x02 // AdvBeaconExt::VERSION
                    this[1] = 0x01 // packet_type = beacon
                    System.arraycopy(originator, 0, this, 2, 8)
                    this[13] = mockCounter.toByte() // sequence low byte (BE u32 at [10..14])
                    this[14] = 80  // battery %
                    this[15] = 1   // power_state = Active
                    this[16] = 0   // node_flags
                    // [17..24) mac left zero -- unverifiable by design (mock peer)
                }

                val ptr = MeshServiceConnection.meshCorePtr.value
                if (ptr != null && ptr != 0L) {
                    MeshCore.nativeProcessIncoming(ptr, beacon, -50, System.currentTimeMillis() * 1000)
                    DiagLogger.ble("Mock peer injected, RSSI=-50")
                }
            } catch (e: Exception) {
                DiagLogger.rust("Mock inject failed: ${e.message}")
            }
        }
    }
}