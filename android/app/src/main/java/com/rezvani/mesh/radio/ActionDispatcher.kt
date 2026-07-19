// android/app/src/main/java/com/rezvani/mesh/radio/ActionDispatcher.kt

package com.rezvani.mesh.radio

import com.rezvani.mesh.utils.DiagLogger

object ActionDispatcher {

    private val ALL_ZERO_TARGET = ByteArray(8)

    fun dispatch(action: ByteArray, radio: RadioController) {
        if (action.size < 4) return
        val actionCount = action[0].toInt() and 0xFF
        var offset = 1
        for (i in 0 until actionCount) {
            if (offset + 3 > action.size) break
            val actionType = action[offset].toInt() and 0xFF
            val payloadLen = ((action[offset + 1].toInt() and 0xFF) shl 8) or (action[offset + 2].toInt() and 0xFF)
            offset += 3
            if (offset + payloadLen > action.size) break
            val payload = action.copyOfRange(offset, offset + payloadLen)
            offset += payloadLen
            when (actionType) {
                0x01 -> radio.startBleAdvertising(payload, 1000)
                0x03 -> dispatchSendBlePacket(payload, radio)
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
    }

    /**
     * `payload` = [target NodeId : 8 bytes][packet data]. `target` is a mesh
     * NodeId, NOT a BLE MAC -- Rust doesn't know about MAC addresses. An
     * all-zero target is the broadcast sentinel (true broadcasts: emergency
     * alerts, KeyAnnouncement); any other value is resolved via
     * [RadioController.sendToNodeId], which looks up the peer's current MAC,
     * sends immediately if a GATT sender is ready, or queues the packet
     * until GATT service discovery completes for that peer.
     *
     * This is the fix for the bug where every "direct message" was
     * physically broadcast to every connected peer instead of being routed
     * only to its intended recipient (the dispatcher previously had no
     * branch other than "broadcast to everyone" for this action type).
     */
    private fun dispatchSendBlePacket(payload: ByteArray, radio: RadioController) {
        if (payload.size <= 8) {
            DiagLogger.ble("SendBlePacket payload too short (${payload.size} bytes), dropping")
            return
        }
        val target = payload.copyOfRange(0, 8)
        val data = payload.copyOfRange(8, payload.size)

        if (target.contentEquals(ALL_ZERO_TARGET)) {
            DiagLogger.ble("Broadcasting packet to all connected peers", "len" to data.size.toString())
            radio.sendBroadcastPacket(data)
            return
        }

        val targetHex = target.joinToString("") { "%02x".format(it) }
        val sent = radio.sendToNodeId(targetHex, data)
        if (!sent) {
            DiagLogger.ble("Could not send to $targetHex -- peer not yet discovered")
        }
    }
}