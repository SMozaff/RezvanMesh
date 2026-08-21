// android/app/src/main/java/com/rezvani/mesh/MeshServiceConnection.kt

package com.rezvani.mesh

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.app.Application
import com.rezvani.mesh.data.DbKeyProvider
import com.rezvani.mesh.data.repositories.MessageRepository
import com.rezvani.mesh.data.repositories.ProtocolMessageId
import com.rezvani.mesh.radio.RezvanRadioService
import com.rezvani.mesh.radio.SendResult
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
        val activeServiceFlow = MutableStateFlow<RezvanRadioService?>(null)
        val ownNodeId = MutableStateFlow<ByteArray?>(null)
        val meshCorePtr = MutableStateFlow<Long?>(null)
        var activeService: RezvanRadioService? = null
            private set
        private var activeConnection: MeshServiceConnection? = null

        fun registerConnection(connection: MeshServiceConnection) {
            activeConnection = connection
            activeService?.setConnection(connection)
        }

        fun onServiceConnected(service: RezvanRadioService) {
            activeService = service
            activeServiceFlow.value = service
            activeConnection?.let(service::setConnection)
            isServiceConnected.value = true
        }

        fun onServiceDisconnected() {
            activeService = null
            activeServiceFlow.value = null
            // Keep the UI bridge for automatic service reconnects.
            ownNodeId.value = null
            isServiceConnected.value = false
        }
    }

    suspend fun sendTextMessage(peerNodeId: ByteArray, text: String): SendResult {
        val protocolId = ProtocolMessageId.toBytes(ProtocolMessageId.generateHex())
            ?: return SendResult.Failed("Could not create persistent message identity")
        return activeService?.sendMessage(peerNodeId, protocolId, System.currentTimeMillis(), text.toByteArray())
            ?: SendResult.NotReady
    }

    suspend fun sendEmergencyBroadcast(message: String): SendResult =
        activeService?.sendBroadcast(message.toByteArray()) ?: SendResult.NotReady

    /**
     * Persists an inbound message. A non-null return means a Gate 1 direct
     * message committed (or matched a committed duplicate) and may be
     * acknowledged by the radio service.
     */
    suspend fun addReceivedMessage(msg: DecryptedMessage): ReceiptAcknowledgementRequest? {
        _receivedMessages.value = _receivedMessages.value + msg
        return try {
            val senderHex = msg.senderId.joinToString("") { "%02x".format(it) }
            val messageType = msg.messageType.toInt() and 0xFF
            val timestamp = if (msg.timestamp > 0) msg.timestamp else System.currentTimeMillis()
            val protocolIdBytes = msg.protocolMessageId
            val protocolId = protocolIdBytes?.let(ProtocolMessageId::fromBytes)
            if (messageType == 0 && protocolId != null && protocolIdBytes != null) {
                messageRepo.storeReceivedDirectMessage(
                    senderId = senderHex,
                    protocolMessageId = protocolId,
                    timestamp = timestamp,
                    content = String(msg.content, Charsets.UTF_8)
                )
                ReceiptAcknowledgementRequest(msg.senderId.copyOf(), protocolIdBytes.copyOf())
            } else {
                val conversationId = if (messageType == 6) {
                    val channelId = ((msg.conversationId[0].toInt() and 0xFF) shl 24) or
                            ((msg.conversationId[1].toInt() and 0xFF) shl 16) or
                            ((msg.conversationId[2].toInt() and 0xFF) shl 8) or
                            (msg.conversationId[3].toInt() and 0xFF)
                    "channel_$channelId"
                } else {
                    senderHex
                }
                messageRepo.insertReceivedMessage(
                    messageId = UUID.randomUUID().toString(),
                    conversationId = conversationId,
                    senderId = senderHex,
                    timestamp = timestamp,
                    type = messageType,
                    content = String(msg.content, Charsets.UTF_8)
                )
                null
            }
        } catch (_: Exception) {
            // Never acknowledge a message that was not durably stored.
            null
        }
    }

    /** Called only after Rust signature, decryption, and binding checks pass. */
    fun onMessageAcknowledged(protocolMessageId: ByteArray, ackSender: ByteArray) {
        val protocolId = ProtocolMessageId.fromBytes(protocolMessageId) ?: return
        val senderHex = ackSender.joinToString("") { "%02x".format(it) }
        scope.launch {
            messageRepo.markRemoteReceived(protocolId, senderHex)
        }
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        val binder = service as? RezvanRadioService.LocalBinder
        activeService = binder?.getService()
        activeService?.setConnection(this)
        activeConnection = this
        activeServiceFlow.value = activeService
        isServiceConnected.value = activeService != null
    }

        override fun onServiceDisconnected(name: ComponentName?) {
        activeService?.setConnection(null)
        activeService = null
        activeServiceFlow.value = null
        isServiceConnected.value = false
    }
}

data class ReceiptAcknowledgementRequest(
    val originalSender: ByteArray,
    val protocolMessageId: ByteArray
)
