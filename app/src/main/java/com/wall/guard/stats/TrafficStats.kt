package com.wall.guard.stats

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class TrafficStats(
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
    val packetsSent: Long = 0,
    val packetsReceived: Long = 0,
    val bytesBlocked: Long = 0,
    val packetsBlocked: Long = 0,
    val startTimeMillis: Long = System.currentTimeMillis()
) {
    val totalForwarded: Long get() = bytesSent + bytesReceived

    fun recordSent(size: Int): TrafficStats = copy(
        bytesSent = bytesSent + size,
        packetsSent = packetsSent + 1
    )

    fun recordReceived(size: Int): TrafficStats = copy(
        bytesReceived = bytesReceived + size,
        packetsReceived = packetsReceived + 1
    )

    fun recordBlocked(size: Int): TrafficStats = copy(
        bytesBlocked = bytesBlocked + size,
        packetsBlocked = packetsBlocked + 1
    )
}

data class PerAppTraffic(
    val uid: Int,
    val packageName: String,
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
    val packetsSent: Long = 0,
    val packetsReceived: Long = 0,
    val bytesBlocked: Long = 0,
    val packetsBlocked: Long = 0
) {
    val totalBytes: Long get() = bytesSent + bytesReceived
}

@Singleton
class TrafficMonitor @Inject constructor() {

    private val _total = MutableStateFlow(TrafficStats())
    val total: StateFlow<TrafficStats> = _total.asStateFlow()

    private val _perApp = MutableStateFlow<Map<Int, PerAppTraffic>>(emptyMap())
    val perApp: StateFlow<Map<Int, PerAppTraffic>> = _perApp.asStateFlow()

    @Synchronized
    fun reset() {
        _total.value = TrafficStats()
        _perApp.value = emptyMap()
    }

    // Forwarded (allowed) traffic. Direction is computed by the caller from
    // the packet's source vs destination addresses.
    @Synchronized
    fun recordForwarded(uid: Int, bytes: Int, outbound: Boolean) {
        if (bytes <= 0) return

        _total.value = if (outbound) _total.value.recordSent(bytes) else _total.value.recordReceived(bytes)

        val current = _perApp.value
        val entry = current[uid] ?: PerAppTraffic(uid = uid, packageName = "")
        val updated = if (outbound) {
            entry.copy(bytesSent = entry.bytesSent + bytes, packetsSent = entry.packetsSent + 1)
        } else {
            entry.copy(bytesReceived = entry.bytesReceived + bytes, packetsReceived = entry.packetsReceived + 1)
        }
        _perApp.value = current + (uid to updated)
    }

    // Dropped (blocked) traffic — connection attempts stopped by the firewall.
    @Synchronized
    fun recordDropped(uid: Int, bytes: Int) {
        if (bytes <= 0) return

        _total.value = _total.value.recordBlocked(bytes)

        val current = _perApp.value
        val entry = current[uid] ?: PerAppTraffic(uid = uid, packageName = "")
        _perApp.value = current + (uid to entry.copy(
            bytesBlocked = entry.bytesBlocked + bytes,
            packetsBlocked = entry.packetsBlocked + 1
        ))
    }
}

fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}