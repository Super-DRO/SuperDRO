package org.superdro.platform.android.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn

/**
 * Follows the networks of the device with a `ConnectivityManager` callback. The callback stays
 * registered while [scope] is active. The request also covers a VPN and a network with no internet
 * access, for example the access point of an adapter. It needs `ACCESS_NETWORK_STATE`.
 */
internal class NetworkTracker(context: Context, scope: CoroutineScope) {

    private val connectivity: ConnectivityManager? = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    /** The networks now. */
    val networks: StateFlow<List<NetworkSnapshot>> = callbackFlow {
        val manager = connectivity
        if (manager == null) {
            trySend(emptyList())
            awaitClose { }
            return@callbackFlow
        }
        val known = ConcurrentHashMap<Network, Pair<NetworkCapabilities?, LinkProperties?>>()
        fun publish() {
            trySend(known.entries.map { (network, state) -> snapshotOf(network, state.first, state.second) })
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                known[network] = manager.getNetworkCapabilities(network) to manager.getLinkProperties(network)
                publish()
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                known[network] = networkCapabilities to (known[network]?.second ?: manager.getLinkProperties(network))
                publish()
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                known[network] = (known[network]?.first ?: manager.getNetworkCapabilities(network)) to linkProperties
                publish()
            }

            override fun onLost(network: Network) {
                known.remove(network)
                publish()
            }
        }
        val request = NetworkRequest.Builder()
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        manager.registerNetworkCallback(request, callback)
        publish()
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, currentNetworks())

    /**
     * Reads the networks now, so that the first value is correct before the first callback.
     * `getAllNetworks()` is deprecated on API 31, but it is the only call with no callback.
     */
    @Suppress("DEPRECATION")
    private fun currentNetworks(): List<NetworkSnapshot> {
        val manager = connectivity ?: return emptyList()
        return manager.allNetworks.map { network ->
            snapshotOf(network, manager.getNetworkCapabilities(network), manager.getLinkProperties(network))
        }
    }

    private fun snapshotOf(network: Network, capabilities: NetworkCapabilities?, properties: LinkProperties?): NetworkSnapshot =
        NetworkSnapshot(
            interfaceName = properties?.interfaceName ?: "network-$network",
            transports = capabilities?.let(BindInterfaces::transportsOf) ?: setOf(LinkTransport.Other),
            addresses = properties?.linkAddresses.orEmpty().mapNotNull { linkAddress -> linkAddress.address?.hostAddress },
        )
}
