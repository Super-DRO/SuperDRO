package org.superdro.platform.android.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.superdro.core.model.TransportKind

/** The prerequisite of the `connectedDevice` service type. */
class ForegroundPrerequisiteTest {

    private fun met(
        sdkInt: Int = 36,
        bluetooth: Boolean = false,
        usb: Boolean = false,
        changeNetwork: Boolean = true,
        transports: List<TransportKind> = listOf(TransportKind.BluetoothSpp),
        changeWifiMulticast: Boolean = true,
        mcpServer: Boolean = false,
    ) = ForegroundPrerequisite.isMet(sdkInt, bluetooth, usb, changeNetwork, transports, changeWifiMulticast, mcpServer)

    @Test
    fun beforeApi34ThereIsNoPrerequisite() {
        assertEquals(34, ForegroundPrerequisite.PREREQUISITE_API)
        assertTrue(met(sdkInt = 33, changeNetwork = false))
        assertTrue(met(sdkInt = 24, changeNetwork = false, transports = listOf(TransportKind.Fake)))
        assertFalse(met(sdkInt = 34, changeNetwork = false))
    }

    @Test
    fun aGrantedBluetoothConnectOrAPermittedUsbDeviceMeetsIt() {
        assertTrue(met(bluetooth = true))
        assertTrue(met(usb = true, transports = listOf(TransportKind.UsbSerial)))
        assertFalse(met(transports = listOf(TransportKind.BluetoothSpp, TransportKind.UsbSerial)))
    }

    @Test
    fun aTcpOnlyMachineMeetsItWithTheDeclaredChangeNetworkState() {
        assertTrue(met(transports = listOf(TransportKind.Tcp)))
        assertTrue(met(transports = listOf(TransportKind.Fake, TransportKind.Tcp)))
        assertFalse(met(changeNetwork = false, transports = listOf(TransportKind.Tcp)))
        // A demo session with no Bluetooth permission does not start the service.
        assertFalse(met(transports = listOf(TransportKind.Fake)))
    }

    @Test
    fun theMcpServerMeetsItWithTheDeclaredChangeWifiMulticastState() {
        // The server with no machine: only the multicast permission meets the prerequisite.
        assertTrue(met(transports = emptyList(), mcpServer = true))
        assertFalse(met(transports = emptyList(), mcpServer = true, changeWifiMulticast = false))
        assertFalse(met(transports = emptyList(), mcpServer = false))
        // The server with a Bluetooth machine and no Bluetooth permission.
        assertTrue(met(mcpServer = true))
        assertFalse(met(mcpServer = true, changeWifiMulticast = false))
        // The multicast permission alone does not meet it for a machine.
        assertFalse(met(transports = listOf(TransportKind.Fake)))
        assertTrue(met(sdkInt = 33, transports = emptyList(), mcpServer = true, changeWifiMulticast = false))
    }

    @Test
    fun thePermissionNamesAreTheManifestNames() {
        assertEquals("android.permission.CHANGE_NETWORK_STATE", ForegroundPrerequisite.CHANGE_NETWORK_STATE)
        assertEquals("android.permission.BLUETOOTH_CONNECT", ForegroundPrerequisite.BLUETOOTH_CONNECT)
        assertEquals("android.permission.CHANGE_WIFI_MULTICAST_STATE", ForegroundPrerequisite.CHANGE_WIFI_MULTICAST_STATE)
    }
}
