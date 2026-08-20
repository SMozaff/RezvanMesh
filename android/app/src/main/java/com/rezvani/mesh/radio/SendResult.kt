package com.rezvani.mesh.radio

/**
 * Truthful outcome of handing a user-visible payload to the local mesh stack.
 *
 * These results intentionally stop at local transport submission. The current
 * protocol does not expose a durable remote acknowledgement for every message,
 * so callers must not label [Queued] as delivered or received by another peer.
 */
sealed interface SendResult {
    /** The foreground service, mesh engine, or radio controller is unavailable. */
    data object NotReady : SendResult

    /** No discovered, reachable BLE peer is available for this payload. */
    data object NoReachablePeer : SendResult

    /**
     * The packet was accepted by the local radio queue for one or more known
     * peers. This proves only local submission, not an over-the-air write or
     * remote delivery.
     */
    data class Queued(val peerCount: Int) : SendResult

    /** Packet construction or local submission failed before it could be queued. */
    data class Failed(val reason: String) : SendResult
}

internal fun SendResult.failureMessage(): String = when (this) {
    SendResult.NotReady -> "Mesh unavailable — not sent"
    SendResult.NoReachablePeer -> "No reachable mesh peer"
    is SendResult.Failed -> reason
    is SendResult.Queued -> ""
}
