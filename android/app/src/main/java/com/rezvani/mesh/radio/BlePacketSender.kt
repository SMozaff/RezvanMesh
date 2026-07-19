package com.rezvani.mesh.radio

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages a queue of outgoing BLE packets for a single connected GATT device.
 * Handles characteristic write with response and automatic retries.
 *
 * Each instance is associated with a specific peer's GATT connection.
 */
class BlePacketSender(private val gatt: BluetoothGatt) {

    private val queue = LinkedBlockingQueue<ByteArray>()
    private val isSending = AtomicBoolean(false)
    private val isClosed = AtomicBoolean(false)

    @Volatile
    private var writeCharacteristic: BluetoothGattCharacteristic? = null

    private val lock = Object()

    companion object {
        private const val TAG = "BlePacketSender"
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 200L
    }

    /**
     * Sets the characteristic to use for writing data.
     * Called when GATT services are discovered.
     */
    fun setCharacteristic(characteristic: BluetoothGattCharacteristic?) {
        synchronized(lock) {
            this.writeCharacteristic = characteristic
            if (characteristic != null && queue.isNotEmpty() && !isSending.get()) {
                processQueue()
            }
        }
    }

    /**
     * Enqueues a packet for transmission.
     *
     * @param data Raw packet data to send.
     */
    fun send(data: ByteArray) {
        if (isClosed.get()) {
            Log.w(TAG, "Sender closed, dropping packet")
            return
        }

        queue.put(data)
        processQueue()
    }

    /**
     * Callback when a characteristic write completes.
     *
     * @param success true if write succeeded, false otherwise.
     */
    fun onWriteComplete(success: Boolean) {
        if (success) {
            synchronized(lock) {
                isSending.set(false)
                // Notify waiting threads (if any retry logic)
                lock.notifyAll()
            }
            processQueue()
        } else {
            Log.w(TAG, "Write failed, will retry")
            // Retry will be handled by the queue processing
            synchronized(lock) {
                isSending.set(false)
            }
            // Delay before retry
            Thread.sleep(RETRY_DELAY_MS)
            processQueue()
        }
    }

    /**
     * Closes this sender and clears the queue.
     */
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
            if (isSending.get() || isClosed.get()) {
                return
            }

            val packet = queue.poll() ?: return

            val characteristic = writeCharacteristic
            if (characteristic == null) {
                // Characteristic not ready yet; put back and wait
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

                    val success = gatt.writeCharacteristic(characteristic)
                    if (!success) {
                        retries++
                        Log.w(TAG, "writeCharacteristic returned false, retry $retries/$MAX_RETRIES")
                        Thread.sleep(RETRY_DELAY_MS)
                        continue
                    }

                    // Wait for onWriteComplete callback
                    lock.wait(3000)

                    // If isSending is still true, write may have timed out
                    if (isSending.get()) {
                        retries++
                        Log.w(TAG, "Write timeout, retry $retries/$MAX_RETRIES")
                    } else {
                        writeSuccess = true
                    }
                }

                if (!writeSuccess && !isClosed.get()) {
                    Log.e(TAG, "Failed to send packet after $MAX_RETRIES retries")
                    // Packet is dropped; could optionally requeue for later
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
