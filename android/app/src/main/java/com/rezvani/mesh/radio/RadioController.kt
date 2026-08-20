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

    /**
     * Queue a logical packet for every live GATT sender.
     *
     * [SendResult.Queued] confirms local queue acceptance only; it is not a
     * remote delivery acknowledgement.
     */
    fun sendBroadcastPacket(data: ByteArray): SendResult

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
     * Queue a packet addressed to a specific mesh NodeId. A [SendResult.Queued]
     * response means the local radio controller accepted the work. It does not
     * promise a successful GATT write or remote receipt.
     */
    fun sendToNodeId(nodeIdHex: String, data: ByteArray): SendResult
}
