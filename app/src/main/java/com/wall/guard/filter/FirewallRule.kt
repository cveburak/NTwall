package com.wall.guard.filter

sealed interface FirewallRule {
    val uid: Int
    val priority: Int

    data class Allow(
        override val uid: Int,
        override val priority: Int = 0
    ) : FirewallRule

    data class Block(
        override val uid: Int,
        override val priority: Int = 0,
        val reason: String = ""
    ) : FirewallRule

    data class BlockByPort(
        override val uid: Int,
        val port: Int,
        override val priority: Int = 1
    ) : FirewallRule

    data class BlockByIp(
        override val uid: Int,
        val ipAddress: String,
        override val priority: Int = 1
    ) : FirewallRule
}

data class AppRules(
    val uid: Int,
    val packageName: String,
    val allowed: Boolean = true,
    val blockedPorts: List<Int> = emptyList(),
    val blockedIps: List<String> = emptyList()
)
