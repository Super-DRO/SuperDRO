package org.superdro.platform.desktop.di

import com.toxicbakery.logging.Arbor
import java.io.File
import java.net.NetworkInterface
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.superdro.core.connection.BindAddresses
import org.superdro.core.connection.LinkCapability
import org.superdro.core.connection.LinkFailure
import org.superdro.core.connection.NetworkInterfaceInfo
import org.superdro.core.connection.NetworkInterfaces
import org.superdro.core.model.BindInterface

/**
 * One network interface of the system at one time, with no `java.net` type, so that a unit test
 * can make one.
 *
 * @property wireless True for a Wi-Fi interface. On Linux the folder `/sys/class/net/<name>/wireless` tells it.
 * @property addresses The IPv4 and IPv6 addresses as text.
 */
data class SystemInterface(
    val name: String,
    val displayName: String = name,
    val up: Boolean = true,
    val loopback: Boolean = false,
    val pointToPoint: Boolean = false,
    val wireless: Boolean = false,
    val addresses: List<String> = emptyList(),
)

/** The rules of the desktop network interfaces and of the network capability. */
object DesktopNetworkRules {

    /** The name of the loopback interface when the system gives none. */
    const val LOOPBACK_NAME: String = "lo"

    /** The addresses of the loopback interface when the system gives none. */
    val LOOPBACK_ADDRESSES: List<String> = listOf("127.0.0.1", "::1")

    private val VPN_PREFIXES = listOf("tun", "tap", "wg", "ppp", "utun", "ipsec", "tailscale", "zt")
    private val VPN_WORDS = listOf("vpn", "wireguard", "tap-windows", "tailscale", "zerotier")
    private val WIFI_WORDS = listOf("wi-fi", "wifi", "wireless", "802.11", "wlan")
    private val ETHERNET_PREFIXES = listOf("eth", "en", "usb")

    /**
     * Gives the bind choice of the data output for [system]: the loopback interface is
     * [BindInterface.Localhost]; a point-to-point interface or a VPN name is [BindInterface.Vpn]; a
     * wireless interface or a Wi-Fi name is [BindInterface.WiFi]; an Ethernet name is
     * [BindInterface.UsbEthernet]. Each other interface (for example a container bridge) gives
     * null: the data output does not offer it.
     *
     * The names come from the system: `wlan0`, `wlp2s0`, `eth0`, `enp3s0`, `tun0`, `wg0`, and the
     * Windows display names such as "Intel(R) Wi-Fi 6 AX201". macOS gives `en0` both for Wi-Fi and for
     * Ethernet, so `en0` is [BindInterface.UsbEthernet] there.
     */
    fun kindOf(system: SystemInterface): BindInterface? {
        val name = system.name.lowercase()
        val display = system.displayName.lowercase()
        return when {
            system.loopback -> BindInterface.Localhost
            system.pointToPoint || VPN_PREFIXES.any(name::startsWith) || VPN_WORDS.any(display::contains) -> BindInterface.Vpn
            system.wireless || name.startsWith("wl") || WIFI_WORDS.any(display::contains) -> BindInterface.WiFi
            ETHERNET_PREFIXES.any(name::startsWith) || "ethernet" in display -> BindInterface.UsbEthernet
            else -> null
        }
    }

    /**
     * Gives the interface list of [NetworkInterfaces]: each interface that is up, with its
     * addresses, and the loopback interface. The order: Wi-Fi, localhost, VPN, USB Ethernet, then
     * the interfaces with no kind; then the name. The Android list has the same order. Java gives a
     * link-local address with its scope ID; a link-local address with no scope ID stays out
     * ([BindAddresses]).
     */
    fun interfaces(system: List<SystemInterface>): List<NetworkInterfaceInfo> {
        val up = system.filter { item -> item.up }
            .map { item -> NetworkInterfaceInfo(item.name, kindOf(item), BindAddresses.bindable(item.addresses).distinct()) }
        val withLoopback = if (up.any { info -> info.kind == BindInterface.Localhost }) {
            up
        } else {
            up + NetworkInterfaceInfo(LOOPBACK_NAME, BindInterface.Localhost, LOOPBACK_ADDRESSES)
        }
        return withLoopback.sortedWith(compareBy({ info -> info.kind?.ordinal ?: Int.MAX_VALUE }, { info -> info.name }))
    }

    /**
     * Gives the network capability of the TCP links: available when one or more interfaces
     * that are up, and that are not the loopback interface, have an address. Else
     * [LinkFailure.NetworkOff]: a TCP session waits in `WaitingForDevice`.
     */
    fun capability(system: List<SystemInterface>): LinkCapability =
        if (system.any { item -> item.up && !item.loopback && item.addresses.isNotEmpty() }) {
            LinkCapability.Available
        } else {
            LinkCapability.Unavailable(LinkFailure.NetworkOff)
        }

    /** Reads the interfaces of the system with `java.net.NetworkInterface`. A failure gives an empty list. */
    fun readSystemInterfaces(sysClassNet: File = File("/sys/class/net")): List<SystemInterface> =
        try {
            NetworkInterface.networkInterfaces().toList().mapNotNull { item ->
                runCatching {
                    SystemInterface(
                        name = item.name,
                        displayName = item.displayName ?: item.name,
                        up = item.isUp,
                        loopback = item.isLoopback,
                        pointToPoint = item.isPointToPoint,
                        wireless = File(File(sysClassNet, item.name), "wireless").isDirectory,
                        addresses = item.inetAddresses().toList().mapNotNull { address -> address.hostAddress },
                    )
                }.getOrNull()
            }
        } catch (error: Exception) {
            log.w(error) { "The network interface list failed" }
            emptyList()
        }

    private val log = Arbor.tag("DesktopNetwork")
}

/**
 * The desktop [NetworkInterfaces] and the network capability of the
 * TCP links. `java.net.NetworkInterface` has no event for a change, so the class reads the
 * list again each [intervalMs].
 *
 * The desktop has no local network permission: the list needs no request.
 *
 * @param read Reads the interfaces of the system. A test gives a fake.
 */
class DesktopNetworkInterfaces(
    scope: CoroutineScope,
    private val intervalMs: Long = POLL_INTERVAL_MS,
    private val read: () -> List<SystemInterface> = { DesktopNetworkRules.readSystemInterfaces() },
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : NetworkInterfaces {

    private val current = MutableStateFlow(read())

    override val interfaces: StateFlow<List<NetworkInterfaceInfo>> =
        current.map(DesktopNetworkRules::interfaces).stateIn(scope, SharingStarted.Eagerly, DesktopNetworkRules.interfaces(current.value))

    /** The capability of the TCP links. */
    val capability: StateFlow<LinkCapability> =
        current.map(DesktopNetworkRules::capability).stateIn(scope, SharingStarted.Eagerly, DesktopNetworkRules.capability(current.value))

    init {
        scope.launch(dispatcher) {
            while (isActive) {
                delay(intervalMs)
                current.value = read()
            }
        }
    }

    companion object {
        /** The time between two reads of the interface list. */
        const val POLL_INTERVAL_MS: Long = 5000L
    }
}
