package org.superdro.platform.android.network

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.superdro.core.connection.NetworkInterfaceInfo
import org.superdro.core.connection.NetworkInterfaces

/**
 * The Android implementation of [NetworkInterfaces]: the interfaces of the device with their
 * addresses, for the bind address of the data output. The list has Wi-Fi, localhost, VPN, USB
 * Ethernet, and the interfaces with no kind (for example the cellular network).
 * `selectedBy(BindInterface.All)` selects each interface with a kind.
 *
 * The list follows the network callbacks while [scope] is active.
 */
class AndroidNetworkInterfaces(context: Context, scope: CoroutineScope) : NetworkInterfaces {

    private val tracker = NetworkTracker(context, scope)

    override val interfaces: StateFlow<List<NetworkInterfaceInfo>> =
        tracker.networks
            .map(NetworkRules::interfaces)
            .stateIn(scope, SharingStarted.Eagerly, NetworkRules.interfaces(emptyList()))
}
