package com.rezvani.mesh.radio

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages logical-packet transmission for one connected GATT peer.
 *
 * Packets that exceed the negotiated ATT payload are fragmented before they
 * enter the write queue. The matching [BleReassembler] is used on the GATT
 * server receive path, so an oversized mesh packet is never passed through as
 * one invalid characteristic write.
 *
 * Exactly one write is in flight at a time. The GATT stack invokes
 * [onWriteComplete] on its own callback thread, so the outcome of the in-flight
 * write is published through [lastWriteSucceeded] and the worker is woken via
 * [Object.notifyAll]. The worker -- not the callback -- decides what happens
 * next, because only the worker owns the retry count and the queue head.
 *
 * That separation is the fix for a real bug: the previous version had the
 * callback both clear the in-flight flag *and* re-enter the send loop, so a
 * `false` result (write failed) was indistinguishable from a `true` one, the
 * packet was treated as delivered, and it was silently dropped.
 *
 * All mutable state that decides "who is driving the queue" is guarded by
 * [lock] and nothing else. Using an atomic for it invites the classic bug where
 * a worker's cleanup path clears a flag a *different* thread has just claimed.
 */
class BlePacketSender(
    private val gatt: BluetoothGatt,
    private val negotiatedMtu: Int = DEFAULT_MTU
) {
    private val queue = ArrayBlockingQueue<ByteArray>(MAX_QUEUE_DEPTH)
    private val nextMessageId = AtomicInteger(0)
    private val closed = AtomicBoolean(false)

    @Volatile
    private var writeCharacteristic: BluetoothGattCharacteristic? = null

    private val lock = Object()

    /** True while a worker owns the queue and is driving writes. Guarded by [lock]. */
    private var draining = false

    /**
     * Outcome of the write currently in flight, written by [onWriteComplete]
     * under [lock] immediately before it notifies. `null` means "no result
     * yet", which is what lets a spurious wakeup be distinguished from a real
     * completion. Guarded by [lock].
     */
    private var lastWriteSucceeded: Boolean? = null

    companion object {
        private const val TAG = "BlePacketSender"
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 200L
        private const val DEFAULT_MTU = 23
        private const val ATT_OVERHEAD = 3
        private const val WRITE_TIMEOUT_MS = 3000L

        /**
         * Bounded so a peer that has stopped draining its GATT buffer cannot
         * make us buffer without limit. Dropping the newest packet (and saying
         * so) beats an OOM kill of the radio service.
         */
        private const val MAX_QUEUE_DEPTH = 128
    }

    fun setCharacteristic(characteristic: BluetoothGattCharacteristic?) {
        synchronized(lock) {
            writeCharacteristic = characteristic
            // Wake a worker that is parked because the characteristic was
            // missing, so it can retry now that one is available.
            if (characteristic != null) lock.notifyAll()
        }
    }

    /**
     * Queues a logical mesh packet. `true` means the local GATT queue accepted
     * it; it does not mean the peer received it.
     *
     * Fragmentation is all-or-nothing: if any fragment cannot be enqueued, the
     * fragments already queued for this packet are withdrawn, so the peer's
     * reassembler never sees a truncated fragment run.
     */
    fun send(data: ByteArray): Boolean {
        if (closed.get() || data.isEmpty()) {
            Log.w(TAG, if (closed.get()) "Sender closed, dropping packet" else "Empty packet, dropping")
            return false
        }

        val maxWholeWrite = (negotiatedMtu - ATT_OVERHEAD).coerceAtLeast(20)
        val writes = if (data.size <= maxWholeWrite) {
            listOf(data)
        } else {
            BleFragmenter.fragment(
                packet = data,
                mtu = negotiatedMtu,
                msgId = nextMessageId.getAndUpdate { (it + 1) and 0xFFFF }
            )
        }

        var enqueued = 0
        for (write in writes) {
            if (queue.offer(write)) {
                enqueued++
            } else {
                for (i in 0 until enqueued) queue.remove(writes[i])
                Log.w(TAG, "Queue full (${queue.size}/$MAX_QUEUE_DEPTH), dropping packet")
                return false
            }
        }

        drainQueue()
        return true
    }

    /**
     * Called by the GATT callback when a characteristic write completes.
     *
     * Only records the outcome and wakes the worker. It must never re-enter the
     * send loop itself: the worker is mid-retry-loop on the queue head and
     * would race with it.
     */
    fun onWriteComplete(success: Boolean) {
        synchronized(lock) {
            lastWriteSucceeded = success
            lock.notifyAll()
        }
    }

    fun close() {
        closed.set(true)
        queue.clear()
        synchronized(lock) {
            lock.notifyAll()
        }
    }

    /**
     * Claim ownership of the queue and write packets until it drains.
     *
     * Returns immediately if another worker already owns it or we are closed.
     * Ownership is released -- under [lock] -- on every exit path, including
     * the exceptional ones, so a throw here cannot wedge the queue forever.
     */
    private fun drainQueue() {
        synchronized(lock) {
            if (draining || closed.get()) return
            draining = true
        }

        try {
            while (true) {
                val packet = synchronized(lock) {
                    if (closed.get()) null
                    else if (writeCharacteristic == null) {
                        // Not ready to transmit. Leave the packet queued and
                        // stop; setCharacteristic will wake us.
                        null
                    } else {
                        queue.poll()
                    }
                } ?: break

                if (!transmit(packet)) {
                    Log.e(TAG, "Giving up on a packet after $MAX_RETRIES attempts")
                }
            }
        } finally {
            synchronized(lock) {
                draining = false
            }
        }
    }

    /**
     * Write one packet, retrying up to [MAX_RETRIES] times.
     *
     * `false` means the packet was definitively not handed to the GATT layer,
     * so the caller must not treat it as delivered.
     */
    private fun transmit(packet: ByteArray): Boolean {
        for (attempt in 1..MAX_RETRIES) {
            if (closed.get()) return false

            val started = synchronized(lock) {
                val characteristic = writeCharacteristic ?: return false
                lastWriteSucceeded = null
                characteristic.value = packet
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                try {
                    gatt.writeCharacteristic(characteristic)
                } catch (e: Exception) {
                    Log.w(TAG, "writeCharacteristic threw on attempt $attempt: ${e.message}")
                    false
                }
            }

            if (!started) {
                Log.w(TAG, "writeCharacteristic refused/failed, attempt $attempt/$MAX_RETRIES")
                if (!sleepBriefly()) return false
                continue
            }

            val outcome = synchronized(lock) {
                // Wait in a loop: a spurious wakeup (setCharacteristic) must
                // not be mistaken for a completed write.
                val deadline = System.currentTimeMillis() + WRITE_TIMEOUT_MS
                while (lastWriteSucceeded == null && !closed.get()) {
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0) break
                    try {
                        lock.wait(remaining)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return false
                    }
                }
                lastWriteSucceeded
            }

            when (outcome) {
                true -> return true
                false -> {
                    Log.w(TAG, "Write reported failure, attempt $attempt/$MAX_RETRIES")
                    if (!sleepBriefly()) return false
                }
                // The callback never arrived: the stack is wedged or the link
                // dropped mid-write. Retrying is right, but a late callback for
                // the previous attempt can still land, so the next attempt
                // resets `lastWriteSucceeded` first.
                null -> {
                    Log.w(TAG, "Write timed out, attempt $attempt/$MAX_RETRIES")
                    if (!sleepBriefly()) return false
                }
            }
        }
        return false
    }

    /** @return false if we were interrupted or closed while backing off. */
    private fun sleepBriefly(): Boolean {
        if (closed.get()) return false
        return try {
            Thread.sleep(RETRY_DELAY_MS)
            !closed.get()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }
}
