package com.rezvani.mesh.radio

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages logical-packet transmission for one connected GATT peer.
 *
 * Packets that exceed the negotiated ATT payload are fragmented before they
 * enter the write queue. The matching [BleReassembler] is used on the GATT
 * server receive path, so an oversized mesh packet is never passed through as
 * one invalid characteristic write.
 */
class BlePacketSender(
    private val gatt: BluetoothGatt,
    private val negotiatedMtu: Int = DEFAULT_MTU
) {
    private val queue = LinkedBlockingQueue<ByteArray>()
    private val isSending = AtomicBoolean(false)
    private val isClosed = AtomicBoolean(false)
    private val nextMessageId = AtomicInteger(0)

    @Volatile
    private var writeCharacteristic: BluetoothGattCharacteristic? = null

    private val lock = Object()

    companion object {
        private const val TAG = "BlePacketSender"
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 200L
        private const val DEFAULT_MTU = 23
        private const val ATT_OVERHEAD = 3
    }

    fun setCharacteristic(characteristic: BluetoothGattCharacteristic?) {
        synchronized(lock) {
            this.writeCharacteristic = characteristic
            if (characteristic != null && queue.isNotEmpty() && !isSending.get()) {
                processQueue()
            }
        }
    }

    /**
     * Queues a logical mesh packet. `true` means the local GATT queue accepted
     * it; it does not mean the peer received it.
     */
    fun send(data: ByteArray): Boolean {
        if (isClosed.get() || data.isEmpty()) {
            Log.w(TAG, if (isClosed.get()) "Sender closed, dropping packet" else "Empty packet, dropping")
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
        writes.forEach(queue::put)
        processQueue()
        return true
    }

    fun onWriteComplete(success: Boolean) {
        if (success) {
            synchronized(lock) {
                isSending.set(false)
                lock.notifyAll()
            }
            processQueue()
        } else {
            Log.w(TAG, "Write failed, will retry")
            synchronized(lock) {
                isSending.set(false)
            }
            Thread.sleep(RETRY_DELAY_MS)
            processQueue()
        }
    }

    fun close() {
        isClosed.set(true)
        queue.clear()
        synchronized(lock) {
            isSending.set(false)
            lock.notifyAll()
        }
    }

    private fun processQueue() {
        synchronized(lock) {
            if (isSending.get() || isClosed.get()) return

            val packet = queue.poll() ?: return
            val characteristic = writeCharacteristic
            if (characteristic == null) {
                queue.offer(packet)
                return
            }

            isSending.set(true)
            try {
                var retries = 0
                var writeSuccess = false
                while (retries < MAX_RETRIES && !writeSuccess && !isClosed.get()) {
                    characteristic.value = packet
                    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

                    val started = gatt.writeCharacteristic(characteristic)
                    if (!started) {
                        retries++
                        Log.w(TAG, "writeCharacteristic returned false, retry $retries/$MAX_RETRIES")
                        Thread.sleep(RETRY_DELAY_MS)
                        continue
                    }

                    lock.wait(3000)
                    if (isSending.get()) {
                        retries++
                        Log.w(TAG, "Write timeout, retry $retries/$MAX_RETRIES")
                    } else {
                        writeSuccess = true
                    }
                }

                if (!writeSuccess && !isClosed.get()) {
                    Log.e(TAG, "Failed to send packet after $MAX_RETRIES retries")
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                isSending.set(false)
            } catch (e: Exception) {
                Log.e(TAG, "Error writing characteristic", e)
                isSending.set(false)
            }
        }
    }
}
