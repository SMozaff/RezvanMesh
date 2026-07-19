package com.rezvani.mesh.ui.viewmodel

import android.app.Application
import android.media.MediaRecorder
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rezvani.mesh.MeshServiceConnection
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

data class VoiceUiState(
    val receptionEnabled: Boolean = true,
    val severityLevel: Int = 1, // 1=Advisory, 2=Watch, 3=Warning, 4=Critical, 5=Emergency
    val isRecording: Boolean = false,
    val canRecord: Boolean = true,
    val status: Status = Status.Ready
) {
    sealed class Status {
        object Ready : Status()
        object Recording : Status()
        object Sending : Status()
        object Sent : Status()
        data class Error(val message: String) : Status()
    }
}

class VoiceViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(VoiceUiState())
    val uiState: StateFlow<VoiceUiState> = _uiState.asStateFlow()

    private var mediaRecorder: MediaRecorder? = null
    private var recordingFile: File? = null

    // Toggle reception state (stored in SharedPreferences, loaded later)
    fun toggleReception(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(receptionEnabled = enabled)
        // Save to SharedPreferences (persist across restarts)
        getApplication<Application>().getSharedPreferences("voice_prefs", 0)
            .edit().putBoolean("voice_reception", enabled).apply()
    }

    fun setSeverity(level: Int) {
        _uiState.value = _uiState.value.copy(severityLevel = level)
    }

    fun startRecording() {
        if (_uiState.value.isRecording) return
        _uiState.value = _uiState.value.copy(
            isRecording = true,
            canRecord = false,
            status = VoiceUiState.Status.Recording
        )
        recordingFile = File(getApplication<Application>().cacheDir, "voice_${UUID.randomUUID()}.amr")
        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(getApplication())
        } else {
            MediaRecorder()
        }.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.AMR_WB)
            setAudioEncoder(if (Build.VERSION.SDK_INT >= 29) MediaRecorder.AudioEncoder.OPUS else MediaRecorder.AudioEncoder.AMR_WB)
            setOutputFile(recordingFile!!.absolutePath)
            setMaxDuration(10_000) // 10 seconds max
            setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                    stopRecording()
                }
            }
            prepare()
            start()
        }
    }

    fun stopRecording() {
        val recorder = mediaRecorder ?: return
        try {
            recorder.stop()
            recorder.release()
        } catch (_: Exception) {}
        mediaRecorder = null
        _uiState.value = _uiState.value.copy(isRecording = false)
        val file = recordingFile ?: return
        if (file.exists() && file.length() > 0) {
            sendVoiceFile(file)
        } else {
            _uiState.value = _uiState.value.copy(canRecord = true, status = VoiceUiState.Status.Ready)
        }
    }

    private fun sendVoiceFile(file: File) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(status = VoiceUiState.Status.Sending)
            try {
                val data = file.readBytes()
                // Prepend codec byte: 0 = AMR-WB, 1 = Opus
                val codec: Byte = if (Build.VERSION.SDK_INT >= 29) 1 else 0
                val severity = _uiState.value.severityLevel.toByte()
                val packet = byteArrayOf(severity, codec) + data
                MeshServiceConnection.activeService?.sendVoiceBroadcast(packet)
                delay(500) // brief status display
                _uiState.value = _uiState.value.copy(
                    canRecord = true,
                    status = VoiceUiState.Status.Sent
                )
                // Reset after a moment
                delay(2000)
                _uiState.value = _uiState.value.copy(status = VoiceUiState.Status.Ready)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    canRecord = true,
                    status = VoiceUiState.Status.Error(e.message ?: "Send failed")
                )
            }
        }
    }

    fun onPermissionGranted() {
        _uiState.value = _uiState.value.copy(canRecord = true)
    }

    fun onPermissionDenied() {
        _uiState.value = _uiState.value.copy(
            canRecord = true,
            status = VoiceUiState.Status.Error("Microphone permission required")
        )
    }

    init {
        // Load reception preference
        val prefs = getApplication<Application>().getSharedPreferences("voice_prefs", 0)
        val reception = prefs.getBoolean("voice_reception", true)
        _uiState.value = _uiState.value.copy(receptionEnabled = reception)
    }
}
