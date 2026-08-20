package com.rezvani.mesh.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rezvani.mesh.data.AppDatabase
import com.rezvani.mesh.data.DbKeyProvider
import com.rezvani.mesh.data.entities.ChannelEntity
import com.rezvani.mesh.data.repositories.ChannelRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ChannelsViewModel(application: Application) : AndroidViewModel(application) {

    private val dbPassphrase = DbKeyProvider.getOrCreateKey(application)
    private val channelRepo = ChannelRepository(application, dbPassphrase)

    private val _allChannels = MutableStateFlow<List<ChannelEntity>>(emptyList())
    val allChannels: StateFlow<List<ChannelEntity>> = _allChannels.asStateFlow()

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
            val channelId = channelRepo.createChannel(name, description, isPrivate, password)
            // Generate the real shared sender-key for this channel now, so
            // send/receive works immediately -- previously ChannelRepository
            // only wrote local metadata and no crypto material existed at all.
            val key = com.rezvani.mesh.MeshServiceConnection.activeService?.createChannelKey(channelId)
            if (key != null) {
                _lastCreatedChannelKey.value = channelId to key
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
            if (key.isEmpty()) {
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
                    channelRepo.joinChannel(channelId)
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
}
