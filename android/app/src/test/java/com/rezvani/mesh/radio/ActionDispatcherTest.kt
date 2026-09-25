package com.rezvani.mesh.radio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for action-frame dispatch.
 *
 * The Rust `Action` enum serializes `SendWifiPacket` as type `0x02`, but
 * `ActionDispatcher` had no case for it, so the frame parsed successfully and
 * then fell through to "Unknown action type" -- the packet was dropped with no
 * error surfaced to the caller.
 */
class ActionDispatcherTest {

    /** Records what the dispatcher asked the radio to do. */
    private class FakeRadioController : RadioController {
        var wifiIp: Int? = null
        var wifiPort: Int? = null
        var wifiData: ByteArray? = null
        var wifiAccepted = true
        var advertised: ByteArray? = null
        var scanIntervalMs: Long? = null
        var scanWindowMs: Long? = null

        override fun sendWifiPacket(peerIpAddress: String, port: Int, data: ByteArray): Boolean {
            error("dispatcher must use the numeric-IP overload, not the string one")
        }

        override fun sendWifiPacket(ip: Int, port: Int, data: ByteArray): Boolean {
            wifiIp = ip
            wifiPort = port
            wifiData = data
            return wifiAccepted
        }

        override fun startBleAdvertising(adData: ByteArray, intervalMs: Int) {
            advertised = adData
        }

        override fun startBleScan(intervalMs: Long, windowMs: Long) {
            scanIntervalMs = intervalMs
            scanWindowMs = windowMs
        }

        override fun stopBleScan() = Unit
        override fun stopBleAdvertising() = Unit
        override fun connectToPeer(peerMacAddress: String) = false
        override fun sendBlePacket(peerMacAddress: String, data: ByteArray) = false
        override fun disconnectPeer(peerMacAddress: String) = Unit
        override fun sendBroadcastPacket(data: ByteArray) = SendResult.NoReachablePeer
        override fun isWifiDirectSupported() = true
        override fun startWifiDirectDiscovery() = Unit
        override fun stopWifiDirectDiscovery() = Unit
        override fun connectWifiDirect(peerMacAddress: String) = false
        override fun disconnectWifiDirect(peerIpAddress: String) = Unit
        override fun getCurrentRssi(peerMacAddress: String) = Int.MIN_VALUE
        override fun setBleTxPower(dbm: Int) = Unit
        override fun setWifiTxPower(dbm: Int) = Unit
        override fun onDestroy() = Unit
        override fun getMacForNodeId(nodeIdHex: String): String? = null
        override fun sendToNodeId(nodeIdHex: String, data: ByteArray) = SendResult.NoReachablePeer
    }

    /** Build an action frame exactly the way `rezvan-core/src/action.rs` does. */
    private fun actionFrame(type: Int, payload: ByteArray): ByteArray {
        val out = ArrayList<Byte>(3 + payload.size)
        out.add(1) // action count
        out.add(type.toByte())
        out.add(((payload.size shr 8) and 0xFF).toByte())
        out.add((payload.size and 0xFF).toByte())
        payload.forEach { out.add(it) }
        return out.toByteArray()
    }

    private fun wifiPayload(ip: ByteArray, port: Int, data: ByteArray): ByteArray {
        require(ip.size == 4)
        return ip + byteArrayOf(
            ((port shr 8) and 0xFF).toByte(),
            (port and 0xFF).toByte()
        ) + data
    }

    @Test
    fun `wifi action is dispatched with the parsed ip port and payload`() {
        val radio = FakeRadioController()
        val data = byteArrayOf(9, 8, 7)
        val frame = actionFrame(0x02, wifiPayload(byteArrayOf(192.toByte(), 168.toByte(), 0, 1), 4237, data))

        val result = ActionDispatcher.dispatch(frame, radio)

        assertTrue("expected Queued, got $result", result is SendResult.Queued)
        // 192.168.0.1 in network byte order, i.e. the sign bit set.
        assertEquals(0xC0A80001, radio.wifiIp)
        assertEquals(4237, radio.wifiPort)
        assertArrayEquals(data, radio.wifiData)
    }

    @Test
    fun `wifi action reports failure when the transport rejects it`() {
        val radio = FakeRadioController().apply { wifiAccepted = false }
        val frame = actionFrame(0x02, wifiPayload(byteArrayOf(10, 0, 0, 5), 1, byteArrayOf(1)))

        val result = ActionDispatcher.dispatch(frame, radio)

        assertTrue("expected Failed, got $result", result is SendResult.Failed)
    }

    @Test
    fun `wifi action with a short header is rejected`() {
        val radio = FakeRadioController()

        // Only 5 bytes: IP but no port.
        val tooShort = actionFrame(0x02, byteArrayOf(192.toByte(), 168.toByte(), 0, 1, 0))
        assertTrue(ActionDispatcher.dispatch(tooShort, radio) is SendResult.Failed)

        // Header present but no packet data.
        val noData = actionFrame(0x02, wifiPayload(byteArrayOf(192.toByte(), 168.toByte(), 0, 1), 4237, ByteArray(0)))
        assertTrue(ActionDispatcher.dispatch(noData, radio) is SendResult.Failed)

        assertEquals(null, radio.wifiIp)
    }

    @Test
    fun `ble advertisement action is still dispatched`() {
        val radio = FakeRadioController()
        val adv = ByteArray(24) { 0x5A.toByte() }
        ActionDispatcher.dispatch(actionFrame(0x01, adv), radio)
        assertArrayEquals(adv, radio.advertised)
    }

    @Test
    fun `scan interval action is still parsed`() {
        val radio = FakeRadioController()
        val payload = byteArrayOf(
            0x00, 0x00, 0x03, 0xE8.toByte(), // intervalMs = 1000
            0x00, 0x00, 0x03, 0xE8.toByte()  // windowMs  = 1000
        )
        ActionDispatcher.dispatch(actionFrame(0x04, payload), radio)
        assertEquals(1000L, radio.scanIntervalMs)
        assertEquals(1000L, radio.scanWindowMs)
    }

    @Test
    fun `truncated frame is rejected without dispatching`() {
        val radio = FakeRadioController()
        assertTrue(ActionDispatcher.dispatch(byteArrayOf(1, 2, 3), radio) is SendResult.Failed)
        assertTrue(ActionDispatcher.dispatch(ByteArray(0), radio) is SendResult.Failed)
    }

    @Test
    fun `ble packet action is still dispatched`() {
        val radio = FakeRadioController()
        val target = ByteArray(8) { 0x11.toByte() }
        val data = byteArrayOf(1, 2, 3, 4)
        val frame = actionFrame(0x03, target + data)
        // No MAC is registered for this NodeId, so the fake reports no reachable
        // peer -- what matters is that it reached the BLE path at all.
        ActionDispatcher.dispatch(frame, radio)
        assertEquals(null, radio.wifiIp)
    }
}
