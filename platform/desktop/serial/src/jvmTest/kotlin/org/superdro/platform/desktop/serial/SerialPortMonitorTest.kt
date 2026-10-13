package org.superdro.platform.desktop.serial

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.superdro.core.connection.AttachedUsbDevice
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.SuperDroJson
import org.superdro.core.model.TransportKind

/** The port monitor and the discoveries of the add-device sheet, in virtual time. */
class SerialPortMonitorTest {

    @Test
    fun theMonitorReadsThePortsAtEachIntervalAndAtARefresh() = runTest {
        val source = FakeSerialPorts(emptyList())
        val dispatcher = StandardTestDispatcher(testScheduler)
        val monitor = SerialPortMonitor(source, backgroundScope, intervalMs = 2_000L, dispatcher = dispatcher)
        runCurrent()
        assertEquals(1, source.listCalls)
        assertEquals(emptyList(), monitor.attachedUsb.value)

        // The user attaches a USB adapter: the next read finds it.
        source.ports = listOf(Ports.cp2102)
        advanceTimeBy(1_999L)
        runCurrent()
        assertEquals(emptyList(), monitor.ports.value)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(listOf(Ports.cp2102), monitor.ports.value)
        assertEquals(listOf(AttachedUsbDevice(0x10C4, 0xEA60, "0001", permitted = true)), monitor.attachedUsb.value)

        // A refresh reads at once.
        source.ports = emptyList()
        monitor.refresh()
        runCurrent()
        assertEquals(emptyList(), monitor.ports.value)
        assertEquals(3, source.listCalls)
    }

    @Test
    fun eachDiscoveryListsTheAdaptersOfItsLinkType() = runTest {
        val source = FakeSerialPorts(listOf(Ports.cp2102, Ports.rfcomm, Ports.physical))
        val monitor = SerialPortMonitor(source, backgroundScope, dispatcher = StandardTestDispatcher(testScheduler))
        runCurrent()
        val bluetooth = SerialAdapterDiscovery(TransportKind.BluetoothSpp, monitor)
        val usb = SerialAdapterDiscovery(TransportKind.UsbSerial, monitor)
        assertEquals(TransportKind.BluetoothSpp, bluetooth.kind)
        assertEquals(listOf(AdapterAddress.BluetoothSpp("24:6F:28:A1:B2:C3", "/dev/rfcomm0")), bluetooth.adapters.first().map { it.address })
        assertEquals(listOf(AdapterAddress.UsbSerial(0x10C4, 0xEA60, "0001")), usb.adapters.first().map { it.address })
    }

    @Test
    fun theSerialPortOfAnSppAddressIsANewFieldWithADefault() {
        // An address of an older file, or of the Android app, has no serial port.
        val old = SuperDroJson.decodeFromString(AdapterAddress.serializer(), """{"type":"bluetooth_spp","mac":"24:6F:28:A1:B2:C3"}""")
        assertEquals(AdapterAddress.BluetoothSpp("24:6F:28:A1:B2:C3"), old)
        assertNull((old as AdapterAddress.BluetoothSpp).serialPort)

        val desktop = AdapterAddress.BluetoothSpp(mac = "", serialPort = "COM5")
        val text = SuperDroJson.encodeToString(AdapterAddress.serializer(), desktop)
        assertEquals("""{"type":"bluetooth_spp","mac":"","serialPort":"COM5"}""", text)
        assertEquals(desktop, SuperDroJson.decodeFromString(AdapterAddress.serializer(), text))
    }
}
