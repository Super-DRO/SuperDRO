package org.superdro.core.connection

import kotlinx.coroutines.flow.StateFlow
import org.superdro.core.model.BindInterface

/**
 * Gives the network interfaces of the tablet with their addresses. The data output uses the list to
 * select its bind address. The Android implementation is in `platform:android:network`. It also
 * asks for the local network permission where the Android version needs it, with
 * `ConnectionRequest.RequestPermissions(setOf(PermissionKind.LocalNetwork))`.
 *
 * The contract is in `core:connection`, so that `platform:android:network` and the adapter probe
 * app do not depend on `core:data-output`.
 */
interface NetworkInterfaces {

    /** The interfaces that are up now. The value changes when an interface goes up or down. */
    val interfaces: StateFlow<List<NetworkInterfaceInfo>>
}

/**
 * One network interface.
 *
 * @property name The system name, for example `wlan0`.
 * @property kind The bind choice of the data output that this interface gives, or null for an
 *   interface that the data output does not offer (for example the cellular network).
 *   [BindInterface.All] is never the kind of one interface.
 * @property addresses The IPv4 and IPv6 addresses as text, for example `192.168.1.20`. Each
 *   address can take a bind: an IPv6 link-local address (`fe80::/10`) has its scope ID, for
 *   example `fe80::1%wlan0`, or the implementation leaves it out ([BindAddresses]).
 */
data class NetworkInterfaceInfo(
    val name: String,
    val kind: BindInterface?,
    val addresses: List<String>,
)

/**
 * Gives the interfaces that [bind] selects. [BindInterface.All] selects each interface with a
 * kind. Each other value selects the interfaces of that kind.
 */
fun List<NetworkInterfaceInfo>.selectedBy(bind: BindInterface): List<NetworkInterfaceInfo> = when (bind) {
    BindInterface.All -> filter { info -> info.kind != null }
    else -> filter { info -> info.kind == bind }
}

/**
 * The address rule of the network interfaces. A bind to an IPv6 link-local address (`fe80::/10`)
 * needs the scope ID of its interface (`fe80::1%wlan0`). With no scope ID, the bind fails with
 * "Invalid argument" (a check on the emulator with Android 15). Thus an implementation of
 * [NetworkInterfaces] gives such an address only with its scope ID.
 */
object BindAddresses {

    /** True for an IPv6 address in `fe80::/10` (the first group is `fe80` to `febf`). */
    fun isLinkLocal(address: String): Boolean {
        if (':' !in address) return false
        val first = address.trim().removePrefix("[").substringBefore(':').toIntOrNull(16) ?: return false
        return first in 0xFE80..0xFEBF
    }

    /** True when a bind to [address] can work: each address except a link-local address with no scope ID. */
    fun isBindable(address: String): Boolean = !isLinkLocal(address) || '%' in address

    /** Gives [addresses] with each address that [isBindable] refuses left out. */
    fun bindable(addresses: List<String>): List<String> = addresses.filter(::isBindable)
}
