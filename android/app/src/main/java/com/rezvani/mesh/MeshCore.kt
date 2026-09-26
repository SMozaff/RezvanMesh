package com.rezvani.mesh

import android.util.Log

object MeshCore {
    private const val TAG = "MeshCore"

    @Volatile
    private var nativeLoadFailure: UnsatisfiedLinkError? = null

    init {
        try {
            System.loadLibrary("rezvan_core")
            Log.i(TAG, "Native library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            nativeLoadFailure = e
            Log.e(TAG, "Failed to load native library", e)
        }
    }

    /** True only when the native library was loaded successfully. */
    @JvmStatic
    fun isNativeLibraryLoaded(): Boolean = nativeLoadFailure == null

    /**
     * Initializes the engine without allowing a broken/stale JNI library to
     * crash the service process. This also catches a library that loads but
     * does not export the current JNI method set.
     */
    @JvmStatic
    fun tryNativeInit(seed: ByteArray, storagePath: String): Long? {
        if (nativeLoadFailure != null) return null
        return try {
            nativeInit(seed, storagePath)
        } catch (e: UnsatisfiedLinkError) {
            nativeLoadFailure = e
            Log.e(TAG, "Native JNI initialization is unavailable", e)
            null
        }
    }

    @JvmStatic external fun nativeInit(seed: ByteArray, storagePath: String): Long
    /**
     * Encrypt and atomically persist the engine's session state (Olm account +
     * ratchet sessions, peer key bundles, channel keys, beacon epoch key, and
     * routing/replay history) into [storagePath].
     *
     * Called periodically rather than only at shutdown: Android gives no
     * reliable "process is about to be killed" signal, so a save that only ran
     * in onDestroy would lose everything exactly when it mattered most.
     *
     * @return true if the state was written, false if the engine handle is
     *   already dead, the arguments were invalid, or the write failed.
     */
    @JvmStatic external fun nativeSaveState(corePtr: Long, storagePath: String, seed: ByteArray): Boolean
    @JvmStatic external fun nativeProcessIncoming(corePtr: Long, packet: ByteArray, rssi: Int, timestampUs: Long): ByteArray?
    @JvmStatic external fun nativeTick(corePtr: Long): ByteArray?
    @JvmStatic external fun nativeSendMessage(corePtr: Long, recipientId: ByteArray, plaintext: ByteArray, messageType: Int): ByteArray?
    @JvmStatic external fun nativeSendMessageV1(corePtr: Long, recipientId: ByteArray, messageId: ByteArray, createdAtMs: Long, messageKind: Int, body: ByteArray): ByteArray?
    @JvmStatic external fun nativeBuildMessageReceivedAck(corePtr: Long, originalSender: ByteArray, messageId: ByteArray, createdAtMs: Long): ByteArray?
    @JvmStatic external fun nativeSendBroadcast(corePtr: Long, message: ByteArray): ByteArray?
    @JvmStatic external fun nativeGetKeyBundle(corePtr: Long): ByteArray?
    @JvmStatic external fun nativeGetNodeId(corePtr: Long): ByteArray?
    @JvmStatic external fun nativeGetRoutingSnapshot(corePtr: Long): ByteArray?
    @JvmStatic external fun nativeRegisterPeerKeys(corePtr: Long, peerId: ByteArray, bundle: ByteArray): Boolean
    @JvmStatic external fun nativeCreateChannelKey(corePtr: Long, channelId: Int): ByteArray?
    @JvmStatic external fun nativeSetChannelKey(corePtr: Long, channelId: Int, key: ByteArray): Boolean
    @JvmStatic external fun nativeSendChannelMessage(corePtr: Long, channelId: Int, message: ByteArray): ByteArray?

    /**
     * Revoke membership of a channel by dropping its sender key.
     *
     * Necessary because the database is not the only copy: the key is also held
     * in the engine and written into the encrypted engine-state file on every
     * save, so clearing the database row alone would leave the engine able to
     * decrypt that channel for the rest of the process's life and the next save
     * would write the key straight back out. See
     * `RezvanRadioService.removeChannelKey`.
     *
     * @return true if a key was held and has now been removed.
     */
    @JvmStatic external fun nativeRemoveChannelKey(corePtr: Long, channelId: Int): Boolean

    /**
     * Channel ids the engine currently holds a sender key for, as a packed
     * big-endian `u32` list (4 bytes per id, sorted ascending).
     *
     * The service reconciles this against the authoritative database on
     * start-up: an id present here but absent from the database is a channel the
     * user has left, and has to be revoked rather than silently retained.
     *
     * @return packed ids, or null if the engine handle is dead.
     */
    @JvmStatic external fun nativeGetChannelKeyIds(corePtr: Long): ByteArray?

    @JvmStatic external fun nativeGetPowerState(corePtr: Long): Int
    @JvmStatic external fun nativeSetPowerOverride(corePtr: Long, state: Int)
    @JvmStatic external fun nativeClearPowerOverride(corePtr: Long)
    @JvmStatic external fun nativeUpdateBattery(corePtr: Long, levelPercent: Int, isCharging: Boolean)
    @JvmStatic external fun nativeDestroy(corePtr: Long)
}

enum class PowerState(val value: Int) {
    EMERGENCY(0), ACTIVE(1), BALANCED(2), POWER_SAVER(3), MINIMAL(4), HIBERNATION(5), DEAD(6);
    companion object { fun fromInt(v: Int) = values().find { it.value == v } ?: BALANCED }
}

enum class MessageType(val value: Int) {
    TEXT(0), VOICE(1), FILE_METADATA(2), FILE_CHUNK(3)
}