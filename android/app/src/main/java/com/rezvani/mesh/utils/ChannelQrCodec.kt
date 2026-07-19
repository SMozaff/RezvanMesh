// android/app/src/main/java/com/rezvani/mesh/utils/ChannelQrCodec.kt

package com.rezvani.mesh.utils

/**
 * Encodes/decodes the QR payload used to share a channel's ID + shared
 * sender-key for the built-in scanner (see CreateChannelScreen /
 * ChannelsScreen -- reuses the same zxing-android-embedded ScanContract
 * already used for contact NodeId QR codes in ContactsScreen).
 *
 * Format: "rzvch1:<channelId decimal>:<64 hex chars, the 32-byte key>"
 *
 * Prefixed and versioned deliberately: a contact QR code is a bare 16-char
 * hex NodeId with no prefix, so scanning a contact code in the channel-join
 * flow (or vice versa) must fail with a clear "wrong kind of code" error
 * rather than something silently misinterpreting one as the other. The "1"
 * is a format version -- if the payload ever needs to change shape, bump it
 * and keep parsing old versions (or reject them with a clear "update the
 * app" message) rather than silently misreading a differently-shaped payload.
 */
object ChannelQrCodec {
    private const val PREFIX = "rzvch1:"
    private const val KEY_HEX_LEN = 64 // 32 bytes

    data class ChannelShare(val channelId: Int, val key: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ChannelShare) return false
            return channelId == other.channelId && key.contentEquals(other.key)
        }
        override fun hashCode(): Int = 31 * channelId + key.contentHashCode()
    }

    fun encode(channelId: Int, key: ByteArray): String {
        require(key.size == 32) { "channel key must be 32 bytes, got ${key.size}" }
        val keyHex = key.joinToString("") { "%02x".format(it) }
        return "$PREFIX$channelId:$keyHex"
    }

    /** Returns null if `raw` isn't a validly-formed channel share payload
     * (wrong prefix, wrong shape, non-hex key, etc) -- callers should show a
     * clear "not a valid channel QR code" error rather than guessing. */
    fun decode(raw: String): ChannelShare? {
        val trimmed = raw.trim()
        if (!trimmed.startsWith(PREFIX)) return null
        val rest = trimmed.removePrefix(PREFIX)
        val parts = rest.split(":")
        if (parts.size != 2) return null

        val channelId = parts[0].toIntOrNull() ?: return null
        val keyHex = parts[1]
        if (keyHex.length != KEY_HEX_LEN || !keyHex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
            return null
        }

        val key = ByteArray(32) { i ->
            keyHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return ChannelShare(channelId, key)
    }
}
