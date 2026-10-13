package org.superdro.platform.android.usb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.superdro.core.connection.AttachedUsbDevice
import org.superdro.core.connection.CapabilityState
import org.superdro.core.connection.DiscoveredAdapter
import org.superdro.core.connection.LinkCapability
import org.superdro.core.connection.LinkFailure
import org.superdro.core.model.AdapterAddress

class UsbRulesTest {

    @Test
    fun theDeviceNameHasFourForms() {
        assertEquals("Silicon Labs CP2102 USB to UART Bridge Controller", UsbRules.deviceName("Silicon Labs", "CP2102 USB to UART Bridge Controller"))
        assertEquals("FTDI USB UART Device", UsbRules.deviceName("FTDI", null))
        assertEquals("Generic USB Serial", UsbRules.deviceName(null, "USB Serial"))
        assertEquals("Generic USB UART Device", UsbRules.deviceName(null, null))
        assertEquals("Generic USB UART Device", UsbRules.deviceName(" ", ""))
        assertEquals("WCH USB UART Device", UsbRules.deviceName(" WCH ", "  "))
    }

    @Test
    fun theLineSettingsAre8N1WithTheStoredBaudRateAndNoDtrOrRts() {
        val stored9600 = UsbLineSettings.of(AdapterAddress.UsbSerial(0x10C4, 0xEA60, baudRate = 9600))
        assertEquals(9600, stored9600.baudRate)
        assertEquals(UsbLineSettings(baudRate = 9600, dataBits = 8, stopBits = 1, parity = 0, dtr = false, rts = false), stored9600)
        assertEquals(115200, UsbLineSettings.of(AdapterAddress.UsbSerial(0x10C4, 0xEA60)).baudRate)
        assertEquals(115200, UsbSerialDefaults.BAUD_RATE)
        assertEquals(19200, UsbLineSettings.of(AdapterAddress.UsbSerial(0x1A86, 0x7523, baudRate = 19200)).baudRate)
    }

    @Test
    fun theReadAndWriteLimits() {
        assertEquals(200, UsbSerialDefaults.READ_TIMEOUT_MS)
        assertTrue(UsbSerialDefaults.READ_BUFFER_BYTES <= UsbSerialDefaults.MAX_READ_BUFFER_BYTES)
        assertEquals(16384, UsbSerialDefaults.MAX_READ_BUFFER_BYTES)
        assertEquals(1000, UsbSerialDefaults.WRITE_TIMEOUT_MS)
    }

    @Test
    fun thePermissionIntentIsMutableAndHasTheAppPackage() {
        assertEquals("org.superdro.USB_PERMISSION", UsbRules.permissionAction("org.superdro"))
        assertEquals("org.superdro.adapterprobe.USB_PERMISSION", UsbRules.permissionAction("org.superdro.adapterprobe"))
        assertEquals(0x02000000, UsbRules.permissionPendingIntentFlags(31))
        assertEquals(0x02000000, UsbRules.permissionPendingIntentFlags(34))
        assertEquals(0, UsbRules.permissionPendingIntentFlags(30))
        assertEquals(0, UsbRules.permissionPendingIntentFlags(24))
        assertTrue(UsbRules.isGranted(extraGranted = true, hasPermission = false))
        assertTrue(UsbRules.isGranted(extraGranted = false, hasPermission = true))
        assertFalse(UsbRules.isGranted(extraGranted = false, hasPermission = false))
    }

    @Test
    fun theIdentityUsesTheSerialNumberOnlyAfterThePermission() {
        var reads = 0
        val noPermission = UsbIdentity.of(0x10C4, 0xEA60, 0, permitted = false, productName = "CP2102") { reads++; "0001" }
        assertEquals(0, reads)
        assertNull(noPermission.serial)
        assertEquals("CP2102", noPermission.name)
        val permitted = UsbIdentity.of(0x10C4, 0xEA60, 0, permitted = true, productName = "CP2102") { reads++; "0001" }
        assertEquals(1, reads)
        assertEquals("0001", permitted.name)
        assertEquals("10C4:EA60 #0 0001", permitted.key)

        assertTrue(permitted.matches(AdapterAddress.UsbSerial(0x10C4, 0xEA60, serial = "0001")))
        assertTrue(permitted.matches(AdapterAddress.UsbSerial(0x10C4, 0xEA60, serial = null)))
        assertFalse(permitted.matches(AdapterAddress.UsbSerial(0x10C4, 0xEA60, serial = "0002")))
        assertFalse(permitted.matches(AdapterAddress.UsbSerial(0x10C4, 0xEA60, serial = "0001", portIndex = 1)))
        assertTrue(noPermission.matches(AdapterAddress.UsbSerial(0x10C4, 0xEA60, serial = "0002")))
    }

    @Test
    fun theAddDeviceListHasOneRowForEachPort() {
        val devices = listOf(
            UsbDeviceInfo(0x10C4, 0xEA60, "Silicon Labs", "CP2102", serial = "0001", permitted = true),
            UsbDeviceInfo(0x0403, 0x6010, "FTDI", "Dual RS232", serial = "FT1234", permitted = false, portCount = 2),
        )
        assertEquals(
            listOf(
                DiscoveredAdapter(AdapterAddress.UsbSerial(0x10C4, 0xEA60, serial = "0001"), "Silicon Labs CP2102", "10C4:EA60"),
                DiscoveredAdapter(AdapterAddress.UsbSerial(0x0403, 0x6010, serial = null, portIndex = 0), "FTDI Dual RS232", "0403:6010 #1", needsPermission = true),
                DiscoveredAdapter(AdapterAddress.UsbSerial(0x0403, 0x6010, serial = null, portIndex = 1), "FTDI Dual RS232", "0403:6010 #2", needsPermission = true),
            ),
            UsbRules.discoveredAdapters(devices),
        )
        assertTrue(UsbRules.discoveredAdapters(devices).all { adapter -> (adapter.address as AdapterAddress.UsbSerial).baudRate == 115200 })
    }

    @Test
    fun aDetachedDeviceIsDeviceGoneAndTheAttachOfTheSameDeviceMakesItAvailable() {
        val address = AdapterAddress.UsbSerial(0x10C4, 0xEA60, serial = "0001")
        val attached = UsbRules.attachedDevices(listOf(UsbDeviceInfo(0x10C4, 0xEA60, null, null, serial = "0001", permitted = true)))
        assertEquals(listOf(AttachedUsbDevice(0x10C4, 0xEA60, "0001", permitted = true)), attached)
        assertEquals(LinkCapability.Available, CapabilityState(attachedUsb = attached).capabilityOf(address))
        assertEquals(LinkCapability.Unavailable(LinkFailure.DeviceGone), CapabilityState(attachedUsb = emptyList()).capabilityOf(address))
        val again = UsbRules.attachedDevices(listOf(UsbDeviceInfo(0x10C4, 0xEA60, null, null, serial = "0001", permitted = false)))
        assertEquals(listOf(AttachedUsbDevice(0x10C4, 0xEA60, null, permitted = false)), again)
        assertEquals(LinkCapability.Unavailable(LinkFailure.PermissionMissing), CapabilityState(attachedUsb = again).capabilityOf(address))
    }
}
