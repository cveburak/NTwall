package com.wall.guard.conntrack

import android.util.Log
import com.wall.guard.appmanager.AppManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton

data class PacketObservation(
    val protocol: Int,
    val sourceIp: String,
    val sourcePort: Int,
    val destIp: String,
    val destPort: Int,
    val uid: Int,
    val isSyn: Boolean,
    val isFin: Boolean,
    val isRst: Boolean,
    val outbound: Boolean,
    val blocked: Boolean,
    val bytes: Int
)

fun isPrivateIpAddress(ip: String?): Boolean {
    if (ip == null) return true
    if (ip == "127.0.0.1" || ip == "0.0.0.0" || ip == "::1" || ip == "::") return true
    if (ip.startsWith("10.")) return true
    if (ip.startsWith("192.168.")) return true
    if (ip.startsWith("172.")) {
        val second = ip.removePrefix("172.").substringBefore(".").toIntOrNull()
        if (second != null && second in 16..31) return true
    }
    if (ip.startsWith("100.")) {
        val second = ip.removePrefix("100.").substringBefore(".").toIntOrNull()
        if (second != null && second in 64..127) return true
    }
    val lower = ip.lowercase()
    if (lower.startsWith("fd") || lower.startsWith("fc") || lower.startsWith("fe8")) return true
    if (lower.startsWith("fe9") || lower.startsWith("fea") || lower.startsWith("feb")) return true
    return false
}

@Singleton
class ConnectionTracker @Inject constructor(
    private val appManager: AppManager
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var cleanupJob: Job? = null

    private val lock = Any()
    private val entries = LinkedHashMap<Long, ConnectionInfo>()
    private var nextId = 1L
    private val hostnameCache = HashMap<String, Pair<String, Long>>()
    private val uidLabelCache = HashMap<Int, Pair<String?, String?>>()

    private val _connections = MutableStateFlow<List<ConnectionInfo>>(emptyList())
    val connections: StateFlow<List<ConnectionInfo>> = _connections.asStateFlow()

    private val _activeCount = MutableStateFlow(0)
    val activeCount: StateFlow<Int> = _activeCount.asStateFlow()

    companion object {
        const val ACTIVE_WINDOW_MS = 30_000L
        const val HISTORY_LIMIT = 300
        private const val TAG = "ConnectionTracker"
        private const val HOSTNAME_TTL_MS = 60_000L
    }

    fun start() {
        cleanupJob = scope.launch {
            while (isActive) {
                delay(5_000)
                trim()
            }
        }
    }

    fun stop() {
        cleanupJob?.cancel()
        cleanupJob = null
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
        }
        refreshSnapshot()
    }

    fun record(observation: PacketObservation) {
        val proto = Protocol.from(observation.protocol) ?: return
        if (observation.sourcePort <= 0 || observation.destPort <= 0) return

        val local = if (observation.outbound) {
            observation.sourceIp to observation.sourcePort
        } else {
            observation.destIp to observation.destPort
        }
        val remote = if (observation.outbound) {
            observation.destIp to observation.destPort
        } else {
            observation.sourceIp to observation.sourcePort
        }
        val localIp = local.first
        val localPort = local.second
        val remoteIp = remote.first
        val remotePort = remote.second

        val key = "${proto.value}|${observation.uid}|$localIp:$localPort|$remoteIp:$remotePort"
        val now = System.currentTimeMillis()
        val newState = if (proto == Protocol.UDP) {
            ConnectionState.ESTABLISHED
        } else {
            ConnectionState.fromTcpFlags(observation.isSyn, observation.isFin, observation.isRst)
        }

        synchronized(lock) {
            val existingId = entries.values.firstOrNull {
                "${it.protocol.value}|${it.uid}|${it.sourceIp}:${it.sourcePort}|${it.destIp}:${it.destPort}" == key
            }?.id

            if (existingId == null && entries.size >= HISTORY_LIMIT) {
                val oldestId = entries.values.minByOrNull { it.lastSeen }?.id
                if (oldestId != null) entries.remove(oldestId)
            }

            if (existingId != null) {
                val e = entries[existingId]
                if (e != null) {
                    val state = when {
                        e.state == ConnectionState.CLOSED -> e.state
                        newState == ConnectionState.FIN_WAIT &&
                            e.state != ConnectionState.FIN_WAIT &&
                            e.state != ConnectionState.CLOSE_WAIT &&
                            e.state != ConnectionState.CLOSED -> ConnectionState.FIN_WAIT
                        else -> {
                            if (e.state == ConnectionState.SYN_SENT && newState == ConnectionState.ESTABLISHED) {
                                ConnectionState.ESTABLISHED
                            } else {
                                e.state
                            }
                        }
                    }
                    val out = observation.outbound
                    entries[existingId] = e.copy(
                        state = state,
                        bytesSent = e.bytesSent + (if (out) observation.bytes else 0),
                        bytesReceived = e.bytesReceived + (if (out) 0 else observation.bytes),
                        packetsSent = e.packetsSent + (if (out) 1 else 0),
                        packetsReceived = e.packetsReceived + (if (out) 0 else 1),
                        blocked = e.blocked || observation.blocked,
                        hostname = e.hostname ?: hostnameInCache(remoteIp),
                        lastSeen = now
                    )
                }
            } else {
                val e = ConnectionInfo(
                    id = nextId++,
                    protocol = proto,
                    sourceIp = localIp,
                    sourcePort = localPort,
                    destIp = remoteIp,
                    destPort = remotePort,
                    hostname = hostnameInCache(remoteIp),
                    uid = observation.uid,
                    packageName = null,
                    appName = null,
                    state = newState,
                    direction = if (observation.outbound) ConnectionDirection.OUTBOUND else ConnectionDirection.INBOUND,
                    bytesSent = if (observation.outbound) observation.bytes.toLong() else 0L,
                    bytesReceived = if (observation.outbound) 0L else observation.bytes.toLong(),
                    packetsSent = if (observation.outbound) 1 else 0,
                    packetsReceived = if (observation.outbound) 0 else 1,
                    blocked = observation.blocked,
                    firstSeen = now,
                    lastSeen = now
                )
                entries[e.id] = e
                resolveAppLabelAsync(e.id)
                resolveHostnameAsync(e.id, remoteIp)
            }
        }

        refreshSnapshot()
    }

    private fun hostnameInCache(ip: String): String? {
        if (isPrivateIpAddress(ip)) return null
        val cached = hostnameCache[ip] ?: return null
        val (host, at) = cached
        if (System.currentTimeMillis() - at > HOSTNAME_TTL_MS) return null
        return if (host != ip) host else null
    }

    private fun resolveAppLabelAsync(id: Long) {
        val entry = synchronized(lock) { entries[id] } ?: return
        val uid = entry.uid

        synchronized(lock) {
            val cached = uidLabelCache[uid]
            if (cached != null) {
                if (entry.packageName == null) {
                    entries[id] = entry.copy(packageName = cached.first, appName = cached.second)
                    refreshSnapshot()
                }
                return
            }
        }

        scope.launch {
            val info = try { appManager.getAppInfo(uid) } catch (_: Exception) { null }
            val pkg = info?.packageName
            val label = info?.appName?.toString()
            synchronized(lock) {
                uidLabelCache[uid] = pkg to label
                val e = entries[id] ?: return@launch
                if (e.packageName == null && pkg != null) {
                    entries[id] = e.copy(packageName = pkg, appName = label)
                    refreshSnapshot()
                }
            }
        }
    }

    private fun resolveHostnameAsync(id: Long, ip: String) {
        if (isPrivateIpAddress(ip)) return

        scope.launch(Dispatchers.IO) {
            val host = try {
                InetAddress.getByName(ip)?.hostName
            } catch (_: Exception) {
                null
            }
            val resolved = if (host == null || host == ip) null else host
            synchronized(lock) {
                hostnameCache[ip] = (resolved ?: ip) to System.currentTimeMillis()
                val e = entries[id] ?: return@launch
                if (e.hostname == null && resolved != null) {
                    entries[id] = e.copy(hostname = resolved)
                    refreshSnapshot()
                }
            }
        }
    }

    fun getConnection(id: Long): ConnectionInfo? = synchronized(lock) { entries[id] }

    private fun trim() {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val toRemove = entries.values
                .filter { !it.isActiveNow(now) && now - it.lastSeen > 10 * 60_000L }
                .take(50)
            toRemove.forEach { entries.remove(it.id) }
        }
        refreshSnapshot()
    }

    private fun refreshSnapshot() {
        val now = System.currentTimeMillis()
        var active = 0
        val list = synchronized(lock) {
            entries.values.toList()
        }.sortedWith(compareByDescending<ConnectionInfo> { it.lastSeen }.thenBy { it.id })
        active = list.count { it.isActiveNow(now) }
        _connections.value = list
        _activeCount.value = active
    }
}
