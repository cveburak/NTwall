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

        private const val MAX_PACKET_SIZE = 32 * 1024
    }

    fun setPacketHandler(handler: (ByteArray, Int) -> Unit) {
        packetHandler = handler
    }

    suspend fun startReading() {
        withContext(Dispatchers.IO) {
            val buffer = ByteArray(MAX_PACKET_SIZE)

            while (active && currentCoroutineContext().isActive) {
                try {
                    val length = inputStream.read(buffer)
                    if (length < 0) break
                    if (length == 0) continue

                    try {
                        packetHandler?.invoke(buffer, length)
                    } catch (e: Exception) {
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
