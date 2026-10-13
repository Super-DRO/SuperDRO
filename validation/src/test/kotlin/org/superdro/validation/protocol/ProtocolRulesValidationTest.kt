package org.superdro.validation.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.superdro.core.connection.NetworkInterfaceInfo
import org.superdro.core.connection.NetworkInterfaces
import org.superdro.core.connection.ble.NordicUartService
import org.superdro.core.connection.selectedBy
import org.superdro.core.connection.tcp.TcpTransport
import org.superdro.core.fakeadapter.FakeAdapter
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.BindInterface
import org.superdro.core.model.MachineProfile

/**
 * The rules for the modules of the protocol and the connection: the network contract in
 * `core:connection`, the package names of the modules with a hyphen, and the two USB baud rate
 * fields.
 */
class ProtocolRulesValidationTest {

    // `NetworkInterfaces` is in `core:connection`.
    @Test
    fun the_network_contract_is_in_core_connection() {
        assertEquals("org.superdro.core.connection", NetworkInterfaces::class.java.packageName)
        val wifi = NetworkInterfaceInfo("wlan0", BindInterface.WiFi, listOf("192.168.1.20"))
        val cellular = NetworkInterfaceInfo("rmnet0", null, listOf("10.0.0.2"))
        assertEquals(listOf(wifi), listOf(wifi, cellular).selectedBy(BindInterface.All))
    }

    // The package of a module with a hyphen has no hyphen.
    @Test
    fun package_names_of_the_modules_with_a_hyphen() {
        assertEquals("org.superdro.core.connection.tcp", TcpTransport::class.java.packageName)
        assertEquals("org.superdro.core.connection.ble", NordicUartService::class.java.packageName)
        assertEquals("org.superdro.core.fakeadapter", FakeAdapter::class.java.packageName)
    }

    // `MachineProfile.usbBaudRate` is the one source of the rate.
    @Test
    fun the_two_usb_baud_rate_fields_have_the_same_default() {
        assertEquals(MachineProfile().usbBaudRate, AdapterAddress.UsbSerial().baudRate)
        assertTrue(MachineProfile().usbBaudRate in AdapterAddress.UsbSerial.BAUD_RATES)
    }
}
