package org.superdro.platform.android.network

import android.net.NetworkCapabilities
import android.os.Build
import org.superdro.core.model.BindInterface

/**
 * The network interfaces of the data output bind address. [AndroidNetworkInterfaces] gives each
 * interface the kind of [bindInterfaceOf].
 */
object BindInterfaces {

    /** Gives the [BindInterface] of a network, or null for a network that the output does not offer. */
    fun bindInterfaceOf(capabilities: NetworkCapabilities): BindInterface? =
        NetworkRules.bindInterfaceOf(transportsOf(capabilities))

    /** Gives the transports of a network with no Android type. */
    fun transportsOf(capabilities: NetworkCapabilities): Set<LinkTransport> = buildSet {
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add(LinkTransport.Wifi)
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add(LinkTransport.Ethernet)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_USB)) add(LinkTransport.Usb)
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add(LinkTransport.Vpn)
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add(LinkTransport.Cellular)
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add(LinkTransport.Bluetooth)
        if (isEmpty()) add(LinkTransport.Other)
    }
}
