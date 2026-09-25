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

    /**
     * Bounded window of recently decrypted messages, for diagnostics and any
     * future UI that wants it.
     *
     * Durable storage is [messageRepo] (Room); this list is *not* the store and
     * nothing reads it. It previously grew without limit, keeping the full
     * `content` ByteArray of every message the process ever decrypted -- on a
     * busy mesh that is an unbounded plaintext retention in memory for no
     * benefit, and `_receivedMessages.value = _receivedMessages.value + msg`
     * copied the whole list on every arrival, so it was quadratic in the
     * session's message count as well.
     *
     * Bounded to a small recent window with drop-oldest, which keeps it useful
     * for debugging "what just came in" without becoming a leak.
     */
    private val _receivedMessages = MutableStateFlow<List<DecryptedMessage>>(emptyList())
    val receivedMessages: StateFlow<List<DecryptedMessage>> = _receivedMessages

    companion object {
        /** How many recent messages [receivedMessages] retains. */
        private const val MAX_RECEIVED_MESSAGE_WINDOW = 64

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
            // The engine lives in the service process, so an unexpected
            // disconnect invalidates the handle outright. Leaving a stale
            // non-null pointer here means every ViewModel that checks
            // `meshCorePtr` sees a "live" engine and calls into a process that
            // no longer exists. The service clears this itself on a clean
            // onDestroy; doing it here as well covers the crash/kill case,
            // where onDestroy never runs.
            meshCorePtr.value = 0L
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
        // Drop-oldest via an ArrayDeque so this stays O(1) instead of copying
        // the whole list on every inbound message.
        _receivedMessages.value = buildList(_receivedMessages.value.size + 1) {
            addAll(_receivedMessages.value.takeLast(MAX_RECEIVED_MESSAGE_WINDOW - 1))
            add(msg)
        }
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
        ownNodeId.value = null
        // See the companion overload for why the engine handle must be dropped
        // here too: a process death never runs RezvanRadioService.onDestroy.
        meshCorePtr.value = 0L
    }
}

data class ReceiptAcknowledgementRequest(
    val originalSender: ByteArray,
    val protocolMessageId: ByteArray
)
