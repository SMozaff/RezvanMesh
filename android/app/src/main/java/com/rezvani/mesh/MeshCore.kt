package com.rezvani.mesh

import android.util.Log

object MeshCore {
    private const val TAG = "MeshCore"

    init {
        try {
            System.loadLibrary("rezvan_core")
            Log.i(TAG, "Native library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native library", e)
        }
    }

    @JvmStatic external fun nativeInit(seed: ByteArray, storagePath: String): Long
    @JvmStatic external fun nativeProcessIncoming(corePtr: Long, packet: ByteArray, rssi: Int, timestampUs: Long): ByteArray?
    @JvmStatic external fun nativeTick(corePtr: Long): ByteArray?
    @JvmStatic external fun nativeSendMessage(corePtr: Long, recipientId: ByteArray, plaintext: ByteArray, messageType: Int): ByteArray?
    @JvmStatic external fun nativeSendBroadcast(corePtr: Long, message: ByteArray): ByteArray?
    @JvmStatic external fun nativeGetKeyBundle(corePtr: Long): ByteArray?
    @JvmStatic external fun nativeGetNodeId(corePtr: Long): ByteArray?
    @JvmStatic external fun nativeGetRoutingSnapshot(corePtr: Long): ByteArray?
    @JvmStatic external fun nativeRegisterPeerKeys(corePtr: Long, peerId: ByteArray, bundle: ByteArray): Boolean
    @JvmStatic external fun nativeCreateChannelKey(corePtr: Long, channelId: Int): ByteArray?
    @JvmStatic external fun nativeSetChannelKey(corePtr: Long, channelId: Int, key: ByteArray): Boolean
    @JvmStatic external fun nativeSendChannelMessage(corePtr: Long, channelId: Int, message: ByteArray): ByteArray?
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