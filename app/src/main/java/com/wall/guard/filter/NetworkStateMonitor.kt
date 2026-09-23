package com.wall.guard.filter

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.core.content.getSystemService
import com.wall.guard.vpn.NetworkType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetworkStateMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val connectivityManager: ConnectivityManager? =
        context.getSystemService()

    val networkType: Flow<NetworkType> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(getCurrentNetworkType())
            }

            override fun onLost(network: Network) {
                trySend(NetworkType.Unknown)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) {
                trySend(NetworkType.fromTransportType(
                    capabilities.getFirstTransport()
                ))
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager?.registerNetworkCallback(request, callback)

        trySend(getCurrentNetworkType())

        awaitClose {
            connectivityManager?.unregisterNetworkCallback(callback)
        }
    }.distinctUntilChanged()

    fun getCurrentNetworkType(): NetworkType {
        val activeNetwork = connectivityManager?.activeNetwork ?: return NetworkType.Unknown
        val caps = connectivityManager?.getNetworkCapabilities(activeNetwork) ?: return NetworkType.Unknown
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return NetworkType.Unknown
        return NetworkType.fromTransportType(caps.getFirstTransport())
    }

    fun isMetered(): Boolean {
        val activeNetwork = connectivityManager?.activeNetwork ?: return false
        val caps = connectivityManager?.getNetworkCapabilities(activeNetwork) ?: return false
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun NetworkCapabilities.getFirstTransport(): Int {
        val transports = arrayOf(
            android.net.NetworkCapabilities.TRANSPORT_WIFI,
            android.net.NetworkCapabilities.TRANSPORT_CELLULAR,
            android.net.NetworkCapabilities.TRANSPORT_ETHERNET,
            android.net.NetworkCapabilities.TRANSPORT_VPN,
            android.net.NetworkCapabilities.TRANSPORT_BLUETOOTH,
            android.net.NetworkCapabilities.TRANSPORT_WIFI_AWARE,
            android.net.NetworkCapabilities.TRANSPORT_LOWPAN
        )
        for (t in transports) {
            if (hasTransport(t)) return t
        }
        return -1
    }
}
