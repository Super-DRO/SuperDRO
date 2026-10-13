package org.superdro.platform.android.network

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import org.superdro.core.connection.ConnectionRequest
import org.superdro.core.connection.ConnectionRequestManager
import org.superdro.core.connection.LinkCapability
import org.superdro.core.connection.LinkFailure
import org.superdro.core.connection.NetworkInterfaceInfo
import org.superdro.core.connection.PermissionKind
import org.superdro.core.connection.selectedBy
import org.superdro.core.model.BindInterface

class NetworkRulesTest {

    @Test
    fun eachNetworkGetsTheBindChoiceOfItsTransport() {
        assertEquals(BindInterface.WiFi, NetworkRules.bindInterfaceOf(setOf(LinkTransport.Wifi)))
        assertEquals(BindInterface.Vpn, NetworkRules.bindInterfaceOf(setOf(LinkTransport.Vpn, LinkTransport.Wifi)))
        assertEquals(BindInterface.UsbEthernet, NetworkRules.bindInterfaceOf(setOf(LinkTransport.Ethernet)))
        assertEquals(BindInterface.UsbEthernet, NetworkRules.bindInterfaceOf(setOf(LinkTransport.Usb)))
        assertNull(NetworkRules.bindInterfaceOf(setOf(LinkTransport.Cellular)))
        assertNull(NetworkRules.bindInterfaceOf(setOf(LinkTransport.Bluetooth)))
        assertNull(NetworkRules.bindInterfaceOf(emptySet()))
    }

    @Test
    fun theInterfaceListHasTheAddressesAndTheLoopbackInterface() {
        val wifiV6 = InetAddress.getByName("2001:db8::20").hostAddress
        val networks = listOf(
            NetworkSnapshot("rmnet0", setOf(LinkTransport.Cellular), listOf("10.20.30.40")),
            NetworkSnapshot("tun0", setOf(LinkTransport.Vpn), listOf("10.8.0.2")),
            NetworkSnapshot("wlan0", setOf(LinkTransport.Wifi), listOf("192.168.1.20", wifiV6)),
            NetworkSnapshot("eth0", setOf(LinkTransport.Ethernet), listOf("192.168.2.5")),
        )
        val list = NetworkRules.interfaces(networks)
        assertEquals(
            listOf(
                NetworkInterfaceInfo("wlan0", BindInterface.WiFi, listOf("192.168.1.20", "2001:db8:0:0:0:0:0:20")),
                NetworkInterfaceInfo("lo", BindInterface.Localhost, listOf("127.0.0.1", "::1")),
                NetworkInterfaceInfo("tun0", BindInterface.Vpn, listOf("10.8.0.2")),
                NetworkInterfaceInfo("eth0", BindInterface.UsbEthernet, listOf("192.168.2.5")),
                NetworkInterfaceInfo("rmnet0", null, listOf("10.20.30.40")),
            ),
            list,
        )
        assertEquals(listOf("wlan0"), list.selectedBy(BindInterface.WiFi).map { info -> info.name })
        assertEquals(listOf("wlan0", "lo", "tun0", "eth0"), list.selectedBy(BindInterface.All).map { info -> info.name })
        assertEquals(listOf(NetworkInterfaceInfo("lo", BindInterface.Localhost, listOf("127.0.0.1", "::1"))), NetworkRules.interfaces(emptyList()))
    }

    @Test
    fun twoNetworksOnOneInterfaceGiveOneEntry() {
        val list = NetworkRules.interfaces(
            listOf(
                NetworkSnapshot("wlan0", setOf(LinkTransport.Other), listOf("192.168.1.20")),
                NetworkSnapshot("wlan0", setOf(LinkTransport.Wifi), listOf("192.168.1.20", "2001:db8::20")),
            ),
        )
        assertEquals(NetworkInterfaceInfo("wlan0", BindInterface.WiFi, listOf("192.168.1.20", "2001:db8::20")), list.first())
    }

    @Test
    fun aLinkLocalAddressWithNoScopeIdIsNotInTheList() {
        // Android gives the link-local address of LinkProperties with no scope: a bind fails with "Invalid argument".
        val list = NetworkRules.interfaces(
            listOf(NetworkSnapshot("wlan0", setOf(LinkTransport.Wifi), listOf("192.168.1.20", "fe80::7efd:1f05:798c:eba8", "fe80::2%wlan0"))),
        )
        assertEquals(NetworkInterfaceInfo("wlan0", BindInterface.WiFi, listOf("192.168.1.20", "fe80::2%wlan0")), list.first())
    }

    @Test
    fun theTcpCapabilityIsNetworkOffWithNoNetworkAndPermissionMissingWithNoGrant() {
        assertEquals(
            LinkCapability.Unavailable(LinkFailure.NetworkOff),
            NetworkRules.tcpCapability(hasNetwork = false, localNetworkNeeded = true, localNetworkGranted = false),
        )
        assertEquals(
            LinkCapability.Unavailable(LinkFailure.PermissionMissing),
            NetworkRules.tcpCapability(hasNetwork = true, localNetworkNeeded = true, localNetworkGranted = false),
        )
        assertEquals(LinkCapability.Available, NetworkRules.tcpCapability(hasNetwork = true, localNetworkNeeded = true, localNetworkGranted = true))
        assertEquals(LinkCapability.Available, NetworkRules.tcpCapability(hasNetwork = true, localNetworkNeeded = false, localNetworkGranted = false))
        assertFalse(NetworkRules.hasNetwork(emptyList()))
        assertFalse(NetworkRules.hasNetwork(listOf(NetworkSnapshot("lo", emptySet(), listOf("127.0.0.1")))))
        assertTrue(NetworkRules.hasNetwork(listOf(NetworkSnapshot("wlan0", setOf(LinkTransport.Wifi), emptyList()))))
    }

    @Test
    fun theLocalNetworkPermissionAppliesFromApi37() {
        assertFalse(NetworkRules.localNetworkPermissionNeeded(36))
        assertTrue(NetworkRules.localNetworkPermissionNeeded(37))
        assertEquals("android.permission.ACCESS_LOCAL_NETWORK", NetworkRules.LOCAL_NETWORK_PERMISSION)
        assertEquals(listOf("android.permission.ACCESS_LOCAL_NETWORK"), NetworkRules.manifestPermissions(PermissionKind.LocalNetwork, 37))
        assertEquals(emptyList(), NetworkRules.manifestPermissions(PermissionKind.LocalNetwork, 36))
        assertEquals(emptyList(), NetworkRules.manifestPermissions(PermissionKind.Camera, 37))
    }

    @Test
    fun theRequestGoesOnTheRequestBusOfTheApp() = runTest {
        val requests = ConnectionRequestManager()
        val received = async { requests.bus.first() }
        yield()
        requests.send(NetworkRules.localNetworkRequest)
        assertEquals(ConnectionRequest.RequestPermissions(setOf(PermissionKind.LocalNetwork)), received.await())
    }
}
