package com.wall.guard.vpn

import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * Thin wrapper around the TUN file descriptor. Reading and writing whole IP
 * packets is all it does; what happens to the packets is decided elsewhere.
 */
class Tunnel(
    private val parcelFileDescriptor: ParcelFileDescriptor
) : AutoCloseable {

    private val inputStream = FileInputStream(parcelFileDescriptor.fileDescriptor)
    private val outputStream = FileOutputStream(parcelFileDescriptor.fileDescriptor)
    private val writeLock = Any()

    private var packetHandler: ((ByteArray, Int) -> Unit)? = null

    @Volatile
    private var active = true

    val fd: ParcelFileDescriptor get() = parcelFileDescriptor

    companion object {
        private const val TAG = "Tunnel"

        // A TUN read returns exactly one packet; leave generous headroom so a
        // packet is never truncated.
        private const val MAX_PACKET_SIZE = 32 * 1024
    }

    /** The handler receives a buffer that is reused, so it must copy what it keeps. */
    fun setPacketHandler(handler: (ByteArray, Int) -> Unit) {
        packetHandler = handler
    }

    suspend fun startReading() {
        withContext(Dispatchers.IO) {
            val buffer = ByteArray(MAX_PACKET_SIZE)

            while (active && currentCoroutineContext().isActive) {
                try {
                    val length = inputStream.read(buffer)
                    if (length < 0) break // descriptor closed
                    if (length == 0) continue

                    try {
                        packetHandler?.invoke(buffer, length)
                    } catch (e: Exception) {
                        // One bad packet must never take the whole firewall down.
                        Log.w(TAG, "Packet handler failed", e)
                    }
                } catch (e: IOException) {
                    if (active) {
                        Log.w(TAG, "TUN read error", e)
                    }
                    break
                }
            }
        }
    }

    /** Writes one complete IP packet towards the apps. Safe to call from any thread. */
    fun write(packet: ByteArray, length: Int) {
        if (!active) return
        try {
            synchronized(writeLock) {
                outputStream.write(packet, 0, length)
            }
        } catch (e: IOException) {
            if (active) Log.w(TAG, "TUN write error", e)
        }
    }

    fun release() {
        active = false
    }

    override fun close() {
        release()
        try {
            inputStream.close()
        } catch (_: Exception) {}

        try {
            outputStream.close()
        } catch (_: Exception) {}

        try {
            parcelFileDescriptor.close()
        } catch (_: Exception) {}
    }
}
