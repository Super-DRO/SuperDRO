package org.superdro.platform.desktop.di

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.superdro.core.connection.LinkCapability
import org.superdro.core.connection.LinkFailure
import org.superdro.core.connection.NetworkInterfaceInfo
import org.superdro.core.connection.selectedBy
import org.superdro.core.model.BindInterface

/** The network interfaces of the desktop for the data output and the TCP links. */
class DesktopNetworkTest {

    private val loopback = SystemInterface("lo", loopback = true, addresses = listOf("127.0.0.1", "0:0:0:0:0:0:0:1%lo"))
    private val wifi = SystemInterface("wlp2s0", wireless = true, addresses = listOf("192.168.1.20"))
    private val ethernet = SystemInterface("enp3s0", addresses = listOf("10.0.0.5"))
    private val vpn = SystemInterface("wg0", pointToPoint = true, addresses = listOf("10.8.0.2"))
    private val docker = SystemInterface("docker0", addresses = listOf("172.17.0.1"))

    @Test
    fun eachInterfaceGetsTheBindChoiceOfItsKind() {
        assertEquals(BindInterface.Localhost, DesktopNetworkRules.kindOf(loopback))
        assertEquals(BindInterface.WiFi, DesktopNetworkRules.kindOf(wifi))
        assertEquals(BindInterface.WiFi, DesktopNetworkRules.kindOf(SystemInterface("wlan0")))
        assertEquals(BindInterface.WiFi, DesktopNetworkRules.kindOf(SystemInterface("net3", displayName = "Intel(R) Wi-Fi 6 AX201 160MHz")))
        assertEquals(BindInterface.UsbEthernet, DesktopNetworkRules.kindOf(ethernet))
        assertEquals(BindInterface.UsbEthernet, DesktopNetworkRules.kindOf(SystemInterface("eth0")))
        assertEquals(BindInterface.UsbEthernet, DesktopNetworkRules.kindOf(SystemInterface("enx00e04c680001")))
        assertEquals(BindInterface.UsbEthernet, DesktopNetworkRules.kindOf(SystemInterface("net5", displayName = "Intel(R) Ethernet Connection I219-V")))
        assertEquals(BindInterface.Vpn, DesktopNetworkRules.kindOf(vpn))
        assertEquals(BindInterface.Vpn, DesktopNetworkRules.kindOf(SystemInterface("tun0")))
        assertEquals(BindInterface.Vpn, DesktopNetworkRules.kindOf(SystemInterface("net9", displayName = "WireGuard Tunnel")))
        assertNull(DesktopNetworkRules.kindOf(docker))
        assertNull(DesktopNetworkRules.kindOf(SystemInterface("virbr0")))
    }

    @Test
    fun theListHasTheInterfacesThatAreUpInTheOrderOfTheAndroidList() {
        val down = SystemInterface("wlan1", wireless = true, up = false, addresses = listOf("192.168.5.5"))
        val list = DesktopNetworkRules.interfaces(listOf(docker, ethernet, vpn, loopback, wifi, down))
        assertEquals(listOf("wlp2s0", "lo", "wg0", "enp3s0", "docker0"), list.map { info -> info.name })
        assertEquals(NetworkInterfaceInfo("wlp2s0", BindInterface.WiFi, listOf("192.168.1.20")), list.first())
        // "All" selects each interface with a kind.
        assertEquals(listOf("wlp2s0", "lo", "wg0", "enp3s0"), list.selectedBy(BindInterface.All).map { info -> info.name })
        assertEquals(listOf("enp3s0"), list.selectedBy(BindInterface.UsbEthernet).map { info -> info.name })
    }

    @Test
    fun aListWithNoLoopbackInterfaceGetsOne() {
        val list = DesktopNetworkRules.interfaces(listOf(wifi))
        assertEquals(NetworkInterfaceInfo("lo", BindInterface.Localhost, listOf("127.0.0.1", "::1")), list.last())
    }

    @Test
    fun theTcpLinksNeedAnInterfaceOtherThanTheLoopback() {
        assertEquals(LinkCapability.Available, DesktopNetworkRules.capability(listOf(loopback, wifi)))
        assertEquals(LinkCapability.Unavailable(LinkFailure.NetworkOff), DesktopNetworkRules.capability(listOf(loopback)))
        assertEquals(
            LinkCapability.Unavailable(LinkFailure.NetworkOff),
            DesktopNetworkRules.capability(listOf(loopback, wifi.copy(up = false), SystemInterface("enp3s0"))),
        )
    }

    @Test
    fun theInterfacesAreReadAgainAtEachInterval() = runTest {
        var system = listOf(loopback)
        val interfaces = DesktopNetworkInterfaces(
            scope = backgroundScope,
            intervalMs = 5_000L,
            read = { system },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        runCurrent()
        assertEquals(LinkCapability.Unavailable(LinkFailure.NetworkOff), interfaces.capability.value)
        system = listOf(loopback, wifi)
        advanceTimeBy(5_001L)
        runCurrent()
        assertEquals(LinkCapability.Available, interfaces.capability.value)
        assertEquals(listOf("wlp2s0", "lo"), interfaces.interfaces.value.map { info -> info.name })
    }

    @Test
    fun theSystemListHasALoopbackInterface() {
        // A real read of this computer: java.net.NetworkInterface gives the loopback interface.
        val system = DesktopNetworkRules.readSystemInterfaces()
        assertEquals(true, system.any { item -> item.loopback })
    }
}
