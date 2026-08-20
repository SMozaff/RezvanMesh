// android/app/src/main/java/com/rezvani/mesh/ui/viewmodel/EmergencyViewModel.kt

package com.rezvani.mesh.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rezvani.mesh.MeshServiceConnection
import com.rezvani.mesh.radio.SendResult
import com.rezvani.mesh.radio.failureMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class EmergencyViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(EmergencyUiState())
    val uiState: StateFlow<EmergencyUiState> = _uiState.asStateFlow()

    fun updateSeverity(level: Int) {
        _uiState.value = _uiState.value.copy(selectedSeverity = level)
    }

    /**
     * Submits a signed emergency payload to the local mesh transport. The UI
     * never describes this as delivered because the current protocol does not
     * produce a remote acknowledgement on this path.
     */
    fun sendEmergencyAlert() {
        if (_uiState.value.sendStatus is EmergencySendStatus.Submitting) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(sendStatus = EmergencySendStatus.Submitting)
            val messageText = "EMERGENCY LEVEL ${_uiState.value.selectedSeverity}"
            val result = MeshServiceConnection.activeService
                ?.sendBroadcast(messageText.toByteArray())
                ?: SendResult.NotReady
            _uiState.value = _uiState.value.copy(sendStatus = result.toEmergencyStatus())
        }
    }

    private fun SendResult.toEmergencyStatus(): EmergencySendStatus = when (this) {
        is SendResult.Queued -> EmergencySendStatus.Queued(
            if (peerCount == 1) {
                "Alert queued for 1 nearby mesh peer. Delivery is not yet confirmed."
            } else {
                "Alert queued for $peerCount nearby mesh peers. Delivery is not yet confirmed."
            }
        )
        else -> EmergencySendStatus.Failed(failureMessage())
    }
}

data class EmergencyUiState(
    val selectedSeverity: Int = 1,
    val sendStatus: EmergencySendStatus = EmergencySendStatus.Idle
)

sealed class EmergencySendStatus {
    data object Idle : EmergencySendStatus()
    data object Submitting : EmergencySendStatus()
    data class Queued(val message: String) : EmergencySendStatus()
    data class Failed(val message: String) : EmergencySendStatus()
}
