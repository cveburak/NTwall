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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.wall.guard.appmanager.AppInfo
import com.wall.guard.data.db.RuleEntity
import com.wall.guard.ui.components.AppIcon
import com.wall.guard.ui.components.EmptyState
import com.wall.guard.ui.theme.GuardAccentGreen
import com.wall.guard.ui.theme.GuardDanger
import com.wall.guard.ui.theme.GuardHairline
import com.wall.guard.ui.theme.GuardSurface
import com.wall.guard.ui.theme.GuardTextSecondary
import kotlinx.coroutines.delay

@Composable
fun AppRulesScreen(
    app: AppInfo?,
    rule: RuleEntity?,
    onBack: () -> Unit,
    onSave: (RuleEntity) -> Unit
) {
    if (app == null) {
        Column(Modifier.fillMaxSize()) {
            TopBar(title = stringResource(R.string.app_rules_title), onBack = onBack)
            EmptyState(
                title = stringResource(R.string.conn_not_found),
                body = "",
                modifier = Modifier.padding(top = 40.dp)
            )
        }
        return
    }

    var allowed by remember(app.uid) { mutableStateOf(rule?.allowed ?: true) }
    var portsText by remember(app.uid) {
        mutableStateOf(summarizeList(rule?.blockedPorts).joinToString(", "))
    }
    var ipsText by remember(app.uid) {
        mutableStateOf(summarizeList(rule?.blockedIps).joinToString(", "))
    }
    var saved by remember(app.uid) { mutableStateOf(false) }

    LaunchedEffect(saved) {
        if (saved) {
            delay(2_000)
            saved = false
        }
    }

    val portTokens = remember(portsText) { splitTokens(portsText) }
    val invalidPorts = remember(portTokens) {
        portTokens.filterNot { isValidPort(it) }
    }
    val ipTokens = remember(ipsText) { splitTokens(ipsText) }
    val invalidIps = remember(ipTokens) {
        ipTokens.filterNot { isValidIpRule(it) }
    }
    val hasErrors = invalidPorts.isNotEmpty() || invalidIps.isNotEmpty()

    Column(Modifier.fillMaxSize()) {
        TopBar(title = stringResource(R.string.app_rules_title), onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // App header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(GuardSurface)
                    .border(1.dp, GuardHairline, RoundedCornerShape(8.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppIcon(app.icon, size = 36)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = app.appName.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = app.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Access control
            RuleCard(title = stringResource(R.string.rule_access_title)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = if (allowed) stringResource(R.string.allowed) else stringResource(R.string.blocked),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = if (allowed) GuardAccentGreen else GuardDanger
                        )
                        Spacer(Modifier.size(2.dp))
                        Text(
                            text = stringResource(
                                if (allowed) R.string.rule_access_hint_allow else R.string.rule_access_hint_block
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = allowed, onCheckedChange = { allowed = it })
                }
            }

            // Port blocking
            RuleCard(title = stringResource(R.string.rule_ports_title)) {
                OutlinedTextField(
                    value = portsText,
                    onValueChange = { portsText = it },
                    placeholder = { Text(stringResource(R.string.rule_ports_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    isError = invalidPorts.isNotEmpty(),
                    colors = fieldColors(hasError = invalidPorts.isNotEmpty()),
                    textStyle = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.size(6.dp))
                if (invalidPorts.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.rule_invalid_tokens, invalidPorts.joinToString(", ")),
                        style = MaterialTheme.typography.bodySmall,
                        color = GuardDanger
                    )
                } else {
                    Text(
                        text = stringResource(R.string.rule_ports_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // IP blocking
            RuleCard(title = stringResource(R.string.rule_ips_title)) {
                OutlinedTextField(
                    value = ipsText,
                    onValueChange = { ipsText = it },
                    placeholder = { Text(stringResource(R.string.rule_ips_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    isError = invalidIps.isNotEmpty(),
                    colors = fieldColors(hasError = invalidIps.isNotEmpty()),
                    textStyle = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.size(6.dp))
                if (invalidIps.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.rule_invalid_tokens, invalidIps.joinToString(", ")),
                        style = MaterialTheme.typography.bodySmall,
                        color = GuardDanger
                    )
                } else {
                    Text(
                        text = stringResource(R.string.rule_ips_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                text = stringResource(R.string.rule_live_notice),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            Button(
                onClick = {
                    val base = rule ?: RuleEntity(uid = app.uid, packageName = app.packageName)
                    onSave(base.copy(
                        allowed = allowed,
                        blockedPorts = portTokens.joinToString(","),
                        blockedIps = ipTokens.joinToString(","),
                        updatedAt = System.currentTimeMillis()
                    ))
                    saved = true
                },
                enabled = !hasErrors,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = GuardAccentGreen,
                    contentColor = androidx.compose.ui.graphics.Color(0xFF00190E)
                )
            ) {
                Text(
                    text = stringResource(if (saved) R.string.rule_saved else R.string.rule_save),
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }

            Spacer(Modifier.size(12.dp))
        }
    }
}

@Composable
private fun TopBar(title: String, onBack: () -> Unit) {
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
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun RuleCard(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(GuardSurface)
            .border(1.dp, GuardHairline, RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = GuardTextSecondary
        )
        Spacer(Modifier.size(8.dp))
        content()
    }
}

@Composable
private fun fieldColors(hasError: Boolean) = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = GuardSurface,
    unfocusedContainerColor = GuardSurface,
    focusedBorderColor = if (hasError) GuardDanger else MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = if (hasError) GuardDanger else GuardHairline,
    errorBorderColor = GuardDanger
)

private fun summarizeList(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
}

private fun splitTokens(text: String): List<String> {
    return text.split(",", "\n", " ")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
}

private fun isValidPort(token: String): Boolean {
    val p = token.toIntOrNull() ?: return false
    return p in 1..65535
}

private val IPV4_REGEX = Regex("^(\\d{1,3})(\\.\\d{1,3}){3}$")
private val IPV6_REGEX = Regex("^[0-9a-fA-F:]+$")
private val DOTTED_PREFIX_REGEX = Regex("^(\\d{1,3}\\.)+$")

private fun isValidIpv4(ip: String): Boolean {
    if (!IPV4_REGEX.matches(ip)) return false
    return ip.split(".").all { it.toInt() in 0..255 }
}

private fun isValidIpv6(ip: String): Boolean {
    if (!ip.contains(":")) return false
    if (!IPV6_REGEX.matches(ip)) return false
    val colonCount = ip.count { it == ':' }
    return colonCount in 2..7
}

private fun isDottedPrefix(token: String): Boolean {
    if (token.isEmpty() || !token.endsWith(".")) return false
    if (!DOTTED_PREFIX_REGEX.matches(token)) return false
    return true
}

fun isValidIpRule(token: String): Boolean {
    val t = token.trim()
    if (t.isEmpty()) return false
    if (t.contains('/')) {
        val parts = t.split("/", limit = 2)
        if (parts.size != 2) return false
        val ip = parts[0].trim()
        val prefix = parts[1].trim().toIntOrNull() ?: return false
        return when {
            isValidIpv4(ip) -> prefix in 0..32
            isValidIpv6(ip) -> prefix in 0..128
            else -> false
        }
    }
    return isValidIpv4(t) || isValidIpv6(t) || isDottedPrefix(t)
}