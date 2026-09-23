package com.wall.guard.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wall.guard.R
import com.wall.guard.conntrack.ConnectionDirection
import com.wall.guard.conntrack.ConnectionInfo
import com.wall.guard.conntrack.Protocol
import com.wall.guard.ui.components.AppIcon
import com.wall.guard.ui.components.AppResolver
import com.wall.guard.ui.components.EmptyState
import com.wall.guard.ui.components.ProtocolTag
import com.wall.guard.ui.components.StatusDot
import com.wall.guard.ui.components.formatBytesCompact
import com.wall.guard.ui.components.formatTime
import com.wall.guard.ui.theme.GuardAccentGreen
import com.wall.guard.ui.theme.GuardAmber
import com.wall.guard.ui.theme.GuardDanger
import com.wall.guard.ui.theme.GuardHairline
import com.wall.guard.ui.theme.GuardSurface
import com.wall.guard.ui.theme.GuardTextSecondary
import com.wall.guard.ui.theme.MonoStyle
import com.wall.guard.vpn.VpnState
import java.util.Locale

private const val LIVE_TICK_MS = 1000L

private data class AppGroup(
    val uid: Int,
    val totalBytes: Long,
    val connectionCount: Int,
    val activeCount: Int,
    val hasBlocked: Boolean,
    val lastSeen: Long
)

private enum class DrillFilter { ALL, TCP, UDP, ACTIVE, BLOCKED }

@Composable
fun TrafficScreen(
    vpnState: VpnState,
    connections: List<ConnectionInfo>,
    resolver: AppResolver,
    onConnectionClick: (Long) -> Unit,
    onStart: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var selectedAppUid by remember { mutableStateOf(-1) }
    var drillFilter by remember { mutableStateOf(DrillFilter.ALL) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(LIVE_TICK_MS)
        }
    }

    val isRunning = vpnState is VpnState.Running

    if (selectedAppUid >= 0) {
        val appName = remember(connections, selectedAppUid) {
            connections.firstOrNull { it.uid == selectedAppUid }
                ?.let { resolver.info(it.uid)?.appName?.toString() } ?: "?"
        }
        DrillDown(
            connections = connections,
            uid = selectedAppUid,
            appName = appName,
            query = query,
            onQueryChange = { query = it },
            filter = drillFilter,
            onFilterChange = { drillFilter = it },
            now = now,
            resolver = resolver,
            onBack = { selectedAppUid = -1; query = ""; drillFilter = DrillFilter.ALL },
            onConnectionClick = onConnectionClick
        )
    } else {
        Grouped(
            connections = connections,
            query = query,
            onQueryChange = { query = it },
            now = now,
            resolver = resolver,
            vpnState = vpnState,
            isRunning = isRunning,
            onStart = onStart,
            onAppClick = { uid -> selectedAppUid = uid; query = "" }
        )
    }
}

/* ── Grouped view: one row per app ───────────────────────────────── */

@Composable
private fun Grouped(
    connections: List<ConnectionInfo>,
    query: String,
    onQueryChange: (String) -> Unit,
    now: Long,
    resolver: AppResolver,
    vpnState: VpnState,
    isRunning: Boolean,
    onStart: () -> Unit,
    onAppClick: (Int) -> Unit
) {
    val groups = remember(connections, now) {
        connections
            .groupBy { it.uid }
            .map { (uid, conns) ->
                AppGroup(
                    uid = uid,
                    totalBytes = conns.sumOf { it.totalBytes },
                    connectionCount = conns.size,
                    activeCount = conns.count { it.isActiveNow(now) },
                    hasBlocked = conns.any { it.blocked },
                    lastSeen = conns.maxOf { it.lastSeen }
                )
            }
            .sortedByDescending { it.lastSeen }
    }

    val q = query.trim().lowercase(Locale.US)
    val filtered = remember(groups, q) {
        if (q.isEmpty()) groups
        else groups.filter { g ->
            val info = resolver.info(g.uid)
            val name = info?.appName?.toString()?.lowercase(Locale.US) ?: ""
            val pkg = info?.packageName?.lowercase(Locale.US) ?: ""
            name.contains(q) || pkg.contains(q)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.traffic_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(if (isRunning) GuardAccentGreen else GuardTextSecondary)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "${groups.size} uygulama · ${connections.size} bağlantı",
                        style = MonoStyle.copy(fontSize = 11.sp),
                        color = if (isRunning) GuardAccentGreen else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.size(10.dp))
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text(stringResource(R.string.search_traffic_hint)) },
                leadingIcon = { Icon(Icons.Filled.Search, null, Modifier.size(18.dp)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = GuardSurface,
                    unfocusedContainerColor = GuardSurface,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = GuardHairline
                ),
                textStyle = MonoStyle.copy(fontSize = 13.sp)
            )
        }

        if (!isRunning) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val connecting = vpnState is VpnState.Starting || vpnState is VpnState.Preparing
                Text(
                    text = if (connecting) stringResource(R.string.vpn_connecting)
                    else stringResource(R.string.firewall_off_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = GuardAmber,
                    modifier = Modifier.weight(1f)
                )
                if (!connecting && vpnState !is VpnState.Error) {
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onStart, shape = RoundedCornerShape(6.dp)) {
                        Text(stringResource(R.string.firewall_banner_start), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        if (filtered.isEmpty()) {
            EmptyState(
                title = stringResource(
                    if (connections.isEmpty()) R.string.list_empty_title else R.string.no_results
                ),
                body = if (connections.isEmpty()) {
                    stringResource(R.string.list_empty_body)
                } else {
                    "Aramayı veya filtreyi temizleyip tekrar deneyin."
                }
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
            ) {
                items(filtered, key = { it.uid }) { group ->
                    AppGroupRow(
                        group = group,
                        resolver = resolver,
                        onClick = { onAppClick(group.uid) }
                    )
                }
            }
        }
    }
}

@Composable
private fun AppGroupRow(
    group: AppGroup,
    resolver: AppResolver,
    onClick: () -> Unit
) {
    val info = remember(group.uid) { resolver.info(group.uid) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(GuardSurface)
            .border(1.dp, GuardHairline, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(info?.icon, size = 36)
        Spacer(Modifier.width(10.dp))

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = info?.appName?.toString() ?: "?",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (group.hasBlocked) {
                    Spacer(Modifier.width(6.dp))
                    StatusDot(GuardDanger)
                }
            }
            Spacer(Modifier.size(3.dp))
            Text(
                text = "${group.connectionCount} bağlantı" +
                    if (group.activeCount > 0) " · ${group.activeCount} aktif" else "",
                style = MonoStyle.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.width(8.dp))

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = formatBytesCompact(group.totalBytes),
                style = MonoStyle.copy(fontSize = 13.sp),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.size(2.dp))
            Text(
                text = formatTime(group.lastSeen),
                style = MonoStyle.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

/* ── Drill-down: individual connections for one app ──────────────── */

@Composable
private fun DrillDown(
    connections: List<ConnectionInfo>,
    uid: Int,
    appName: String,
    query: String,
    onQueryChange: (String) -> Unit,
    filter: DrillFilter,
    onFilterChange: (DrillFilter) -> Unit,
    now: Long,
    resolver: AppResolver,
    onBack: () -> Unit,
    onConnectionClick: (Long) -> Unit
) {
    val appConns = remember(connections, uid) { connections.filter { it.uid == uid } }

    val q = query.trim().lowercase(Locale.US)
    val filtered = remember(appConns, q, filter, now) {
        appConns.filter { c ->
            val activeNow = c.isActiveNow(now)
            val fOk = when (filter) {
                DrillFilter.ALL -> true
                DrillFilter.ACTIVE -> activeNow
                DrillFilter.BLOCKED -> c.blocked
                DrillFilter.TCP -> c.protocol == Protocol.TCP
                DrillFilter.UDP -> c.protocol == Protocol.UDP
            }
            val qOk = q.isEmpty() ||
                c.destIp.lowercase(Locale.US).contains(q) ||
                (c.hostname?.lowercase(Locale.US)?.contains(q) == true) ||
                c.destPort.toString().contains(q)
            fOk && qOk
        }
    }

    val activeCount = appConns.count { it.isActiveNow(now) }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
                Text(
                    text = appName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${appConns.size} · ${activeCount} aktif",
                    style = MonoStyle.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.size(8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text(stringResource(R.string.search_traffic_hint)) },
                leadingIcon = { Icon(Icons.Filled.Search, null, Modifier.size(18.dp)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = GuardSurface,
                    unfocusedContainerColor = GuardSurface,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = GuardHairline
                ),
                textStyle = MonoStyle.copy(fontSize = 13.sp)
            )
            Spacer(Modifier.size(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                FilterChip(selected = filter == DrillFilter.ALL, onClick = { onFilterChange(DrillFilter.ALL) }, label = { Text(stringResource(R.string.chip_all)) })
                FilterChip(selected = filter == DrillFilter.ACTIVE, onClick = { onFilterChange(DrillFilter.ACTIVE) }, label = { Text(stringResource(R.string.chip_active)) })
                FilterChip(selected = filter == DrillFilter.BLOCKED, onClick = { onFilterChange(DrillFilter.BLOCKED) }, label = { Text(stringResource(R.string.chip_blocked)) })
                FilterChip(selected = filter == DrillFilter.TCP, onClick = { onFilterChange(DrillFilter.TCP) }, label = { Text(stringResource(R.string.chip_tcp)) })
                FilterChip(selected = filter == DrillFilter.UDP, onClick = { onFilterChange(DrillFilter.UDP) }, label = { Text(stringResource(R.string.chip_udp)) })
            }
        }

        if (filtered.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.no_results),
                body = "Aramayı veya filtreyi temizleyip tekrar deneyin."
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
            ) {
                items(filtered, key = { it.id }) { conn ->
                    ConnectionRow(
                        conn = conn,
                        now = now,
                        resolver = resolver,
                        onClick = { onConnectionClick(conn.id) }
                    )
                }
            }
        }
    }
}

/* ── Single connection row (used in drill-down) ─────────────────── */

@Composable
fun ConnectionRow(
    conn: ConnectionInfo,
    now: Long,
    resolver: AppResolver,
    onClick: () -> Unit
) {
    val info = remember(conn.uid) { resolver.info(conn.uid) }
    val activeNow = conn.isActiveNow(now)
    val dotColor = when {
        conn.blocked -> GuardDanger
        activeNow -> GuardAccentGreen
        else -> GuardTextSecondary
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(GuardSurface)
            .border(1.dp, if (conn.blocked) GuardDanger.copy(alpha = 0.35f) else GuardHairline, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusDot(dotColor)
        Spacer(Modifier.width(10.dp))
        AppIcon(info?.icon, size = 32)
        Spacer(Modifier.width(10.dp))

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = info?.appName?.toString() ?: "?",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(6.dp))
                ProtocolTag(conn.protocol, blocked = conn.blocked)
            }
            Spacer(Modifier.size(3.dp))
            Text(
                text = hostLine(conn),
                style = MonoStyle.copy(fontSize = 12.sp),
                color = if (conn.blocked) GuardDanger else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.width(8.dp))

        Column(horizontalAlignment = Alignment.End) {
            val dir = if (conn.direction == ConnectionDirection.OUTBOUND) "↑" else "↓"
            Text(
                text = "$dir ${formatBytesCompact(conn.totalBytes)}",
                style = MonoStyle.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.size(2.dp))
            Text(
                text = formatTime(conn.lastSeen),
                style = MonoStyle.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

private fun hostLine(conn: ConnectionInfo): String {
    val host = conn.hostname ?: ""
    val port = conn.destPort
    return when {
        conn.blocked -> "${conn.destIp}:$port ⛔"
        host.isNotEmpty() && host != conn.destIp -> "$host (${conn.destIp}:$port)"
        else -> "${conn.destIp}:$port"
    }
}