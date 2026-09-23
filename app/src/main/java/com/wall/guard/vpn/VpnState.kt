package com.wall.guard.vpn

sealed interface VpnState {
    data object Idle : VpnState
    data object Preparing : VpnState
    data object Starting : VpnState

    data class Running(
        val networkType: NetworkType = NetworkType.Unknown,
        val blockedCount: Int = 0
    ) : VpnState

    data object Reconfiguring : VpnState
    data object Stopping : VpnState

    data class Error(
        val message: String,
        val cause: Throwable? = null
    ) : VpnState
}

enum class NetworkType {
    WiFi,
    Mobile,
    Ethernet,
    VPN,
    Unknown;

    val isMetered: Boolean get() = this == Mobile

    companion object {
        fun fromTransportType(transport: Int): NetworkType = when (transport) {
            android.net.NetworkCapabilities.TRANSPORT_WIFI -> WiFi
            android.net.NetworkCapabilities.TRANSPORT_CELLULAR -> Mobile
            android.net.NetworkCapabilities.TRANSPORT_ETHERNET -> Ethernet
            android.net.NetworkCapabilities.TRANSPORT_VPN -> VPN
            else -> Unknown
        }
    }
}
