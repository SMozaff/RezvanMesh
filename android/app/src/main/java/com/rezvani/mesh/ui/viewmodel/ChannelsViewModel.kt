package com.rezvani.mesh.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rezvani.mesh.data.AppDatabase
import com.rezvani.mesh.data.DbKeyProvider
import com.rezvani.mesh.data.entities.ChannelEntity
import com.rezvani.mesh.data.repositories.ChannelRepository
import com.rezvani.mesh.utils.DiagLogger
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ChannelsViewModel(application: Application) : AndroidViewModel(application) {

    private val dbPassphrase = DbKeyProvider.getOrCreateKey(application)
    private val channelRepo = ChannelRepository(application, dbPassphrase)

    private val _allChannels = MutableStateFlow<List<ChannelEntity>>(emptyList())
    val allChannels: StateFlow<List<ChannelEntity>> = _allChannels.asStateFlow()

    /**
     * Why the last [createChannel] call did not fully succeed, or null if it
     * did.
     *
     * Two distinct failures surface here: a private channel with no password
     * (rejected outright), and a channel that was persisted while the mesh
     * service was offline, so it has metadata but no sender key and cannot
     * send or receive. The second is worth telling the user about -- otherwise
     * they create a channel, it appears in the list, and it is silently dead.
     */
    private val _createChannelError = MutableStateFlow<String?>(null)
    val createChannelError: StateFlow<String?> = _createChannelError.asStateFlow()

    fun clearCreateChannelError() {
        _createChannelError.value = null
    }

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init {
        loadChannels()
    }

    private fun loadChannels() {
        viewModelScope.launch {
            channelRepo.getAllChannels().collect { channels ->
                _allChannels.value = channels
            }
        }
    }

    fun refreshChannels() {
        viewModelScope.launch {
            _isRefreshing.value = true
            // NOTE: there is no real channel-discovery wire protocol yet --
            // channels are currently discovered only by manually sharing a
            // channel ID + key out-of-band (see ChannelDetailViewModel /
            // MeshServiceConnection.createChannelKey). A previous version of
            // this function called nativeProcessIncoming() (the RECEIVE path,
            // not a send path) with a hand-built packet using packet_type
            // 0x06, which was never an implemented type on the Rust side and
            // silently did nothing -- and 0x06 is now the real "channel
            // message" packet type (see engine.rs), so that dead code has
            // been removed rather than left to collide with it. This refresh
            // currently only re-queries local channel state; a real
            // broadcast-based discovery protocol would need its own packet
            // type and engine.rs handler, not a hand-rolled packet fed into
            // the receive path.
            kotlinx.coroutines.delay(500)
            _isRefreshing.value = false
        }
    }

    private val _lastCreatedChannelKey = MutableStateFlow<Pair<Int, ByteArray>?>(null)
    /** The (channelId, key) pair for the most recently created channel, so
     * the UI can show/export it for other members to join with. Cleared by
     * the UI after displaying (see ChannelsScreen). */
    val lastCreatedChannelKey: StateFlow<Pair<Int, ByteArray>?> = _lastCreatedChannelKey.asStateFlow()

    fun createChannel(name: String, description: String, isPrivate: Boolean, password: String?) {
        viewModelScope.launch {
            // `createChannel` requires a password for a private channel. The UI
            // disables the button in that case, but the repository is reachable
            // from anywhere, so handle it here rather than letting the
            // IllegalArgumentException tear down the coroutine and leave the
            // user with a button that silently does nothing.
            val channelId = runCatching {
                channelRepo.createChannel(name, description, isPrivate, password)
            }.getOrElse { error ->
                DiagLogger.err("CHANNEL", "Channel creation rejected: ${error.message}", error)
                _createChannelError.value = error.message
                    ?: "A private channel needs a password before it can be created."
                return@launch
            }
            _createChannelError.value = null

            // Generate the real shared sender-key for this channel now, so
            // send/receive works immediately -- previously ChannelRepository
            // only wrote local metadata and no crypto material existed at all.
            val key = com.rezvani.mesh.MeshServiceConnection.activeService?.createChannelKey(channelId)
            if (key != null) {
                // Persist the key alongside the membership flag. Without this the
                // channel only works until the service restarts, because the
                // native engine's copy is in-memory (and, before the engine
                // state file existed, was gone after any restart at all).
                channelRepo.recordChannelKey(channelId, key)
                _lastCreatedChannelKey.value = channelId to key
            } else {
                // The channel exists in metadata but has no key, so it cannot
                // send or receive. Say so instead of letting the user discover
                // it later as "my channel is silent".
                DiagLogger.err(
                    "CHANNEL",
                    "Channel $channelId created without a sender key; the mesh service is not running"
                )
                _createChannelError.value =
                    "The channel was saved, but the mesh service is offline so it has no encryption key. " +
                        "It will not be able to send or receive messages until the service is running and the channel is recreated."
            }
        }
    }

    fun clearLastCreatedChannelKey() {
        _lastCreatedChannelKey.value = null
    }

    /**
     * Joins a channel using a key shared out-of-band by an existing member
     * (e.g. scanned from their QR export of [lastCreatedChannelKey]).
     * Without this, a channel that isn't ours to create has no way to ever
     * become sendable/receivable -- sender_key.rs's shared key must be
     * agreed on by all members somehow, and this is that "somehow" for the
     * joining side.
     */
    fun joinChannelWithKey(
        channelId: Int,
        key: ByteArray,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            if (key.size != SENDER_KEY_BYTES) {
                // A wrong-length key would be rejected by the JNI layer anyway;
                // catching it here turns a silent "could not be accepted" into
                // an accurate complaint about the invite.
                onError("The channel invite did not contain a valid key.")
                return@launch
            }

            val service = com.rezvani.mesh.MeshServiceConnection.activeService
            if (service == null) {
                onError("Mesh service is unavailable. Reconnect and try the channel invite again.")
                return@launch
            }

            try {
                if (service.setChannelKey(channelId, key)) {
                    // Persist before reporting success. If this write throws,
                    // the engine holds a key we cannot recover after a restart,
                    // so the user is better off being told the join failed.
                    channelRepo.recordChannelKey(channelId, key)
                    onSuccess()
                } else {
                    onError("The channel invite could not be accepted. Verify the invite and try again.")
                }
            } catch (error: Exception) {
                onError(error.message ?: "The channel invite could not be accepted. Try again.")
            }
        }
    }

    fun joinPrivateChannel(channelId: Int, password: String, onSuccess: () -> Unit, onError: () -> Unit) {
        viewModelScope.launch {
            val joined = channelRepo.joinPrivateChannel(channelId, password)
            if (joined) onSuccess() else onError()
        }
    }

    fun joinPublicChannel(channelId: Int) {
        viewModelScope.launch {
            channelRepo.joinChannel(channelId)
        }
    }

    /**
     * Leave a channel and actually stop being able to read it.
     *
     * Order matters here. The database row is cleared first, then the engine's
     * copy is dropped. Doing only the first is what the codebase used to do, and
     * it did not revoke anything: the key stayed in the engine for the life of
     * the process *and* was written back into the encrypted engine-state file
     * on the next periodic save, so the user could read the channel forever
     * despite the UI saying they had left.
     *
     * Both halves are best-effort. If the engine is not running the database
     * still records the departure, and the next start-up reconciliation
     * reconciles the engine from it.
     */
    fun leaveChannel(channelId: Int, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            channelRepo.leaveChannel(channelId)
            val service = com.rezvani.mesh.MeshServiceConnection.activeService
            if (service == null) {
                DiagLogger.ble(
                    "Left channel $channelId in the database only; the mesh service is " +
                        "offline and will reconcile on next start"
                )
            } else if (!service.removeChannelKey(channelId)) {
                DiagLogger.ble(
                    "Left channel $channelId; the engine held no key for it"
                )
            } else {
                DiagLogger.ble("Left channel $channelId and revoked its sender key")
            }
            onDone()
        }
    }

    private companion object {
        const val SENDER_KEY_BYTES = 32
    }
}
