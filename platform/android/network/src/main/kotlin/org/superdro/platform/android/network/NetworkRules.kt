package org.superdro.platform.android.network

import org.superdro.core.connection.BindAddresses
import org.superdro.core.connection.ConnectionRequest
import org.superdro.core.connection.LinkCapability
import org.superdro.core.connection.LinkFailure
import org.superdro.core.connection.NetworkInterfaceInfo
import org.superdro.core.connection.PermissionKind
import org.superdro.core.model.BindInterface

/** The transport of one Android network, with no Android type. */
enum class LinkTransport { Wifi, Ethernet, Usb, Vpn, Cellular, Bluetooth, Other }

/**
 * One Android network at one time: its interface name, its transports, and its addresses as text.
 */
data class NetworkSnapshot(
    val interfaceName: String,
    val transports: Set<LinkTransport>,
    val addresses: List<String>,
)

/**
 * The network rules of the TCP links and of the data output, with no Android type.
 */
object NetworkRules {

    /** The name of the loopback interface. */
    const val LOOPBACK_NAME: String = "lo"

    /** The addresses of the loopback interface. */
    val LOOPBACK_ADDRESSES: List<String> = listOf("127.0.0.1", "::1")

    /** The manifest name of the local network permission (API 37). */
    const val LOCAL_NETWORK_PERMISSION: String = "android.permission.ACCESS_LOCAL_NETWORK"

    /** API 37 (Android 17): the first API level that has `ACCESS_LOCAL_NETWORK`. */
    const val LOCAL_NETWORK_API: Int = 37

    /**
     * Gives the bind choice of the data output for a network: a VPN is [BindInterface.Vpn],
     * Wi-Fi is [BindInterface.WiFi], and Ethernet or USB is [BindInterface.UsbEthernet]. Each other
     * network (for example the cellular network) gives null: the data output does not offer it.
     */
    fun bindInterfaceOf(transports: Set<LinkTransport>): BindInterface? = when {
        LinkTransport.Vpn in transports -> BindInterface.Vpn
        LinkTransport.Wifi in transports -> BindInterface.WiFi
        LinkTransport.Ethernet in transports || LinkTransport.Usb in transports -> BindInterface.UsbEthernet
        else -> null
    }

    /**
     * Gives the interface list of [NetworkInterfaces][org.superdro.core.connection.NetworkInterfaces]:
     * each network, and the loopback interface with [BindInterface.Localhost]. Two networks on
     * one interface give one entry with the addresses of both. The order: Wi-Fi, localhost, VPN,
     * USB Ethernet, then the interfaces with no kind; then the name.
     *
     * Android gives the IPv6 link-local addresses of `LinkProperties` with no scope ID. A bind to
     * such an address fails, so the list leaves them out ([BindAddresses]).
     */
    fun interfaces(networks: Collection<NetworkSnapshot>): List<NetworkInterfaceInfo> {
        val byName = linkedMapOf<String, NetworkInterfaceInfo>()
        networks.forEach { network ->
            val kind = bindInterfaceOf(network.transports)
            val known = byName[network.interfaceName]
            val addresses = BindAddresses.bindable(network.addresses)
            byName[network.interfaceName] = if (known == null) {
                NetworkInterfaceInfo(network.interfaceName, kind, addresses.distinct())
            } else {
                NetworkInterfaceInfo(known.name, known.kind ?: kind, (known.addresses + addresses).distinct())
            }
        }
        if (LOOPBACK_NAME !in byName) {
            byName[LOOPBACK_NAME] = NetworkInterfaceInfo(LOOPBACK_NAME, BindInterface.Localhost, LOOPBACK_ADDRESSES)
        }
        return byName.values.sortedWith(compareBy({ info -> info.kind?.ordinal ?: Int.MAX_VALUE }, { info -> info.name }))
    }

    /** True when the Android version has the local network permission: API 37 and higher. */
    fun localNetworkPermissionNeeded(sdkInt: Int): Boolean = sdkInt >= LOCAL_NETWORK_API

    /**
     * Gives the capability of the TCP links: no network is [LinkFailure.NetworkOff], and a local
     * network permission that the Android version needs and the user did not grant is
     * [LinkFailure.PermissionMissing]. A network of any transport counts, also a Wi-Fi network with
     * no internet access (an adapter in access point mode).
     */
    fun tcpCapability(hasNetwork: Boolean, localNetworkNeeded: Boolean, localNetworkGranted: Boolean): LinkCapability = when {
        !hasNetwork -> LinkCapability.Unavailable(LinkFailure.NetworkOff)
        localNetworkNeeded && !localNetworkGranted -> LinkCapability.Unavailable(LinkFailure.PermissionMissing)
        else -> LinkCapability.Available
    }

    /** True when one or more networks other than the loopback interface are up. */
    fun hasNetwork(networks: Collection<NetworkSnapshot>): Boolean =
        networks.any { network -> network.interfaceName != LOOPBACK_NAME }

    /** The request that asks for the local network permission on the request bus of the app. */
    val localNetworkRequest: ConnectionRequest = ConnectionRequest.RequestPermissions(setOf(PermissionKind.LocalNetwork))

    /** Gives the manifest permissions of [kind] on [sdkInt]: `ACCESS_LOCAL_NETWORK` for the local network on API 37 and higher. */
    fun manifestPermissions(kind: PermissionKind, sdkInt: Int): List<String> =
        if (kind == PermissionKind.LocalNetwork && localNetworkPermissionNeeded(sdkInt)) listOf(LOCAL_NETWORK_PERMISSION) else emptyList()
}
