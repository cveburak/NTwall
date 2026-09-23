package com.wall.guard.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
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
import com.wall.guard.R
import com.wall.guard.appmanager.AppInfo
import com.wall.guard.data.db.RuleEntity
import com.wall.guard.stats.PerAppTraffic
import com.wall.guard.stats.formatBytes
import com.wall.guard.ui.components.AppIcon
import com.wall.guard.ui.components.EmptyState
import com.wall.guard.ui.theme.GuardDanger
import com.wall.guard.ui.theme.GuardHairline
import com.wall.guard.ui.theme.GuardSurface

private enum class AppFilterMode { ALL, ALLOWED, BLOCKED }

@Composable
fun AppListScreen(
    apps: List<AppInfo>,
    rules: Map<Int, RuleEntity>,
    trafficByUid: Map<Int, PerAppTraffic>,
    onToggle: (AppInfo, Boolean) -> Unit,
    onEdit: (AppInfo) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var filterMode by remember { mutableStateOf(AppFilterMode.ALLOWED) }

    val blockedCount = rules.count { (_, r) -> !r.allowed }

    val filtered by remember(apps, rules, trafficByUid, searchQuery, filterMode) {
        derivedStateOf {
            apps.filter { app ->
                val rule = rules[app.uid]
                val hasTraffic = trafficByUid[app.uid] != null
                val visible = rule != null || hasTraffic

                val q = searchQuery.isBlank() ||
                    app.appName.contains(searchQuery, true) ||
                    app.packageName.contains(searchQuery, true)
                val f = when (filterMode) {
                    AppFilterMode.ALL -> true
                    AppFilterMode.ALLOWED -> (rule == null || rule.allowed) && hasTraffic
                    AppFilterMode.BLOCKED -> rule != null && !rule.allowed
                }
                visible && q && f
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.app_list_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${filtered.size} uygulama · $blockedCount ${stringResource(R.string.blocked)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.size(10.dp))
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(stringResource(R.string.search_hint)) },
                leadingIcon = { Icon(Icons.Filled.Search, null, Modifier.size(18.dp)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = GuardSurface,
                    unfocusedContainerColor = GuardSurface,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = GuardHairline
                )
            )
            Spacer(Modifier.size(8.dp))
            Row {
                FilterChip(selected = filterMode == AppFilterMode.ALL, onClick = { filterMode = AppFilterMode.ALL }, label = { Text(stringResource(R.string.filter_all)) })
                Spacer(Modifier.width(6.dp))
                FilterChip(selected = filterMode == AppFilterMode.ALLOWED, onClick = { filterMode = AppFilterMode.ALLOWED }, label = { Text(stringResource(R.string.filter_allowed)) })
                Spacer(Modifier.width(6.dp))
                FilterChip(selected = filterMode == AppFilterMode.BLOCKED, onClick = { filterMode = AppFilterMode.BLOCKED }, label = { Text(stringResource(R.string.filter_blocked)) })
            }
        }

        if (apps.isEmpty()) {
            EmptyState(
                title = "Uygulama yükleniyor…",
                body = "Yüklü uygulamaların listesi hazırlanıyor. Bu görünürden kaybolmaz, listenin taraması sürüyor."
            )
        } else if (filtered.isEmpty()) {
            val isSearching = searchQuery.isNotBlank() || filterMode != AppFilterMode.ALL
            EmptyState(
                title = stringResource(if (isSearching) R.string.no_results else R.string.apps_empty_title),
                body = if (isSearching) {
                    "Aramayı veya filtreyi temizleyip tekrar deneyin."
                } else {
                    stringResource(R.string.apps_empty_body)
                }
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp)
            ) {
                items(filtered, key = { it.packageName }) { app ->
                    AppRow(
                        appInfo = app,
                        rule = rules[app.uid],
                        traffic = trafficByUid[app.uid],
                        onToggle = { allowed -> onToggle(app, allowed) },
                        onEdit = { onEdit(app) }
                    )
                }
            }
        }
    }
}

@Composable
private fun AppRow(
    appInfo: AppInfo,
    rule: RuleEntity?,
    traffic: PerAppTraffic?,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit
) {
    val allowed = rule?.allowed ?: true

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(GuardSurface)
            .border(1.dp, GuardHairline, RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onEdit)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(appInfo.icon, size = 36)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = appInfo.appName.toString(),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = appInfo.packageName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        val t = traffic
                        if (t != null && t.bytesBlocked > 0) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "🛡 ${formatBytes(t.bytesBlocked)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = GuardDanger
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.Filled.Tune,
            contentDescription = stringResource(R.string.app_list_edit_hint),
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(6.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (allowed) stringResource(R.string.allowed) else stringResource(R.string.blocked),
                style = MaterialTheme.typography.labelSmall,
                color = if (allowed) MaterialTheme.colorScheme.primary else GuardDanger,
                modifier = Modifier.offset(y = (-4).dp)
            )
            Switch(
                checked = allowed,
                onCheckedChange = onToggle
            )
        }
    }
}