package org.superdro.core.connection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.superdro.core.model.BindInterface

class NetworkInterfacesTest {

    private val interfaces = listOf(
        NetworkInterfaceInfo("lo", BindInterface.Localhost, listOf("127.0.0.1", "::1")),
        NetworkInterfaceInfo("wlan0", BindInterface.WiFi, listOf("192.168.1.20")),
        NetworkInterfaceInfo("tun0", BindInterface.Vpn, listOf("10.8.0.2")),
        NetworkInterfaceInfo("rmnet0", null, listOf("100.64.0.9")),
    )

    @Test
    fun theBindChoiceSelectsTheInterfacesOfItsKind() {
        assertEquals(listOf("wlan0"), interfaces.selectedBy(BindInterface.WiFi).map { info -> info.name })
        assertEquals(listOf("tun0"), interfaces.selectedBy(BindInterface.Vpn).map { info -> info.name })
        assertEquals(emptyList(), interfaces.selectedBy(BindInterface.UsbEthernet))
    }

    @Test
    fun allSelectsEachInterfaceThatTheOutputOffers() {
        assertEquals(listOf("lo", "wlan0", "tun0"), interfaces.selectedBy(BindInterface.All).map { info -> info.name })
    }

    @Test
    fun anAddressCanTakeABindExceptALinkLocalAddressWithNoScopeId() {
        assertTrue(BindAddresses.isLinkLocal("fe80::7efd:1f05:798c:eba8"))
        assertTrue(BindAddresses.isLinkLocal("FE80::1"))
        assertTrue(BindAddresses.isLinkLocal("febf::1"))
        assertFalse(BindAddresses.isLinkLocal("fec0::4e1d:8faf:58d:cdea"))
        assertFalse(BindAddresses.isLinkLocal("10.0.2.20"))
        assertFalse(BindAddresses.isLinkLocal("::1"))
        assertEquals(
            listOf("10.0.2.20", "fe80::1%wlan0", "2001:db8::20"),
            BindAddresses.bindable(listOf("10.0.2.20", "fe80::1", "fe80::1%wlan0", "2001:db8::20", "fe80:0:0:0:abcd:0:0:1")),
        )
    }
}
