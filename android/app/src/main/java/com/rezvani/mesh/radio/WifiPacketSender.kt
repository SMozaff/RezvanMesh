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
    private val outputLock = ReentrantLock()

    /**
     * Sends data to the peer.
     * @param data The payload to send.
     * @return true if the packet was successfully written.
     */
    fun send(data: ByteArray): Boolean {
        return try {
            ensureConnected()
            val out = socket!!.getOutputStream()
            outputLock.lock()
            try {
                // Prepend 2-byte big-endian length prefix
                val lengthBytes = ByteBuffer.allocate(2).putShort(data.size.toShort()).array()
                out.write(lengthBytes)
                out.write(data)
                out.flush()
                true
            } finally {
                outputLock.unlock()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to send WiFi packet to $ip:$port", e)
            closeSocket()
            false
        }
    }

    private fun ensureConnected() {
        if (socket == null || socket!!.isClosed) {
            socket = Socket(ip, port)
            Log.d(TAG, "Connected to $ip:$port")
        }
    }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (_: IOException) {
            // Ignore
        } finally {
            socket = null
        }
    }

    fun close() {
        closeSocket()
    }

    companion object {
        private const val TAG = "WifiPacketSender"
    }
}
