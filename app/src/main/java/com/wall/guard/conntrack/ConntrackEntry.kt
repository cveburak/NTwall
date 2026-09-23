package com.wall.guard.conntrack

enum class Protocol(val value: Int, val label: String) {
    TCP(6, "TCP"),
    UDP(17, "UDP");

    companion object {
        fun from(value: Int): Protocol? = entries.firstOrNull { it.value == value }
    }
}

enum class ConnectionState(val label: String) {
    SYN_SENT("SYN"),
    ESTABLISHED("Kurulu"),
    CLOSE_WAIT("Kapanıyor"),
    FIN_WAIT("Kapanıyor"),
    CLOSED("Kapalı");

    companion object {
        fun fromTcpFlags(isSyn: Boolean, isFin: Boolean, isRst: Boolean): ConnectionState {
            if (isRst || isFin) return FIN_WAIT
            if (isSyn) return SYN_SENT
            return ESTABLISHED
        }
    }
}

enum class ConnectionDirection(val label: String) {
    OUTBOUND("Giden"),
    INBOUND("Gelen")
}

data class ConnectionInfo(
    val id: Long,
    val protocol: Protocol,
    val sourceIp: String,
    val sourcePort: Int,
    val destIp: String,
    val destPort: Int,
    val hostname: String?,
    val uid: Int,
    val packageName: String?,
    val appName: String?,
    val state: ConnectionState,
    val direction: ConnectionDirection,
    val bytesSent: Long,
    val bytesReceived: Long,
    val packetsSent: Long,
    val packetsReceived: Long,
    val blocked: Boolean,
    val firstSeen: Long,
    val lastSeen: Long
) {
    val totalBytes: Long get() = bytesSent + bytesReceived

    fun isActiveNow(now: Long = System.currentTimeMillis()): Boolean =
        lastSeen > now - ConnectionTracker.ACTIVE_WINDOW_MS
}

data class ConntrackEntry(
    val protocol: Protocol,
    val sourceIp: String,
    val sourcePort: Int,
    val destIp: String,
    val destPort: Int,
    val uid: Int,
    val state: ConnectionState
)