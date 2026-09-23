package com.wall.guard.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import com.wall.guard.stats.formatBytes
import com.wall.guard.ui.components.AppIcon
import com.wall.guard.ui.components.AppResolver
import com.wall.guard.ui.components.DetailRow
import com.wall.guard.ui.components.EmptyState
import com.wall.guard.ui.components.ProtocolTag
import com.wall.guard.ui.components.StatusDot
import com.wall.guard.ui.components.formatTime
import com.wall.guard.ui.theme.GuardDanger
import com.wall.guard.ui.theme.GuardAccentGreen
import com.wall.guard.ui.theme.GuardHairline
import com.wall.guard.ui.theme.GuardSurface
import com.wall.guard.ui.theme.GuardSurfaceVariant
import com.wall.guard.ui.theme.GuardTextSecondary
import com.wall.guard.ui.theme.MonoStyle

@Composable
fun ConnectionDetailScreen(
    connection: ConnectionInfo?,
    resolver: AppResolver,
    onBack: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
            }
            Text(
                text = stringResource(R.string.conn_detail_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        if (connection == null) {
            EmptyState(
                title = stringResource(R.string.conn_not_found),
                body = "",
                modifier = Modifier.padding(top = 40.dp)
            )
            return@Column
        }

        ConnectionDetailHeader(connection, resolver)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 12.dp)
        ) {
            if (connection.blocked) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(GuardDanger.copy(alpha = 0.12f))
                        .border(1.dp, GuardDanger.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusDot(GuardDanger)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.detail_blocked_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = GuardDanger
                    )
                }
                Spacer(Modifier.size(8.dp))
            }

            val info = remember(connection.uid) { resolver.info(connection.uid) }
            val appName = connection.appName
                ?: info?.appName?.toString()
                ?: connection.packageName
                ?: stringResource(R.string.unknown_app)

            DetailRow(stringResource(R.string.detail_app), appName)
            DetailRow(
                stringResource(R.string.detail_protocol),
                connection.protocol.label
            )
            DetailRow(
                stringResource(R.string.detail_direction),
                if (connection.direction == ConnectionDirection.OUTBOUND) {
                    stringResource(R.string.direction_out)
                } else {
                    stringResource(R.string.direction_in)
                }
            )
            DetailRow(
                stringResource(R.string.detail_state),
                connection.state.label,
                valueColor = if (connection.isActiveNow()) GuardAccentGreen else GuardTextSecondary
            )
            DetailRow(
                stringResource(R.string.detail_dest_ip),
                connection.destIp,
                valueColor = if (connection.blocked) GuardDanger else MaterialTheme.colorScheme.onSurface
            )
            DetailRow(
                stringResource(R.string.detail_hostname),
                connection.hostname ?: stringResource(R.string.hostname_unresolved)
            )
            DetailRow(stringResource(R.string.detail_dest_port), connection.destPort.toString())
            DetailRow(stringResource(R.string.detail_source_ip), connection.sourceIp)
            DetailRow(stringResource(R.string.detail_source_port), connection.sourcePort.toString())
            DetailRow(stringResource(R.string.detail_bytes_sent), formatBytes(connection.bytesSent))
            DetailRow(stringResource(R.string.detail_bytes_received), formatBytes(connection.bytesReceived))
            DetailRow(stringResource(R.string.detail_first_seen), formatTime(connection.firstSeen))
            DetailRow(stringResource(R.string.detail_last_seen), formatTime(connection.lastSeen))
            DetailRow(stringResource(R.string.detail_uid), connection.uid.toString(), valueColor = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(24.dp))
        }
    }
}

@Composable
private fun ConnectionDetailHeader(conn: ConnectionInfo, resolver: AppResolver) {
    val info = remember(conn.uid) { resolver.info(conn.uid) }
    val appName = conn.appName
        ?: info?.appName?.toString()
        ?: conn.packageName
        ?: stringResource(R.string.unknown_app)
    val activeNow = conn.isActiveNow()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(GuardSurface)
            .border(1.dp, GuardHairline, RoundedCornerShape(8.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(info?.icon, size = 44)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = appName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.size(2.dp))
            Text(
                text = "${conn.destIp}:${conn.destPort}",
                style = MonoStyle.copy(fontSize = 12.sp),
                color = if (conn.blocked) GuardDanger else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        ProtocolTag(conn.protocol, blocked = conn.blocked)
        Spacer(Modifier.width(6.dp))
        StatusDot(
            if (conn.blocked) GuardDanger else if (activeNow) GuardAccentGreen else GuardTextSecondary
        )
    }
}