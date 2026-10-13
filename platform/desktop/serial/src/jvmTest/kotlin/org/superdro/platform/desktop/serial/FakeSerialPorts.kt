package org.superdro.platform.desktop.serial

import java.io.IOException
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.superdro.core.connection.LinkFailure
import org.superdro.core.connection.LinkFailureException

/** A port list in memory for the tests. Each opened port is a [FakeOpenPort]. */
class FakeSerialPorts(var ports: List<SerialPortInfo> = emptyList()) : SerialPorts {

    /** The ports that [open] opened, in order. */
    val opened: MutableList<FakeOpenPort> = Collections.synchronizedList(mutableListOf())

    /** The settings of each open, in order. */
    val openSettings: MutableList<Pair<String, SerialLineSettings>> = Collections.synchronizedList(mutableListOf())

    /** When not null, [open] throws a [LinkFailureException] with this failure. */
    var openFailure: LinkFailure? = null

    /** The number of calls of [list]. */
    @Volatile
    var listCalls = 0

    /** Gives the port [id], so that a test can feed it. */
    fun openPort(id: String): FakeOpenPort = synchronized(opened) { opened.last { port -> port.id == id } }

    override fun list(): List<SerialPortInfo> {
        listCalls += 1
        return ports
    }

    override fun open(id: String, settings: SerialLineSettings): OpenSerialPort {
        openSettings += id to settings
        openFailure?.let { failure -> throw LinkFailureException(failure, "fake open failure") }
        if (ports.none { port -> port.id == id }) throw LinkFailureException(LinkFailure.DeviceGone, "no port $id")
        return FakeOpenPort(id, settings.readTimeoutMs.toLong()).also { port -> opened += port }
    }
}

/** One open port in memory. A test puts the bytes of the adapter with [feed] and reads the written bytes. */
class FakeOpenPort(val id: String, private val readTimeoutMs: Long) : OpenSerialPort {

    private val input = LinkedBlockingQueue<Any>()

    /** The bytes that the transport wrote, one array for each write. */
    val written: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())

    /** When true, [write] blocks until the test sets it to false. */
    @Volatile
    var blockWrites = false

    /** When not null, [write] throws it. */
    @Volatile
    var writeFailure: IOException? = null

    @Volatile
    var closed = false
        private set

    /** Gives [bytes] to the next read. */
    fun feed(bytes: ByteArray) {
        input.put(bytes)
    }

    /** Makes the next read fail, as a removed cable does. */
    fun failRead() {
        input.put(READ_FAILURE)
    }

    override fun read(buffer: ByteArray): Int {
        if (closed) throw IOException("closed")
        val next = input.poll(readTimeoutMs, TimeUnit.MILLISECONDS) ?: return 0
        if (next === READ_FAILURE) throw IOException("The fake read failed.")
        val bytes = next as ByteArray
        bytes.copyInto(buffer)
        return bytes.size
    }

    override fun write(bytes: ByteArray) {
        writeFailure?.let { failure -> throw failure }
        while (blockWrites) Thread.sleep(5)
        written += bytes.copyOf()
    }

    override fun close() {
        closed = true
    }

    private companion object {
        val READ_FAILURE = Any()
    }
}

/** The ports of the examples. */
object Ports {
    val cp2102 = SerialPortInfo(
        id = "/dev/ttyUSB0",
        name = "ttyUSB0",
        description = "CP2102 USB to UART Bridge Controller",
        friendlyName = "CP2102 USB to UART Bridge Controller",
        vendorId = 0x10C4,
        productId = 0xEA60,
        serialNumber = "0001",
        manufacturer = "Silicon Labs",
        location = "1-1.2",
    )

    val ch340 = SerialPortInfo(
        id = "/dev/ttyUSB1",
        name = "ttyUSB1",
        description = "USB Serial",
        vendorId = 0x1A86,
        productId = 0x7523,
        location = "1-1.3",
    )

    val rfcomm = SerialPortInfo(
        id = "/dev/rfcomm0",
        name = "rfcomm0",
        description = "Bluetooth Device",
        friendlyName = "Bluetooth Device",
        location = "0-0",
        bluetoothMac = "24:6F:28:A1:B2:C3",
    )

    val windowsBluetooth = SerialPortInfo(
        id = "COM5",
        name = "COM5",
        description = "Standard Serial over Bluetooth link",
        friendlyName = "Standard Serial over Bluetooth link (COM5)",
    )

    val macBluetooth = SerialPortInfo(id = "/dev/cu.ESP32-DRO", name = "cu.ESP32-DRO", friendlyName = "ESP32-DRO")

    val macIncoming = SerialPortInfo(id = "/dev/cu.Bluetooth-Incoming-Port", name = "cu.Bluetooth-Incoming-Port")

    val physical = SerialPortInfo(id = "/dev/ttyS0", name = "ttyS0", description = "Physical Port S0", friendlyName = "Physical Port S0")
}
