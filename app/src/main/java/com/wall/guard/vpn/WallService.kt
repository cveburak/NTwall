package com.wall.guard.vpn

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import com.wall.guard.MainActivity
import com.wall.guard.R
import com.wall.guard.WallApp
import com.wall.guard.conntrack.ConnectionTracker
import com.wall.guard.conntrack.PacketObservation
import com.wall.guard.data.repository.RuleRepository
import com.wall.guard.data.repository.SettingsRepository
import com.wall.guard.filter.NetworkStateMonitor
import com.wall.guard.filter.RuleMatcher
import com.wall.guard.stats.TrafficMonitor
import com.wall.guard.stats.TrafficStats
import com.wall.guard.stats.formatBytes
import com.wall.guard.vpn.forward.FlowDecision
import com.wall.guard.vpn.forward.FlowInfo
import com.wall.guard.vpn.forward.InboundListener
import com.wall.guard.vpn.forward.PacketForwarder
import com.wall.guard.vpn.forward.SocketProtector
import com.wall.guard.vpn.forward.TunWriter
import com.wall.guard.vpn.forward.Verdict
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import javax.inject.Inject

@AndroidEntryPoint
class WallService : VpnService() {

    @Inject lateinit var ruleRepository: RuleRepository
    @Inject lateinit var ruleMatcher: RuleMatcher
    @Inject lateinit var networkStateMonitor: NetworkStateMonitor
    @Inject lateinit var trafficMonitor: TrafficMonitor
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var connectionTracker: ConnectionTracker

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @Volatile
    private var tunnel: Tunnel? = null

    // Relays the traffic of apps that have port/IP rules (see PacketForwarder).
    @Volatile
    private var forwarder: PacketForwarder? = null

    @Volatile
    private var lastTableRefresh = 0L

    private var packetLoopJob: Job? = null
    private var notificationJob: Job? = null
    private var rulesJob: Job? = null
    private var networkJob: Job? = null
    private var socketTableJob: Job? = null
    private val uidCache = UidCache()
    private var lastBlockedSet: Set<String> = emptySet()

    private val _state = MutableStateFlow<VpnState>(VpnState.Idle)
    val state: Flow<VpnState> = _state.asStateFlow()

    private var currentNetworkType: NetworkType = NetworkType.Unknown
    private var blockedPackages: List<String> = emptyList()

    companion object {
        private const val TAG = "WallService"
        const val ACTION_START = "com.wall.guard.action.START"
        const val ACTION_STOP = "com.wall.guard.action.STOP"
        const val VPN_IPV4 = "10.1.10.1"
        const val VPN_IPV6 = "fd00:1::1"
        const val VPN_MTU = 1500

        private val _globalState = MutableStateFlow<VpnState>(VpnState.Idle)
        val globalState: kotlinx.coroutines.flow.StateFlow<VpnState> = _globalState.asStateFlow()

        fun enqueueStart(context: Context) {
            val intent = Intent(context, WallService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun enqueueStop(context: Context) {
            val intent = Intent(context, WallService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        _state.value = VpnState.Idle
        _globalState.value = VpnState.Idle
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startVpn()
            ACTION_STOP -> stopVpn()
            else -> {
                if (_state.value is VpnState.Idle) {
                    startVpn()
                }
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        Log.d(TAG, "VPN revoked by system")
        serviceScope.launch { settingsRepository.setVpnEnabled(false) }
        shutdown()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun startVpn() {
        if (_state.value is VpnState.Running || _state.value is VpnState.Starting) {
            Log.d(TAG, "VPN already starting or running")
            return
        }

        _state.value = VpnState.Starting
        _globalState.value = VpnState.Starting

        serviceScope.launch(Dispatchers.IO) {
            try {
                val networkType = networkStateMonitor.getCurrentNetworkType()
                currentNetworkType = networkType

                val rules = ruleRepository.getAll()
                ruleMatcher.updateRules(rules)

                blockedPackages = ruleMatcher.getBlockedPackages()

                val newTunnel = openTunnel()
                if (newTunnel == null) {
                    val errState = VpnState.Error("VPN establishment failed")
                    _state.value = errState
                    _globalState.value = errState
                    return@launch
                }

                forwarder?.close()
                forwarder = createForwarder()
                val notification = buildNotification(networkType, blockedPackages.size)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(NOTIFICATION_ID_VPN, notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                } else {
                    startForeground(NOTIFICATION_ID_VPN, notification)
                }

                activateTunnel(newTunnel)

                // /proc/net is only readable on Android 8/9; newer versions use
                // ConnectivityManager#getConnectionOwnerUid instead.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    socketTableJob = serviceScope.launch(Dispatchers.IO) {
                        KernelSocketTable.refresh()
                        while (isActive) {
                            delay(1_000)
                            KernelSocketTable.refresh()
                        }
                    }
                }

                notificationJob = serviceScope.launch {
                    while (isActive) {
                        delay(3_000)
                        updateNotification(networkType, blockedPackages.size, trafficMonitor.total.value)
                    }
                }

                val runningState = VpnState.Running(
                    networkType = networkType,
                    blockedCount = blockedPackages.size
                )
                _state.value = runningState
                _globalState.value = runningState
                Log.d(TAG, "VPN established: ${blockedPackages.size} apps blocked on $networkType")

                observeNetworkChanges()
                observeRules()
                trafficMonitor.reset()
                connectionTracker.clear()
                connectionTracker.start()
                serviceScope.launch { settingsRepository.setVpnEnabled(true) }

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start VPN", e)
                val errState = VpnState.Error(
                    message = e.message ?: "Unknown error",
                    cause = e
                )
                _state.value = errState
                _globalState.value = errState
                shutdownTunnel()
            }
        }
    }

    /**
     * Every app is routed through the tunnel so that all connections can be
     * observed. Allowed traffic is relayed by [PacketForwarder]; blocked
     * traffic is dropped. Our own package is excluded so the app can never
     * loop into its own tunnel.
     */
    private fun configureBuilder(): Builder {
        return Builder().apply {
            setSession("NTWall")
            setMtu(VPN_MTU)
            addAddress(VPN_IPV4, 32)
            addAddress(VPN_IPV6, 128)
            setBlocking(true)
            addRoute("0.0.0.0", 0)
            addRoute("::", 0)
            try {
                addDisallowedApplication(packageName)
            } catch (_: PackageManager.NameNotFoundException) {
            }
        }
    }

    private fun openTunnel(): Tunnel? {
        val fd = configureBuilder().establish() ?: return null
        return Tunnel(fd)
    }

    /** Makes [newTunnel] the live tunnel and retires the previous one, if any. */
    private fun activateTunnel(newTunnel: Tunnel) {
        val oldTunnel = tunnel
        val oldJob = packetLoopJob
        tunnel = newTunnel
        newTunnel.setPacketHandler { buffer, length -> handlePacket(buffer, length) }
        packetLoopJob = serviceScope.launch(Dispatchers.IO) {
            newTunnel.startReading()
        }
        oldJob?.cancel()
        try {
            oldTunnel?.close()
        } catch (_: Exception) {
        }
    }

    private fun createForwarder(): PacketForwarder = PacketForwarder(
        tun = TunWriter { packet, length -> tunnel?.write(packet, length) },
        protector = object : SocketProtector {
            override fun protect(socket: java.net.Socket): Boolean = this@WallService.protect(socket)
            override fun protect(socket: java.net.DatagramSocket): Boolean = this@WallService.protect(socket)
        },
        listener = InboundListener { flow, uid, bytes, flags -> onInbound(flow, uid, bytes, flags) }
    )

    private fun stopVpn() {
        _state.value = VpnState.Stopping
        _globalState.value = VpnState.Stopping
        serviceScope.launch { settingsRepository.setVpnEnabled(false) }
        shutdown()
        val idle = VpnState.Idle
        _state.value = idle
        _globalState.value = idle
    }

    private fun shutdown() {
        packetLoopJob?.cancel()
        packetLoopJob = null
        notificationJob?.cancel()
        notificationJob = null
        rulesJob?.cancel()
        rulesJob = null
        networkJob?.cancel()
        networkJob = null
        socketTableJob?.cancel()
        socketTableJob = null
        connectionTracker.stop()
        shutdownTunnel()
        forwarder?.close()
        forwarder = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun shutdownTunnel() {
        try {
            tunnel?.close()
        } catch (_: Exception) {}
        tunnel = null
    }

    private fun handlePacket(data: ByteArray, length: Int) {
        val packet = ParsedPacket.parse(ByteBuffer.wrap(data, 0, length)) ?: return
        val bytes = packet.ipHeader.totalLength
        val relay = forwarder

        var uid = Process.INVALID_UID
        var blocked = false
        var record = true

        var verdict = Verdict.UNSUPPORTED
        if (relay != null && packet.ipHeader.version == 4 && (packet.isTCP || packet.isUDP)) {
            val result = relay.forward(data, length) { flow -> decide(flow) }
            verdict = result.verdict
            uid = result.uid
        }

        when (verdict) {
            Verdict.FORWARDED -> Unit
            Verdict.DENIED -> blocked = true
            Verdict.IGNORED -> record = false
            Verdict.UNSUPPORTED -> {
                // IPv6, ICMP and other traffic cannot be relayed, so it is
                // dropped. Only count it when a rule actually caused the drop.
                uid = resolvePacketUid(packet) ?: Process.INVALID_UID
                val result = ruleMatcher.evaluate(
                    uid = uid,
                    destPort = packet.destPort,
                    destIp = packet.ipHeader.destIp.hostAddress
                )
                if (result.action != RuleMatcher.FirewallAction.ALLOW) blocked = true else record = false
            }
        }

        if (!record) return

        // Feed the connection tracker for the analysis dashboard.
        try {
            connectionTracker.record(
                PacketObservation(
                    protocol = packet.protocol,
                    sourceIp = packet.sourceIpString,
                    sourcePort = packet.sourcePort,
                    destIp = packet.destIpString,
                    destPort = packet.destPort,
                    uid = uid,
                    isSyn = packet.isSYN,
                    isFin = packet.isFIN,
                    isRst = packet.isRST,
                    outbound = true,
                    blocked = blocked,
                    bytes = bytes
                )
            )
        } catch (_: Exception) { /* tracker should not crash packet loop */ }

        if (blocked) {
            trafficMonitor.recordDropped(uid, bytes)
        } else {
            trafficMonitor.recordForwarded(uid, bytes, true)
        }
    }

    /**
     * Decides once per new flow. An unknown owner is treated as blocked
     * (fail closed): the app's retransmitted SYN / next datagram is evaluated
     * again a moment later, when the owner can be resolved.
     */
    private fun decide(flow: FlowInfo): FlowDecision {
        val uid = resolveFlowUid(flow.protocol, flow.sourceIp, flow.sourcePort, flow.destIp, flow.destPort)
        if (uid == Process.INVALID_UID) return FlowDecision(false, uid)
        val result = ruleMatcher.evaluate(
            uid = uid,
            destPort = flow.destPort,
            destIp = flow.destIp.hostAddress
        )
        return FlowDecision(result.action == RuleMatcher.FirewallAction.ALLOW, uid)
    }

    /** Reply traffic written back to an app by the relay. */
    private fun onInbound(flow: FlowInfo, uid: Int, bytes: Int, tcpFlags: Int) {
        trafficMonitor.recordForwarded(uid, bytes, false)
        val remote = flow.destIp.hostAddress ?: return
        val local = flow.sourceIp.hostAddress ?: return
        try {
            connectionTracker.record(
                PacketObservation(
                    protocol = flow.protocol,
                    sourceIp = remote,
                    sourcePort = flow.destPort,
                    destIp = local,
                    destPort = flow.sourcePort,
                    uid = uid,
                    isSyn = false,
                    isFin = tcpFlags and 0x01 != 0,
                    isRst = tcpFlags and 0x04 != 0,
                    outbound = false,
                    blocked = false,
                    bytes = bytes
                )
            )
        } catch (_: Exception) { /* tracker should not crash the relay */ }
    }

    private fun resolvePacketUid(packet: ParsedPacket?): Int? {
        if (packet == null || (!packet.isTCP && !packet.isUDP)) return null
        val uid = resolveFlowUid(
            packet.protocol,
            packet.ipHeader.sourceIp,
            packet.sourcePort,
            packet.ipHeader.destIp,
            packet.destPort
        )
        return uid.takeIf { it != Process.INVALID_UID }
    }

    private fun resolveFlowUid(
        protocol: Int,
        srcAddr: InetAddress,
        srcPort: Int,
        dstAddr: InetAddress,
        dstPort: Int
    ): Int {
        if (srcPort <= 0 || dstPort <= 0) return Process.INVALID_UID

        val srcIp = srcAddr.hostAddress ?: return Process.INVALID_UID
        val dstIp = dstAddr.hostAddress ?: return Process.INVALID_UID

        val key = ConnectionKey(protocol, srcIp, srcPort, dstIp, dstPort)

        // 1) Fast path: UID cached for this connection 4-tuple.
        uidCache.get(key)?.let { return it }

        // 2) Android 10+: the system tells the VPN app who owns the connection.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                val uid = cm.getConnectionOwnerUid(
                    protocol,
                    InetSocketAddress(srcAddr, srcPort),
                    InetSocketAddress(dstAddr, dstPort)
                )
                if (uid != Process.INVALID_UID) {
                    uidCache.put(key, uid)
                    return uid
                }
            } catch (_: Exception) {
                // fall through to the kernel table
            }
        }

        // 3) Android 8/9 (and last resort): kernel socket table from /proc/net.
        //    A brand-new connection may not be in the periodic snapshot yet, so
        //    on a miss refresh it right away (rate limited).
        var tableUid = KernelSocketTable.resolveUid(
            proto = protocol,
            sourceIp = srcIp,
            sourcePort = srcPort,
            destIp = dstIp,
            destPort = dstPort
        )
        if (tableUid == Process.INVALID_UID && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val now = System.currentTimeMillis()
            if (now - lastTableRefresh > 150) {
                lastTableRefresh = now
                KernelSocketTable.refresh()
                tableUid = KernelSocketTable.resolveUid(
                    proto = protocol,
                    sourceIp = srcIp,
                    sourcePort = srcPort,
                    destIp = dstIp,
                    destPort = dstPort
                )
            }
        }
        if (tableUid != Process.INVALID_UID) {
            uidCache.put(key, tableUid)
            return tableUid
        }
        return Process.INVALID_UID
    }

    private data class ConnectionKey(
        val protocol: Int,
        val sourceIp: String,
        val sourcePort: Int,
        val destIp: String,
        val destPort: Int
    )

    private class UidCache(
        private val ttlMs: Long = 10_000L,
        private val maxSize: Int = 512
    ) {
        private val map = LinkedHashMap<ConnectionKey, Pair<Int, Long>>()

        @Synchronized
        fun get(key: ConnectionKey): Int? {
            val now = System.currentTimeMillis()
            val entry = map[key] ?: return null
            if (now > entry.second) {
                map.remove(key)
                return null
            }
            return entry.first
        }

        @Synchronized
        fun put(key: ConnectionKey, uid: Int) {
            map[key] = uid to (System.currentTimeMillis() + ttlMs)
            if (map.size > maxSize) {
                val now = System.currentTimeMillis()
                map.entries.removeAll { now > it.value.second }
                while (map.size > maxSize && map.isNotEmpty()) {
                    val it = map.entries.iterator()
                    it.next()
                    it.remove()
                }
            }
        }
    }

    private fun observeNetworkChanges() {
        networkJob?.cancel()
        networkJob = serviceScope.launch {
            networkStateMonitor.networkType.collect { newType ->
                if (newType != currentNetworkType && newType != NetworkType.Unknown) {
                    Log.d(TAG, "Network changed: $currentNetworkType -> $newType")
                    currentNetworkType = newType
                    val running = VpnState.Running(newType, blockedPackages.size)
                    _state.value = running
                    _globalState.value = running
                    if (tunnel != null) updateNotification(newType, blockedPackages.size)
                }
            }
        }
    }

    private fun observeRules() {
        rulesJob?.cancel()
        lastBlockedSet = blockedPackages.toSet()
        rulesJob = serviceScope.launch {
            ruleRepository.observeAll().collect { entities ->
                ruleMatcher.updateRules(entities)
                val blockedSet = ruleMatcher.getBlockedPackages().toSet()
                if (blockedSet != lastBlockedSet) {
                    lastBlockedSet = blockedSet
                    blockedPackages = ruleMatcher.getBlockedPackages()
                    val running = VpnState.Running(currentNetworkType, blockedPackages.size)
                    _state.value = running
                    _globalState.value = running
                    updateNotification(currentNetworkType, blockedPackages.size)
                    Log.d(TAG, "Blocked set changed: ${blockedPackages.size} apps blocked")
                }
            }
        }
    }

    private fun buildNotification(
        networkType: NetworkType,
        blockedCount: Int,
        traffic: TrafficStats = trafficMonitor.total.value
    ): android.app.Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, WallService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        val networkLabel = when (networkType) {
            NetworkType.WiFi -> getString(R.string.network_wifi)
            NetworkType.Mobile -> getString(R.string.network_mobile)
            else -> getString(R.string.network_other)
        }

        val trafficLabel = "↑ ${formatBytes(traffic.bytesSent)} · ↓ ${formatBytes(traffic.bytesReceived)} · 🛡 ${formatBytes(traffic.bytesBlocked)}"

        return NotificationCompat.Builder(this, WallApp.CHANNEL_VPN)
            .setContentTitle(getString(R.string.notification_vpn_active, blockedCount))
            .setContentText("$networkLabel · $blockedCount ${getString(R.string.blocked)} · $trafficLabel")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .addAction(
                NotificationCompat.Action.Builder(
                    android.R.drawable.ic_media_pause,
                    getString(R.string.vpn_stop),
                    stopIntent
                ).build()
            )
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(
        networkType: NetworkType,
        blockedCount: Int,
        traffic: TrafficStats = trafficMonitor.total.value
    ) {
        val notification = buildNotification(networkType, blockedCount, traffic)
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(NOTIFICATION_ID_VPN, notification)
    }

    private val NOTIFICATION_ID_VPN = WallApp.NOTIFICATION_ID_VPN
}
