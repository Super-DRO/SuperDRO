package org.superdro.core.connection

import kotlin.test.Test
import kotlin.test.assertEquals
import org.superdro.core.model.AdapterAddress

/** The capability rules of a session in `WaitingForDevice`. */
class CapabilityStateTest {

    private val usb = AdapterAddress.UsbSerial(vendorId = 0x10C4, productId = 0xEA60, serial = "A1")

    @Test
    fun bluetoothAndBleUseTheBluetoothState() {
        val state = CapabilityState(bluetooth = LinkCapability.Unavailable(LinkFailure.RadioOff))
        assertEquals(LinkCapability.Unavailable(LinkFailure.RadioOff), state.capabilityOf(AdapterAddress.BluetoothSpp("00:11:22:33:44:55")))
        assertEquals(LinkCapability.Unavailable(LinkFailure.RadioOff), state.capabilityOf(AdapterAddress.Ble("id")))
    }

    @Test
    fun tcpUsesTheNetworkState() {
        val state = CapabilityState(network = LinkCapability.Unavailable(LinkFailure.NetworkOff))
        assertEquals(LinkCapability.Unavailable(LinkFailure.NetworkOff), state.capabilityOf(AdapterAddress.Tcp("10.0.0.5")))
    }

    @Test
    fun aUsbAdapterNeedsTheAttachedDeviceAndItsPermission() {
        assertEquals(LinkCapability.Unavailable(LinkFailure.DeviceGone), CapabilityState().capabilityOf(usb))
        val notPermitted = CapabilityState(attachedUsb = listOf(AttachedUsbDevice(0x10C4, 0xEA60, serial = "A1")))
        assertEquals(LinkCapability.Unavailable(LinkFailure.PermissionMissing), notPermitted.capabilityOf(usb))
        val permitted = CapabilityState(attachedUsb = listOf(AttachedUsbDevice(0x10C4, 0xEA60, serial = "A1", permitted = true)))
        assertEquals(LinkCapability.Available, permitted.capabilityOf(usb))
    }

    @Test
    fun aUsbDeviceWithADifferentSerialIsNotTheAdapter() {
        val other = CapabilityState(attachedUsb = listOf(AttachedUsbDevice(0x10C4, 0xEA60, serial = "B2", permitted = true)))
        assertEquals(LinkCapability.Unavailable(LinkFailure.DeviceGone), other.capabilityOf(usb))
    }

    @Test
    fun theFakeAdapterIsAlwaysAvailable() {
        val state = CapabilityState(bluetooth = LinkCapability.Unavailable(LinkFailure.RadioOff))
        assertEquals(LinkCapability.Available, state.capabilityOf(AdapterAddress.Fake("demo")))
    }
}
