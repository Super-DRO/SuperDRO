package org.superdro.platform.android.di

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.superdro.core.connection.AttachedUsbDevice
import org.superdro.core.connection.CapabilityState
import org.superdro.core.connection.LinkCapability
import org.superdro.core.connection.LinkFailure
import org.superdro.core.model.AdapterAddress

/** The capability source combines the Bluetooth, network, and USB monitors. */
class AndroidCapabilitySourceTest {

    @Test
    fun theStateFollowsEachMonitor() = runTest {
        val bluetooth = MutableStateFlow<LinkCapability>(LinkCapability.Unavailable(LinkFailure.PermissionMissing))
        val network = MutableStateFlow<LinkCapability>(LinkCapability.Available)
        val usb = MutableStateFlow(emptyList<AttachedUsbDevice>())
        val source = AndroidCapabilitySource(bluetooth, network, usb, backgroundScope)
        val spp = AdapterAddress.BluetoothSpp("24:6F:28:A1:B2:C3")
        val usbAddress = AdapterAddress.UsbSerial(vendorId = 0x10C4, productId = 0xEA60, serial = null)

        // The first value is there at once, before a collector.
        assertEquals(CapabilityState(bluetooth.value, network.value, emptyList()), source.state.value)
        assertEquals(LinkCapability.Unavailable(LinkFailure.PermissionMissing), source.state.value.capabilityOf(spp))
        assertEquals(LinkCapability.Unavailable(LinkFailure.DeviceGone), source.state.value.capabilityOf(usbAddress))
    }

    @Test
    fun aChangeOfOneMonitorGivesANewState() = runTest(UnconfinedTestDispatcher()) {
        val bluetooth = MutableStateFlow<LinkCapability>(LinkCapability.Unavailable(LinkFailure.RadioOff))
        val network = MutableStateFlow<LinkCapability>(LinkCapability.Unavailable(LinkFailure.NetworkOff))
        val usb = MutableStateFlow(emptyList<AttachedUsbDevice>())
        val source = AndroidCapabilitySource(bluetooth, network, usb, backgroundScope)
        val spp = AdapterAddress.BluetoothSpp("24:6F:28:A1:B2:C3")
        val usbAddress = AdapterAddress.UsbSerial(vendorId = 0x10C4, productId = 0xEA60, serial = null)
        val tcp = AdapterAddress.Tcp("192.168.1.20")

        bluetooth.value = LinkCapability.Available
        assertEquals(LinkCapability.Available, source.state.value.capabilityOf(spp))

        usb.value = listOf(AttachedUsbDevice(0x10C4, 0xEA60, serial = null, permitted = false))
        assertEquals(LinkCapability.Unavailable(LinkFailure.PermissionMissing), source.state.value.capabilityOf(usbAddress))
        usb.value = listOf(AttachedUsbDevice(0x10C4, 0xEA60, serial = null, permitted = true))
        assertEquals(LinkCapability.Available, source.state.value.capabilityOf(usbAddress))

        assertEquals(LinkCapability.Unavailable(LinkFailure.NetworkOff), source.state.value.capabilityOf(tcp))
        network.value = LinkCapability.Available
        assertEquals(LinkCapability.Available, source.state.value.capabilityOf(tcp))
    }
}
