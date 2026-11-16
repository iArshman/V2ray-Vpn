package com.hollow.vpn.vpn

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Watches the underlying physical network (Wi-Fi <-> Cellular, DHCP lease changes, roaming)
 * while the VPN tunnel is active and triggers a debounced handover callback so dead sockets
 * can be detected and recovered cleanly.
 */
class NetworkMonitor(
    private val connectivity: ConnectivityManager,
    private val onHandover: (reason: String) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var upstream: Network? = null
    private var upstreamAddresses: Set<String> = emptySet()
    private var handoverJob: Job? = null
    private var registered = false

    private val request: NetworkRequest by lazy {
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
    }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val previous = upstream
            upstream = network
            upstreamAddresses = emptySet()
            if (previous != null && previous != network) {
                scheduleHandover("Network switched")
            }
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            if (upstream != null && upstream != network) return
            val newAddresses = linkProperties.linkAddresses
                .mapNotNull { it.address?.hostAddress }
                .toSet()
            val previous = upstreamAddresses
            upstream = network
            upstreamAddresses = newAddresses
            if (previous.isNotEmpty() && previous != newAddresses && newAddresses.isNotEmpty()) {
                scheduleHandover("Interface address changed")
            }
        }

        override fun onLost(network: Network) {
            if (upstream == network) {
                upstream = null
                upstreamAddresses = emptySet()
            }
        }
    }

    fun start() {
        if (registered) return
        try {
            connectivity.registerNetworkCallback(request, callback)
            registered = true
        } catch (_: Exception) {
        }
    }

    fun stop() {
        handoverJob?.cancel()
        handoverJob = null
        if (registered) {
            try {
                connectivity.unregisterNetworkCallback(callback)
            } catch (_: Exception) {
            }
            registered = false
        }
        scope.cancel()
    }

    private fun scheduleHandover(reason: String) {
        handoverJob?.cancel()
        handoverJob = scope.launch {
            delay(HANDOVER_DEBOUNCE_MS)
            onHandover(reason)
        }
    }

    companion object {
        private const val HANDOVER_DEBOUNCE_MS = 1_500L
    }
}
