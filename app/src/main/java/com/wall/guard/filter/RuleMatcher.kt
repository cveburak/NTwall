package com.wall.guard.filter

import com.wall.guard.data.db.RuleEntity
import com.wall.guard.vpn.NetworkType
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RuleMatcher @Inject constructor() {

    data class MatchResult(
        val action: FirewallAction,
        val matchedRule: AppRules? = null
    )

    enum class FirewallAction {
        ALLOW,
        BLOCK,
        BLOCK_BY_PORT,
        BLOCK_BY_IP
    }

    @Volatile
    private var rules: Map<Int, AppRules> = emptyMap()

    fun updateRules(entities: List<RuleEntity>) {
        rules = entities.associate { entity ->
            entity.uid to AppRules(
                uid = entity.uid,
                packageName = entity.packageName,
                allowed = entity.allowed,
                blockedPorts = entity.blockedPorts
                    .split(",")
                    .mapNotNull { it.trim().toIntOrNull() },
                blockedIps = entity.blockedIps
                    .split(",")
                    .mapNotNull { it.trim().ifBlank { null } }
            )
        }
    }

    fun evaluate(
        uid: Int,
        destPort: Int = 0,
        destIp: String? = null
    ): MatchResult {
        val appRule = rules[uid] ?: return MatchResult(FirewallAction.ALLOW)

        if (!appRule.allowed) {
            return MatchResult(FirewallAction.BLOCK, appRule)
        }

        if (destPort > 0 && appRule.blockedPorts.contains(destPort)) {
            return MatchResult(FirewallAction.BLOCK_BY_PORT, appRule)
        }

        if (destIp != null && appRule.blockedIps.any { blockedIp ->
                matchesIp(destIp, blockedIp)
            }) {
            return MatchResult(FirewallAction.BLOCK_BY_IP, appRule)
        }

        return MatchResult(FirewallAction.ALLOW, appRule)
    }

    private fun matchesIp(destIp: String, rule: String): Boolean {
        val r = rule.trim().lowercase()
        if (r.isEmpty()) return false
        if (r.contains('/')) return ipInCidr(destIp, r)
        if (isDottedPrefix(r)) return destIp.startsWith(r)
        return destIp.equals(r, ignoreCase = true)
    }

    private fun isDottedPrefix(rule: String): Boolean {
        if (!rule.endsWith(".")) return false
        return rule.all { it.isDigit() || it == '.' }
    }

    private fun ipInCidr(destIp: String, cidr: String): Boolean {
        return try {
            val parts = cidr.split("/", limit = 2)
            if (parts.size != 2) return false
            val prefixBits = parts[1].trim().toIntOrNull() ?: return false
            val network = java.net.InetAddress.getByName(parts[0].trim())
            val address = java.net.InetAddress.getByName(destIp)
            val net = network.address
            val addr = address.address
            if (net.size != addr.size) return false
            val maxBits = net.size * 8
            if (prefixBits < 0 || prefixBits > maxBits) return false
            val fullBytes = prefixBits / 8
            for (i in 0 until fullBytes) {
                if (net[i] != addr[i]) return false
            }
            val remBits = prefixBits % 8
            if (remBits > 0) {
                val mask = (0xFF shl (8 - remBits)) and 0xFF
                if ((net[fullBytes].toInt() and mask) != (addr[fullBytes].toInt() and mask)) {
                    return false
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun getBlockedPackages(): List<String> {
        return rules.values
            .filter { !it.allowed }
            .map { it.packageName }
    }
}
