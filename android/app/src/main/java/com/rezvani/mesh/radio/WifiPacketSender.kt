package com.rezvani.mesh.radio

import android.util.Log
import java.io.IOException
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.locks.ReentrantLock

/**
 * Manages a TCP connection to a WiFi Direct peer and sends packets
 * with a 2-byte big-endian length prefix.
 */
class WifiPacketSender(private val ip: String, private val port: Int) {
    private var socket: Socket? = null
    private val lock = ReentrantLock()

    companion object {
        private const val TAG = "WifiPacketSender"
        private const val MAX_PACKET_SIZE = 65535
    }

    /**
     * Sends data to the peer.
     * @param data The payload to send.
     * @return true if the packet was successfully written.
     */
    fun send(data: ByteArray): Boolean {
        if (data.size > MAX_PACKET_SIZE) {
            Log.e(TAG, "Packet size ${data.size} exceeds maximum $MAX_PACKET_SIZE bytes")
            return false
        }

        lock.lock()
        try {
            ensureConnectedLocked()
            val out = socket!!.getOutputStream()
            // Prepend 2-byte big-endian length prefix (unsigned short)
            val lengthBytes = ByteBuffer.allocate(2).putShort(data.size.toShort()).array()
            out.write(lengthBytes)
            out.write(data)
            out.flush()
            return true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to send WiFi packet to $ip:$port", e)
            closeSocketLocked()
            return false
        } finally {
            lock.unlock()
        }
    }

    private fun ensureConnectedLocked() {
        if (socket == null || socket!!.isClosed) {
            socket = Socket(ip, port)
            Log.d(TAG, "Connected to $ip:$port")
        }
    }

    private fun closeSocketLocked() {
        try {
            socket?.close()
        } catch (_: IOException) {
            // Ignore
        } finally {
            socket = null
        }
    }

    fun close() {
        lock.lock()
        try {
            closeSocketLocked()
        } finally {
            lock.unlock()
        }
    }
}
