package org.superdro.core.connection.tcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.superdro.core.connection.tcp.TcpAddressRules.HostKind
import org.superdro.core.model.AdapterAddress

class TcpAddressRulesTest {

    @Test
    fun theHostCanBeAnIpv4AddressAnIpv6AddressOrAHostName() {
        assertEquals(HostKind.Ipv4, TcpAddressRules.hostKind("192.168.4.1"))
        assertEquals(HostKind.Ipv4, TcpAddressRules.hostKind("10.0.0.255"))
        assertEquals(HostKind.Ipv6, TcpAddressRules.hostKind("fe80::1"))
        assertEquals(HostKind.Ipv6, TcpAddressRules.hostKind("::1"))
        assertEquals(HostKind.Ipv6, TcpAddressRules.hostKind("2001:db8:0:0:0:0:2:1"))
        assertEquals(HostKind.Ipv6, TcpAddressRules.hostKind("[fe80::1%wlan0]"))
        assertEquals(HostKind.Ipv6, TcpAddressRules.hostKind("::ffff:192.168.4.1"))
        assertEquals(HostKind.HostName, TcpAddressRules.hostKind("esp32-dro.local"))
        assertEquals(HostKind.HostName, TcpAddressRules.hostKind("localhost"))
        assertEquals(HostKind.HostName, TcpAddressRules.hostKind("dro.example.com."))
    }

    @Test
    fun aTextThatIsNotAnAddressIsRefused() {
        listOf(
            "",
            "192.168.1.300",
            "1234",
            "-adapter.local",
            "adapter-.local",
            "a..b",
            "dro adapter",
            "fe80::1::2",
            "1:2:3:4:5:6:7:8:9",
            "1:2:3",
            "[fe80::1",
            "fe80::1]",
            "[dro.local]",
            "fe80::1%",
            "a".repeat(64) + ".local",
        ).forEach { text -> assertFalse(TcpAddressRules.isValidHost(text), text) }
        assertTrue(TcpAddressRules.isValidHost("a".repeat(63) + ".local"))
    }

    @Test
    fun thePortRangeIs1To65535() {
        assertFalse(TcpAddressRules.isValidPort(0))
        assertTrue(TcpAddressRules.isValidPort(1))
        assertTrue(TcpAddressRules.isValidPort(8080))
        assertTrue(TcpAddressRules.isValidPort(65535))
        assertFalse(TcpAddressRules.isValidPort(65536))
        assertFalse(TcpAddressRules.isValid(AdapterAddress.Tcp("192.168.4.1", 0)))
        assertTrue(TcpAddressRules.isValid(AdapterAddress.Tcp("192.168.4.1")))
    }

    @Test
    fun aTextWithNoPortGetsPort8080() {
        assertEquals(AdapterAddress.Tcp("192.168.4.1", 8080), TcpAddressRules.parse("192.168.4.1"))
        assertEquals(AdapterAddress.Tcp("esp32-dro.local", 8080), TcpAddressRules.parse(" esp32-dro.local "))
        assertEquals(AdapterAddress.Tcp("fe80::1:8080", 8080), TcpAddressRules.parse("fe80::1:8080"))
        assertEquals(AdapterAddress.Tcp("fe80::1", 8080), TcpAddressRules.parse("[fe80::1]"))
    }

    @Test
    fun aTextWithAPortKeepsThePort() {
        assertEquals(AdapterAddress.Tcp("192.168.4.1", 23), TcpAddressRules.parse("192.168.4.1:23"))
        assertEquals(AdapterAddress.Tcp("dro.local", 65535), TcpAddressRules.parse("dro.local:65535"))
        assertEquals(AdapterAddress.Tcp("::1", 9000), TcpAddressRules.parse("[::1]:9000"))
        assertNull(TcpAddressRules.parse("192.168.4.1:0"))
        assertNull(TcpAddressRules.parse("192.168.4.1:65536"))
        assertNull(TcpAddressRules.parse("192.168.4.1:"))
        assertNull(TcpAddressRules.parse("192.168.4.1:80a"))
        assertNull(TcpAddressRules.parse("[::1]9000"))
        assertNull(TcpAddressRules.parse("[dro.local]:80"))
        assertNull(TcpAddressRules.parse(""))
    }

    @Test
    fun theDeviceIdIsHostColonPort() {
        assertEquals("192.168.4.1:8080", TcpAddressRules.deviceId(AdapterAddress.Tcp("192.168.4.1")))
        assertEquals("dro.local:23", TcpAddressRules.deviceId(AdapterAddress.Tcp("dro.local", 23)))
        assertEquals("fe80::1:8080", TcpAddressRules.deviceId(AdapterAddress.Tcp("fe80::1")))
    }
}
