// android/app/src/main/java/com/rezvani/mesh/ui/viewmodel/EmergencyViewModel.kt

package com.rezvani.mesh.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rezvani.mesh.MeshServiceConnection
import kotlinx.coroutines.delay
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

    fun sendEmergencyAlert() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(sendStatus = EmergencySendStatus.Sending)
            try {
                val messageText = "EMERGENCY LEVEL ${_uiState.value.selectedSeverity}"
                // Previously called sendMessage(broadcastId=[0xFF;8], ...) --
                // the 1:1 Olm-encrypted direct-message path with a fake
                // all-0xFF "recipient". There's no Olm session with that
                // address, so send_message would silently fail every time.
                // sendBroadcast calls the real signed broadcast path
                // (MeshEngine::send_broadcast, packet_type 0x03).
                MeshServiceConnection.activeService?.sendBroadcast(messageText.toByteArray())
                delay(1000)
                _uiState.value = _uiState.value.copy(sendStatus = EmergencySendStatus.Success("Alert sent"))
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(sendStatus = EmergencySendStatus.Failed(e.message ?: "Unknown error"))
            }
        }
    }
}

data class EmergencyUiState(
    val selectedSeverity: Int = 1,
    val sendStatus: EmergencySendStatus = EmergencySendStatus.Idle
)

sealed class EmergencySendStatus {
    object Idle : EmergencySendStatus()
    object Sending : EmergencySendStatus()
    data class Success(val message: String) : EmergencySendStatus()
    data class Failed(val message: String) : EmergencySendStatus()
}