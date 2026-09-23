package com.wall.guard.vpn

import android.os.Process
import java.io.File
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicReference

/**
 * Resolves the UID that owns a packet by parsing the kernel's socket tables
 * (/proc/net/tcp*, /proc/net/udp*).
 *
 * This is only a fallback for Android 8/9 (API 26-28). Since Android 10 apps
 * can no longer read these files, so there the service asks
 * ConnectivityManager#getConnectionOwnerUid instead and this table stays empty.
 *
 * The table is rebuilt periodically from /proc so entries represent the
 * current live sockets. Lookup is a cheap hash-map hit on the packet path.
 */
object KernelSocketTable {

    private data class SocketRow(
        val proto: Int,
        val localIp: String,
        val localPort: Int,
        val remoteIp: String,
        val remotePort: Int,
        val uid: Int
    )

    private val fullMapRef = AtomicReference<Map<String, Int>>(emptyMap())
    private val localPortMapRef = AtomicReference<Map<String, Int>>(emptyMap())

    private const val REFRESH_INTERVAL_MS = 1_200L

    /** Rebuild both lookup maps from the /proc net tables. Safe to call repeatedly. */
    fun refresh() {
        try {
            rebuild()
        } catch (_: Exception) {
            // /proc may be transiently unreadable; keep the previous table.
        }
    }

    private fun rebuild() {
        val full = HashMap<String, Int>(512)
        val byLocalPort = HashMap<String, Int>(512)

        tcpFile4(true)?.let { parseFile(it, 6, full, byLocalPort) }
        tcpFile4(false)?.let { parseFile(it, 6, full, byLocalPort) }
        udpFile4(true)?.let { parseFile(it, 17, full, byLocalPort) }
        udpFile4(false)?.let { parseFile(it, 17, full, byLocalPort) }

        fullMapRef.set(full)
        localPortMapRef.set(byLocalPort)
    }

    private fun parseFile(
        file: File,
        proto: Int,
        full: MutableMap<String, Int>,
        byLocalPort: MutableMap<String, Int>
    ) {
        if (!file.exists()) return
        var first = true
        file.forEachLine { line ->
            if (first) {
                first = false
                return@forEachLine
            }
            if (line.isBlank()) return@forEachLine
            val row = parseLine(line) ?: return@forEachLine
            if (row.proto != proto) return@forEachLine
            full[fourTupleKey(row)] = row.uid
            byLocalPort[localPortKey(row)] = row.uid
        }
    }

    private fun parseLine(line: String): SocketRow? {
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 8) return null
        val local = parseAddrPort(parts[1]) ?: return null
        val remote = parseAddrPort(parts[2]) ?: return null
        val uid = parts[7].toIntOrNull() ?: return null
        return SocketRow(
            proto = 0,
            localIp = local.first,
            localPort = local.second,
            remoteIp = remote.first,
            remotePort = remote.second,
            uid = uid
        )
    }

    private fun parseAddrPort(token: String): Pair<String, Int>? {
        val sep = token.indexOf(':')
        if (sep <= 0) return null
        val hexIp = token.substring(0, sep)
        val hexPort = token.substring(sep + 1)
        val port = hexPort.toIntOrNull(16) ?: return null
        if (hexIp.length == 8) {
            return ipv4(hexIp)?.let { it to port }
        }
        return ipv6(hexIp)?.let { it to port }
    }

    private fun ipv4(hex: String): String? {
        return try {
            val bytes = ByteArray(4)
            for (i in 0 until 4) {
                val idx = 6 - i * 2
                bytes[i] = hex.substring(idx, idx + 2).toInt(16).toByte()
            }
            InetAddress.getByAddress(bytes).hostAddress
        } catch (_: Exception) {
            null
        }
    }

    private fun ipv6(hex: String): String? {
        if (hex.length != 32) return null
        return try {
            val bytes = ByteArray(16)
            for (i in 0 until 4) {
                for (j in 0 until 4) {
                    val srcIdx = i * 8 + (3 - j) * 2
                    bytes[i * 4 + j] = hex.substring(srcIdx, srcIdx + 2).toInt(16).toByte()
                }
            }
            InetAddress.getByAddress(bytes).hostAddress
        } catch (_: Exception) {
            null
        }
    }

    private fun fourTupleKey(r: SocketRow): String =
        "${r.proto}|${r.localIp}|${r.localPort}|${r.remoteIp}|${r.remotePort}"

    private fun localPortKey(r: SocketRow): String =
        "${r.proto}|${r.localIp}|${r.localPort}"

    /**
     * Find the owning UID for a packet.
     *
     * @param sourceIp source address of the packet on the tunnel
     * @param destIp destination address of the packet on the tunnel
     */
    fun resolveUid(
        proto: Int,
        sourceIp: String?,
        sourcePort: Int,
        destIp: String?,
        destPort: Int
    ): Int {
        if (sourceIp == null || destIp == null || sourcePort <= 0 || destPort <= 0) {
            return Process.INVALID_UID
        }

        // Outbound: on-device end is (sourceIp, sourcePort).
        fullMapRef.get()[fourTupleKey(proto, sourceIp, sourcePort, destIp, destPort)]
            ?.let { return it }

        // Inbound/response: on-device end is (destIp, destPort).
        fullMapRef.get()[fourTupleKey(proto, destIp, destPort, sourceIp, sourcePort)]
            ?.let { return it }

        // Fallback for unconnected sockets (e.g. DNS): on-device local port.
        localPortMapRef.get()[localPortKey(proto, sourceIp, sourcePort)]
            ?.let { return it }

        return Process.INVALID_UID
    }

    private fun fourTupleKey(proto: Int, localIp: String, localPort: Int, remoteIp: String, remotePort: Int): String =
        "$proto|$localIp|$localPort|$remoteIp|$remotePort"

    private fun localPortKey(proto: Int, localIp: String, localPort: Int): String =
        "$proto|$localIp|$localPort"

    private fun tcpFile4(parse4: Boolean): File =
        File(if (parse4) "/proc/net/tcp" else "/proc/net/tcp6")

    private fun udpFile4(parse4: Boolean): File =
        File(if (parse4) "/proc/net/udp" else "/proc/net/udp6")
}