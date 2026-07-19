package com.rezvani.mesh.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import com.rezvani.mesh.utils.DiagLogger
import java.io.File
import java.util.UUID

/**
 * Plays received voice broadcasts.
 *
 * Wire payload handed in by the service: [severity:1][codec:1][audio bytes...]
 * where the audio bytes are a containerized recording (AMR-WB / .amr), not raw
 * PCM -- so playback goes through MediaPlayer via a temp file, which handles the
 * container and codec.
 *
 * Severity (matches VoiceUiState): 1=Advisory 2=Watch 3=Warning 4=Critical 5=Emergency.
 * For Critical+ we play on the ALARM stream at maximum volume (and restore the
 * previous alarm volume afterwards) so an emergency is heard even on silent.
 */
class VoicePlayer(private val context: Context) {

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var player: MediaPlayer? = null
    private var playingSeverity: Int = 0
    private var savedAlarmVolume: Int? = null

    /** codec: 0 = AMR-WB (.amr). Other values fall back to .amr container. */
    fun play(severity: Int, codec: Byte, audio: ByteArray) {
        if (audio.isEmpty()) return

        // A higher- or equal-severity clip interrupts the current one; a lower
        // one is dropped while something is already playing.
        if (player != null) {
            if (severity >= playingSeverity) stopInternal() else return
        }

        val ext = if (codec.toInt() == 0) "amr" else "amr"
        val tmp = File(context.cacheDir, "rx_voice_${UUID.randomUUID()}.$ext")
        try {
            tmp.writeBytes(audio)
        } catch (e: Exception) {
            DiagLogger.err("VOICE", "Failed to buffer voice clip: ${e.message}", e)
            return
        }

        val emergency = severity >= 4
        val usage = if (emergency) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_MEDIA

        if (emergency) raiseAlarmVolume()

        try {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(usage)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(tmp.absolutePath)
                setOnPreparedListener { it.start() }
                setOnCompletionListener { cleanup(tmp) }
                setOnErrorListener { _, what, extra ->
                    DiagLogger.err("VOICE", "MediaPlayer error what=$what extra=$extra")
                    cleanup(tmp); true
                }
                prepareAsync()
            }
            playingSeverity = severity
            DiagLogger.ble("Voice rx: playing severity=$severity bytes=${audio.size}")
        } catch (e: Exception) {
            DiagLogger.err("VOICE", "Voice playback failed: ${e.message}", e)
            cleanup(tmp)
        }
    }

    fun stop() = stopInternal()

    private fun stopInternal() {
        try { player?.stop() } catch (_: Exception) {}
        player?.release()
        player = null
        playingSeverity = 0
        restoreAlarmVolume()
    }

    private fun cleanup(tmp: File) {
        player?.release()
        player = null
        playingSeverity = 0
        restoreAlarmVolume()
        runCatching { tmp.delete() }
    }

    private fun raiseAlarmVolume() {
        if (savedAlarmVolume != null) return
        savedAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        runCatching { audioManager.setStreamVolume(AudioManager.STREAM_ALARM, max, 0) }
    }

    private fun restoreAlarmVolume() {
        val saved = savedAlarmVolume ?: return
        runCatching { audioManager.setStreamVolume(AudioManager.STREAM_ALARM, saved, 0) }
        savedAlarmVolume = null
    }
}