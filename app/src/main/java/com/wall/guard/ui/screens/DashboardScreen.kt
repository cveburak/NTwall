package com.wall.guard.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wall.guard.R
import com.wall.guard.stats.PerAppTraffic
import com.wall.guard.stats.TrafficStats
import com.wall.guard.stats.formatBytes
import com.wall.guard.ui.components.AppResolver
import com.wall.guard.ui.components.SectionLabel
import com.wall.guard.ui.components.StatCell
import com.wall.guard.ui.components.StatusDot
import com.wall.guard.ui.components.formatBytesCompact
import com.wall.guard.ui.components.AppIcon
import com.wall.guard.ui.theme.GuardDanger
import com.wall.guard.ui.theme.GuardAccentGreen
import com.wall.guard.ui.theme.GuardHairline
import com.wall.guard.ui.theme.GuardTextSecondary
import com.wall.guard.ui.theme.MonoStyle
import com.wall.guard.ui.theme.GuardAmber
import com.wall.guard.vpn.NetworkType
import com.wall.guard.vpn.VpnState

@Composable
fun DashboardScreen(
    vpnState: VpnState,
    networkType: NetworkType,
    totalTraffic: TrafficStats,
    perApp: Map<Int, PerAppTraffic>,
    activeConnections: Int,
    blockedAppCount: Int,
    resolver: AppResolver,
    onNavigateToTraffic: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val isRunning = vpnState is VpnState.Running

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Spacer(Modifier.size(8.dp))

        // Wordmark + status
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.app_wordmark),
                style = MonoStyle.copy(
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 3.sp,
                    fontFamily = FontFamily.Monospace
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.weight(1f))
            StatusChip(vpnState)
        }

        // Status card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, GuardHairline, RoundedCornerShape(8.dp))
                .padding(16.dp)
        ) {
            val title = when (vpnState) {
                is VpnState.Running -> stringResource(R.string.vpn_status_connected)
                is VpnState.Starting, VpnState.Preparing -> stringResource(R.string.vpn_status_connecting)
                is VpnState.Error -> stringResource(R.string.vpn_status_error)
                else -> stringResource(R.string.vpn_status_disconnected)
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.size(4.dp))
            val netLabel = when (networkType) {
                NetworkType.WiFi -> stringResource(R.string.network_wifi)
                NetworkType.Mobile -> stringResource(R.string.network_mobile)
                else -> stringResource(R.string.network_other)
            }
            Text(
                text = if (isRunning) {
                    "$netLabel · $blockedAppCount ${stringResource(R.string.blocked)} · $activeConnections ${stringResource(R.string.stat_active)}".lowercase()
                } else if (vpnState is VpnState.Error) {
                    vpnState.message
                } else {
                    stringResource(R.string.dashboard_requires_vpn)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // ANALİZ button
        Button(
            onClick = onNavigateToTraffic,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = GuardAccentGreen,
                contentColor = androidx.compose.ui.graphics.Color(0xFF00190E)
            ),
            enabled = isRunning
        ) {
            Icon(Icons.AutoMirrored.Filled.ShowChart, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.dashboard_analiz),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp
            )
        }
        Text(
            text = stringResource(R.string.dashboard_analiz_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp)
        )

        // Flow stats
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCell(
                label = stringResource(R.string.stat_forwarded),
                value = formatBytesCompact(totalTraffic.totalForwarded),
                modifier = Modifier.weight(1f)
            )
            StatCell(
                label = stringResource(R.string.stat_blocked),
                value = formatBytesCompact(totalTraffic.bytesBlocked),
                modifier = Modifier.weight(1f)
            )
            StatCell(
                label = stringResource(R.string.stat_active),
                value = (if (isRunning) activeConnections else 0).toString(),
                modifier = Modifier.weight(1f)
            )
        }

        // Top blocked apps
        val topBlocked = remember(perApp) {
            perApp.values
                .filter { it.uid > 0 && it.bytesBlocked > 0 }
                .sortedByDescending { it.bytesBlocked }
                .take(4)
        }
        if (topBlocked.isNotEmpty()) {
            SectionLabel(stringResource(R.string.stat_blocked).uppercase() + " TRAFİK")
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, GuardHairline, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                topBlocked.forEach { t ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val info = remember(t.uid) { resolver.info(t.uid) }
                        AppIcon(info?.icon, size = 32)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = info?.appName?.toString() ?: t.packageName.ifBlank { "?" },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = "🛡 " + formatBytes(t.bytesBlocked),
                            style = MonoStyle.copy(fontSize = 13.sp),
                            color = GuardDanger
                        )
                    }
                }
            }
        }

        // Firewall control
        val controlEnabled = vpnState !is VpnState.Starting &&
            vpnState !is VpnState.Preparing &&
            vpnState !is VpnState.Reconfiguring
        Button(
            onClick = if (isRunning) onStop else onStart,
            modifier = Modifier.fillMaxWidth(),
            enabled = controlEnabled,
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRunning) GuardDanger else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (isRunning) androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.onSurface
            ),
            border = if (isRunning) null else androidx.compose.foundation.BorderStroke(1.dp, GuardHairline)
        ) {
            Icon(
                if (isRunning) Icons.Filled.LockOpen else Icons.Filled.Lock,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (isRunning) R.string.vpn_stop else R.string.vpn_start))
        }

        Text(
            text = stringResource(R.string.privacy_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
        )

        Spacer(Modifier.size(12.dp))
    }
}

@Composable
private fun StatusChip(vpnState: VpnState) {
    val (label, color) = when (vpnState) {
        is VpnState.Running -> "AKTİF" to GuardAccentGreen
        is VpnState.Starting, VpnState.Preparing -> "BAĞLANIYOR" to GuardAmber
        is VpnState.Error -> "HATA" to GuardDanger
        else -> "KAPALI" to GuardTextSecondary
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, GuardHairline, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusDot(color)
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MonoStyle.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
            color = color
        )
    }
}