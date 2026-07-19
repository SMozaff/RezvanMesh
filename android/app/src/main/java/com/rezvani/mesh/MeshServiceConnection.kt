// android/app/src/main/java/com/rezvani/mesh/MeshServiceConnection.kt

package com.rezvani.mesh

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.app.Application
import com.rezvani.mesh.data.DbKeyProvider
import com.rezvani.mesh.data.repositories.MessageRepository
import com.rezvani.mesh.radio.RezvanRadioService
import com.rezvani.mesh.rust.DecryptedMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class MeshServiceConnection(private val context: Context) : ServiceConnection {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Per-install, Keystore-backed key shared with every ViewModel via
    // DbKeyProvider -- see that file for the single source of truth.
    private val dbPassphrase by lazy { DbKeyProvider.getOrCreateKey(context.applicationContext) }
    private val messageRepo by lazy { MessageRepository(context.applicationContext as Application, dbPassphrase) }

    private val _receivedMessages = MutableStateFlow<List<DecryptedMessage>>(emptyList())
    val receivedMessages: StateFlow<List<DecryptedMessage>> = _receivedMessages

    companion object {
        val nodeCount      = MutableStateFlow(0)
        val signalStrength = MutableStateFlow("-68 dBm")
        val batteryLevel   = MutableStateFlow(100)
        val isCharging     = MutableStateFlow(false)
        val isServiceConnected = MutableStateFlow(false)
        val meshCorePtr = MutableStateFlow<Long?>(null)
        var activeService: RezvanRadioService? = null
            private set

        fun onServiceConnected(service: RezvanRadioService) {
            activeService = service
            isServiceConnected.value = true
        }

        fun onServiceDisconnected() {
            activeService = null
            isServiceConnected.value = false
        }
    }

    fun sendTextMessage(peerNodeId: ByteArray, text: String) {
        activeService?.sendMessage(peerNodeId, text.toByteArray())
    }

    fun sendEmergencyBroadcast(message: String) {
        activeService?.sendBroadcast(message.toByteArray())
    }

    fun addReceivedMessage(msg: DecryptedMessage) {
        // 1. Keep in-memory flow for live UI updates this session
        _receivedMessages.value = _receivedMessages.value + msg

        // 2. Persist to SQLCipher DB so messages survive restart.
        //
        // CRITICAL: key the conversation by the SENDER's node id, not the engine's
        // conversation_id field, for 1:1 messages. For channel messages
        // (messageType == 6), conversation_id's first 4 bytes carry the
        // channel_id (big-endian u32) instead -- see engine.rs's 0x06 handler
        // -- so we build "channel_<id>" as the conversation key, matching
        // ChannelDetailViewModel's convention.
        scope.launch {
            try {
                val senderHex = msg.senderId.joinToString("") { "%02x".format(it) }
                val conversationId = if ((msg.messageType.toInt() and 0xFF) == 6) {
                    val channelId = ((msg.conversationId[0].toInt() and 0xFF) shl 24) or
                            ((msg.conversationId[1].toInt() and 0xFF) shl 16) or
                            ((msg.conversationId[2].toInt() and 0xFF) shl 8) or
                            (msg.conversationId[3].toInt() and 0xFF)
                    "channel_$channelId"
                } else {
                    senderHex   // <-- peer node id = the 1:1 chat key
                }
                messageRepo.insertReceivedMessage(
                    messageId      = UUID.randomUUID().toString(),
                    conversationId = conversationId,
                    senderId       = senderHex,
                    timestamp      = if (msg.timestamp > 0) msg.timestamp else System.currentTimeMillis(),
                    type           = msg.messageType.toInt() and 0xFF,
                    content        = String(msg.content, Charsets.UTF_8)
                )
            } catch (e: Exception) {
                // Log silently - don't crash the radio receive path
            }
        }
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        val binder = service as? RezvanRadioService.LocalBinder
        activeService = binder?.getService()
        activeService?.setConnection(this)
        isServiceConnected.value = true
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        activeService?.setConnection(null)
        activeService = null
        isServiceConnected.value = false
    }
}