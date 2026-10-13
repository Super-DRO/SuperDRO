package org.superdro.platform.desktop.serial

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.superdro.core.connection.AttachedUsbDevice
import org.superdro.core.connection.CapabilityState
import org.superdro.core.connection.DiscoveredAdapter
import org.superdro.core.connection.LinkCapability
import org.superdro.core.connection.LinkFailure
import org.superdro.core.model.AdapterAddress

/** The rules of the desktop serial ports. */
class SerialPortRulesTest {

    @Test
    fun eachPortHasTheKindOfItsSystem() {
        assertEquals(SerialPortKind.Usb, SerialPortRules.kindOf(Ports.cp2102))
        assertEquals(SerialPortKind.Usb, SerialPortRules.kindOf(Ports.ch340))
        assertEquals(SerialPortKind.Bluetooth, SerialPortRules.kindOf(Ports.rfcomm))
        // An RFCOMM port with no address in sysfs is still a Bluetooth port.
        assertEquals(SerialPortKind.Bluetooth, SerialPortRules.kindOf(Ports.rfcomm.copy(bluetoothMac = null)))
        assertEquals(SerialPortKind.Bluetooth, SerialPortRules.kindOf(Ports.windowsBluetooth))
        assertEquals(SerialPortKind.Bluetooth, SerialPortRules.kindOf(Ports.macBluetooth))
        assertEquals(SerialPortKind.Other, SerialPortRules.kindOf(Ports.macIncoming))
        assertEquals(SerialPortKind.Other, SerialPortRules.kindOf(Ports.physical))
        assertEquals(SerialPortKind.Other, SerialPortRules.kindOf(SerialPortInfo(id = "/dev/pts/3", name = "pts/3", description = "Pseudo-Terminal Device")))
    }

    @Test
    fun theUsbDeviceNameHasFourForms() {
        assertEquals("Silicon Labs CP2102 USB to UART Bridge Controller", SerialPortRules.usbDeviceName("Silicon Labs", "CP2102 USB to UART Bridge Controller"))
        assertEquals("FTDI USB UART Device", SerialPortRules.usbDeviceName("FTDI", null))
        assertEquals("Generic USB Serial", SerialPortRules.usbDeviceName(" ", "USB Serial"))
        assertEquals("Generic USB UART Device", SerialPortRules.usbDeviceName(null, ""))
    }

    @Test
    fun theUsbRowsHaveTheIdsThePortAndTheDefaultBaudRate() {
        val rows = SerialPortRules.usbAdapters(listOf(Ports.rfcomm, Ports.ch340, Ports.cp2102, Ports.physical))
        assertEquals(
            listOf(
                DiscoveredAdapter(
                    address = AdapterAddress.UsbSerial(vendorId = 0x1A86, productId = 0x7523, serial = null, baudRate = 115_200, portIndex = 0),
                    displayName = "Generic USB Serial",
                    detail = "1A86:7523 (ttyUSB1)",
                ),
                DiscoveredAdapter(
                    address = AdapterAddress.UsbSerial(vendorId = 0x10C4, productId = 0xEA60, serial = "0001", baudRate = 115_200, portIndex = 0),
                    displayName = "Silicon Labs CP2102 USB to UART Bridge Controller",
                    detail = "10C4:EA60 (ttyUSB0)",
                ),
            ),
            rows,
        )
    }

    @Test
    fun aDeviceWithTwoPortsGivesTwoRowsAndAPortWithNoAccessNeedsPermission() {
        val first = SerialPortInfo(id = "/dev/ttyUSB3", name = "ttyUSB3", description = "Dual RS232", vendorId = 0x0403, productId = 0x6010, serialNumber = "FT1", manufacturer = "FTDI", location = "1-2:1.0")
        val second = first.copy(id = "/dev/ttyUSB2", name = "ttyUSB2", location = "1-2:1.1", accessible = false)
        val rows = SerialPortRules.usbAdapters(listOf(second, first))
        assertEquals(listOf("0403:6010 #1 (ttyUSB3)", "0403:6010 #2 (ttyUSB2)"), rows.map { row -> row.detail })
        assertEquals(listOf(0, 1), rows.map { row -> (row.address as AdapterAddress.UsbSerial).portIndex })
        assertEquals(listOf(false, true), rows.map { row -> row.needsPermission })
        assertEquals("FTDI Dual RS232", rows.first().displayName)

        // The address of each row selects its own port again.
        assertEquals("/dev/ttyUSB3", SerialPortRules.portIdFor(rows[0].address, listOf(first, second)))
        assertEquals("/dev/ttyUSB2", SerialPortRules.portIdFor(rows[1].address, listOf(first, second)))
    }

    @Test
    fun theSppRowsHaveTheBluetoothAddressAndThePort() {
        val rows = SerialPortRules.sppAdapters(listOf(Ports.cp2102, Ports.rfcomm, Ports.windowsBluetooth, Ports.macBluetooth, Ports.macIncoming))
        assertEquals(
            listOf(
                DiscoveredAdapter(AdapterAddress.BluetoothSpp(mac = "", serialPort = "COM5"), "COM5", "Standard Serial over Bluetooth link (COM5)"),
                DiscoveredAdapter(AdapterAddress.BluetoothSpp(mac = "", serialPort = "/dev/cu.ESP32-DRO"), "ESP32-DRO", "ESP32-DRO"),
                DiscoveredAdapter(AdapterAddress.BluetoothSpp(mac = "24:6F:28:A1:B2:C3", serialPort = "/dev/rfcomm0"), "rfcomm0", "24:6F:28:A1:B2:C3"),
            ),
            rows,
        )
    }

    @Test
    fun theAttachedUsbDevicesGiveTheCapabilityOfAUsbAddress() {
        val noAccess = Ports.ch340.copy(accessible = false)
        val attached = SerialPortRules.attachedUsb(listOf(Ports.cp2102, noAccess, Ports.rfcomm))
        assertEquals(
            listOf(AttachedUsbDevice(0x10C4, 0xEA60, "0001", permitted = true), AttachedUsbDevice(0x1A86, 0x7523, null, permitted = false)),
            attached,
        )
        val state = CapabilityState(attachedUsb = attached)
        assertEquals(LinkCapability.Available, state.capabilityOf(AdapterAddress.UsbSerial(0x10C4, 0xEA60, "0001")))
        assertEquals(LinkCapability.Unavailable(LinkFailure.PermissionMissing), state.capabilityOf(AdapterAddress.UsbSerial(0x1A86, 0x7523)))
        assertEquals(LinkCapability.Unavailable(LinkFailure.DeviceGone), state.capabilityOf(AdapterAddress.UsbSerial(0x0403, 0x6001)))
    }

    @Test
    fun aUsbAddressSelectsThePortOfItsDevice() {
        val ports = listOf(Ports.cp2102, Ports.ch340)
        assertEquals("/dev/ttyUSB0", SerialPortRules.portIdFor(AdapterAddress.UsbSerial(0x10C4, 0xEA60, "0001"), ports))
        // No serial number in the address: the vendor ID and the product ID select the port.
        assertEquals("/dev/ttyUSB0", SerialPortRules.portIdFor(AdapterAddress.UsbSerial(0x10C4, 0xEA60), ports))
        assertNull(SerialPortRules.portIdFor(AdapterAddress.UsbSerial(0x10C4, 0xEA60, "0002"), ports))
        assertNull(SerialPortRules.portIdFor(AdapterAddress.UsbSerial(0x10C4, 0xEA60, "0001", portIndex = 1), ports))
        assertNull(SerialPortRules.portIdFor(AdapterAddress.UsbSerial(0x10C4, 0xEA60), emptyList()))
    }

    @Test
    fun anSppAddressSelectsTheBluetoothAddressFirstThenThePort() {
        val renumbered = Ports.rfcomm.copy(id = "/dev/rfcomm1", name = "rfcomm1")
        val other = Ports.rfcomm.copy(bluetoothMac = "11:22:33:44:55:66")
        val stored = AdapterAddress.BluetoothSpp(mac = "24:6f:28:a1:b2:c3", serialPort = "/dev/rfcomm0")

        assertEquals("/dev/rfcomm0", SerialPortRules.portIdFor(stored, listOf(Ports.rfcomm)))
        // The port number changed: the Bluetooth address finds the port.
        assertEquals("/dev/rfcomm1", SerialPortRules.portIdFor(stored, listOf(renumbered)))
        // The port belongs to a different adapter now: no port.
        assertNull(SerialPortRules.portIdFor(stored, listOf(other)))
        // No Bluetooth address (Windows, macOS): the stored port, also when the list does not have it.
        assertEquals("COM5", SerialPortRules.portIdFor(AdapterAddress.BluetoothSpp(serialPort = "COM5"), listOf(Ports.windowsBluetooth)))
        assertEquals("COM5", SerialPortRules.portIdFor(AdapterAddress.BluetoothSpp(serialPort = "COM5"), emptyList()))
        // An address of the Android app has no port, and no port has its Bluetooth address: no port.
        assertNull(SerialPortRules.portIdFor(AdapterAddress.BluetoothSpp("AA:BB:CC:DD:EE:FF"), listOf(Ports.rfcomm)))
        assertNull(SerialPortRules.portIdFor(AdapterAddress.Tcp("192.168.1.20"), listOf(Ports.rfcomm)))
    }

    @Test
    fun theOpenErrorCodesGiveTheLinkFailures() {
        assertEquals(LinkFailure.PermissionMissing, SerialPortRules.openFailure(13, windows = false))
        assertEquals(LinkFailure.PermissionMissing, SerialPortRules.openFailure(1, windows = false))
        assertEquals(LinkFailure.DeviceGone, SerialPortRules.openFailure(2, windows = false))
        assertEquals(LinkFailure.DeviceGone, SerialPortRules.openFailure(6, windows = false))
        assertEquals(LinkFailure.DeviceGone, SerialPortRules.openFailure(19, windows = false))
        // EBUSY and EHOSTDOWN (an adapter that is off): the session tries again.
        assertEquals(LinkFailure.Refused, SerialPortRules.openFailure(16, windows = false))
        assertEquals(LinkFailure.Refused, SerialPortRules.openFailure(112, windows = false))
        assertEquals(LinkFailure.DeviceGone, SerialPortRules.openFailure(2, windows = true))
        assertEquals(LinkFailure.DeviceGone, SerialPortRules.openFailure(3, windows = true))
        // ERROR_ACCESS_DENIED: a different program has the port.
        assertEquals(LinkFailure.Refused, SerialPortRules.openFailure(5, windows = true))
        assertEquals(LinkFailure.Refused, SerialPortRules.openFailure(121, windows = true))
        assertEquals(true, SerialPortRules.isWindows("Windows 11"))
        assertEquals(false, SerialPortRules.isWindows("Linux"))
        assertEquals(false, SerialPortRules.isWindows("Mac OS X"))
    }

    @Test
    fun theBluetoothAddressOfALinuxRfcommPortComesFromSysfs() {
        assertEquals("24:6F:28:A1:B2:C3", LinuxRfcomm.parseMac("24:6f:28:a1:b2:c3\n"))
        assertNull(LinuxRfcomm.parseMac("00:00:00:00:00:00"))
        assertNull(LinuxRfcomm.parseMac("not an address"))

        val sys = Files.createTempDirectory("sys-class-tty").toFile()
        try {
            sys.resolve("rfcomm0").mkdirs()
            sys.resolve("rfcomm0/address").writeText("24:6f:28:a1:b2:c3\n")
            sys.resolve("ttyUSB0").mkdirs()
            assertEquals("24:6F:28:A1:B2:C3", LinuxRfcomm.macOf("rfcomm0", sys))
            assertNull(LinuxRfcomm.macOf("rfcomm1", sys))
            assertNull(LinuxRfcomm.macOf("ttyUSB0", sys))
        } finally {
            sys.deleteRecursively()
        }
    }

    @Test
    fun aDeviceFileThatTheUserCannotWriteIsNotAccessible() {
        val file = Files.createTempFile("tty", "").toFile()
        try {
            assertEquals(true, SerialPortRules.isAccessible(file.absolutePath))
            file.setWritable(false)
            assertEquals(false, SerialPortRules.isAccessible(file.absolutePath))
            assertEquals(false, SerialPortRules.isAccessible("/dev/no-such-port"))
            assertEquals(true, SerialPortRules.isAccessible("COM5"))
        } finally {
            file.setWritable(true)
            file.delete()
        }
    }
}
