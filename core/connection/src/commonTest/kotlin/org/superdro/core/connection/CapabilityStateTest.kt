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
    fun aDeviceWithNoPermissionDoesNotHideThePermittedAdapter() {
        val noSerial = usb.copy(serial = null)
        val otherFirst = CapabilityState(
            attachedUsb = listOf(AttachedUsbDevice(0x10C4, 0xEA60), AttachedUsbDevice(0x10C4, 0xEA60, serial = "A1", permitted = true)),
        )
        assertEquals(LinkCapability.Available, otherFirst.capabilityOf(usb))
        assertEquals(LinkCapability.Available, otherFirst.capabilityOf(noSerial))
        val permittedFirst = CapabilityState(attachedUsb = otherFirst.attachedUsb.reversed())
        assertEquals(LinkCapability.Available, permittedFirst.capabilityOf(usb))
        assertEquals(LinkCapability.Available, permittedFirst.capabilityOf(noSerial))
    }

    @Test
    fun aPermittedDeviceWithADifferentSerialLeavesOnlyTheDeviceWithNoPermission() {
        val state = CapabilityState(
            attachedUsb = listOf(AttachedUsbDevice(0x10C4, 0xEA60, serial = "B2", permitted = true), AttachedUsbDevice(0x10C4, 0xEA60)),
        )
        assertEquals(LinkCapability.Unavailable(LinkFailure.PermissionMissing), state.capabilityOf(usb))
        // An address with no serial number can use the permitted device.
        assertEquals(LinkCapability.Available, state.capabilityOf(usb.copy(serial = null)))
    }

    @Test
    fun aPermittedDeviceWithNoSerialIsNotAnAdapterWithASerial() {
        val state = CapabilityState(attachedUsb = listOf(AttachedUsbDevice(0x10C4, 0xEA60, serial = null, permitted = true)))
        assertEquals(LinkCapability.Unavailable(LinkFailure.DeviceGone), state.capabilityOf(usb))
        assertEquals(LinkCapability.Available, state.capabilityOf(usb.copy(serial = null)))
    }

    @Test
    fun aDeviceWithOtherIdsIsNotTheAdapter() {
        val state = CapabilityState(attachedUsb = listOf(AttachedUsbDevice(0x0403, 0x6001, serial = "A1", permitted = true)))
        assertEquals(LinkCapability.Unavailable(LinkFailure.DeviceGone), state.capabilityOf(usb))
    }

    @Test
    fun theSelectionPrefersAPermittedDeviceAndElseKeepsTheOrder() {
        val noSerial = usb.copy(serial = null)
        val devices = listOf(
            "other ids" to AttachedUsbDevice(0x0403, 0x6001, permitted = true),
            "first" to AttachedUsbDevice(0x10C4, 0xEA60),
            "second" to AttachedUsbDevice(0x10C4, 0xEA60),
            "permitted" to AttachedUsbDevice(0x10C4, 0xEA60, serial = "A1", permitted = true),
        )
        assertEquals("permitted", selectUsbDevice(noSerial, devices) { it.second }?.first)
        assertEquals("permitted", selectUsbDevice(usb, devices) { it.second }?.first)
        assertEquals("first", selectUsbDevice(usb, devices.dropLast(1)) { it.second }?.first)
        assertEquals("first", selectUsbDevice(usb.copy(serial = "C3"), devices) { it.second }?.first)
        assertEquals(null, selectUsbDevice(usb, devices.take(1)) { it.second })
    }

    @Test
    fun theFakeAdapterIsAlwaysAvailable() {
        val state = CapabilityState(bluetooth = LinkCapability.Unavailable(LinkFailure.RadioOff))
        assertEquals(LinkCapability.Available, state.capabilityOf(AdapterAddress.Fake("demo")))
    }
}
