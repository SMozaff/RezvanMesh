// android/app/src/main/java/com/rezvani/mesh/radio/RadioController.kt

package com.rezvani.mesh.radio

interface RadioController {
    fun startBleScan(intervalMs: Long, windowMs: Long)
    fun stopBleScan()
    fun startBleAdvertising(adData: ByteArray, intervalMs: Int)
    fun stopBleAdvertising()
    fun connectToPeer(peerMacAddress: String): Boolean
    fun sendBlePacket(peerMacAddress: String, data: ByteArray): Boolean
    fun disconnectPeer(peerMacAddress: String)
    fun sendBroadcastPacket(data: ByteArray)
    fun isWifiDirectSupported(): Boolean
    fun startWifiDirectDiscovery()
    fun stopWifiDirectDiscovery()
    fun connectWifiDirect(peerMacAddress: String): Boolean
    fun sendWifiPacket(peerIpAddress: String, port: Int, data: ByteArray): Boolean
    fun disconnectWifiDirect(peerIpAddress: String)
    fun getCurrentRssi(peerMacAddress: String): Int
    fun setBleTxPower(dbm: Int)
    fun setWifiTxPower(dbm: Int)
    fun onDestroy()

    /** Resolve a mesh NodeId (16-char hex string) to a currently-known BLE
     * MAC address, if we've seen an advertisement from that peer. Returns
     * null if the peer hasn't been discovered yet. */
    fun getMacForNodeId(nodeIdHex: String): String?

    /**
     * Send a packet addressed to a specific peer, identified by their mesh
     * NodeId rather than a raw BLE MAC. Resolves NodeId -> MAC via
     * [getMacForNodeId] and attempts delivery over an existing GATT
     * connection; if no connection exists yet (or the write itself isn't
     * ready), queues the packet so it's flushed automatically once GATT
     * service discovery completes for that peer (see
     * [RadioControllerImpl.onServicesDiscovered]'s pending-packet flush).
     * Returns true if the packet was either sent immediately or queued for
     * later delivery (i.e. not silently dropped); false only if the peer's
     * MAC couldn't be resolved at all.
     */
    fun sendToNodeId(nodeIdHex: String, data: ByteArray): Boolean
}