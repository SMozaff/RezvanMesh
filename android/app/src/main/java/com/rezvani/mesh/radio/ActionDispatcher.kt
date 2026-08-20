// android/app/src/main/java/com/rezvani/mesh/radio/ActionDispatcher.kt

package com.rezvani.mesh.radio

import com.rezvani.mesh.utils.DiagLogger

object ActionDispatcher {

    private val ALL_ZERO_TARGET = ByteArray(8)

    /**
     * Dispatches native action envelopes. For send actions the returned value
     * expresses only whether the local radio controller accepted the packet;
     * it must never be interpreted as remote delivery.
     */
    fun dispatch(action: ByteArray, radio: RadioController): SendResult {
        if (action.size < 4) return SendResult.Failed("Invalid mesh action frame")
        val actionCount = action[0].toInt() and 0xFF
        var offset = 1
        var transportResult: SendResult = SendResult.Failed("No transport action produced")
        for (i in 0 until actionCount) {
            if (offset + 3 > action.size) return SendResult.Failed("Truncated mesh action frame")
            val actionType = action[offset].toInt() and 0xFF
            val payloadLen = ((action[offset + 1].toInt() and 0xFF) shl 8) or (action[offset + 2].toInt() and 0xFF)
            offset += 3
            if (offset + payloadLen > action.size) return SendResult.Failed("Truncated mesh action payload")
            val payload = action.copyOfRange(offset, offset + payloadLen)
            offset += payloadLen
            when (actionType) {
                0x01 -> radio.startBleAdvertising(payload, 1000)
                0x03 -> transportResult = dispatchSendBlePacket(payload, radio)
                0x04 -> {
                    if (payload.size >= 8) {
                        val intervalMs = ((payload[0].toInt() and 0xFF) shl 24) or
                                ((payload[1].toInt() and 0xFF) shl 16) or
                                ((payload[2].toInt() and 0xFF) shl 8) or
                                (payload[3].toInt() and 0xFF)
                        val windowMs = ((payload[4].toInt() and 0xFF) shl 24) or
                                ((payload[5].toInt() and 0xFF) shl 16) or
                                ((payload[6].toInt() and 0xFF) shl 8) or
                                (payload[7].toInt() and 0xFF)
                        radio.startBleScan(intervalMs.toLong(), windowMs.toLong())
                    }
                }
                else -> DiagLogger.ble("Unknown action type: $actionType")
            }
        }
        return transportResult
    }

    /**
     * `payload` = [target NodeId : 8 bytes][packet data]. `target` is a mesh
     * NodeId, NOT a BLE MAC. An all-zero target represents a broadcast.
     */
    private fun dispatchSendBlePacket(payload: ByteArray, radio: RadioController): SendResult {
        if (payload.size <= 8) {
            DiagLogger.ble("SendBlePacket payload too short (${payload.size} bytes), dropping")
            return SendResult.Failed("Mesh packet was empty")
        }
        val target = payload.copyOfRange(0, 8)
        val data = payload.copyOfRange(8, payload.size)

        if (target.contentEquals(ALL_ZERO_TARGET)) {
            return radio.sendBroadcastPacket(data)
        }

        val targetHex = target.joinToString("") { "%02x".format(it) }
        return radio.sendToNodeId(targetHex, data)
    }
}
