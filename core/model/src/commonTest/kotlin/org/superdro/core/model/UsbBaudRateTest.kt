package org.superdro.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** The one source of truth of the USB baud rate. */
class UsbBaudRateTest {

    @Test
    fun theUsbLinkGetsTheRateOfTheProfileSetting() {
        val profile = MachineProfile(usbBaudRate = 9_600, adapter = AdapterAddress.UsbSerial(0x10C4, 0xEA60, "A1", baudRate = 115_200))
        val synced = profile.withUsbBaudRateOnLink()
        assertEquals(AdapterAddress.UsbSerial(0x10C4, 0xEA60, "A1", baudRate = 9_600), synced.adapter)
        assertEquals(9_600, synced.usbBaudRate)
        // The same rate, or a link of a different type, gives the same profile.
        assertSame(synced, synced.withUsbBaudRateOnLink())
        val tcp = MachineProfile(usbBaudRate = 9_600, adapter = AdapterAddress.Tcp("192.168.4.1"))
        assertSame(tcp, tcp.withUsbBaudRateOnLink())
        val none = MachineProfile(usbBaudRate = 57_600)
        assertSame(none, none.withUsbBaudRateOnLink())
    }

    @Test
    fun aNewProfileHasTheDefault115200OnTheSettingAndOnANewUsbLink() {
        assertEquals(115_200, MachineProfile().usbBaudRate)
        assertEquals(115_200, AdapterAddress.UsbSerial().baudRate)
        val linked = MachineProfile(adapter = AdapterAddress.UsbSerial(1, 2, null)).withUsbBaudRateOnLink()
        assertEquals(115_200, (linked.adapter as AdapterAddress.UsbSerial).baudRate)
    }
}
