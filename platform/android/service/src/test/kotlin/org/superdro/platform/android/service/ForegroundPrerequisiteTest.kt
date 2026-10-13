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
    ) = ForegroundPrerequisite.isMet(sdkInt, bluetooth, usb, changeNetwork, transports)

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
    fun thePermissionNamesAreTheManifestNames() {
        assertEquals("android.permission.CHANGE_NETWORK_STATE", ForegroundPrerequisite.CHANGE_NETWORK_STATE)
        assertEquals("android.permission.BLUETOOTH_CONNECT", ForegroundPrerequisite.BLUETOOTH_CONNECT)
    }
}
