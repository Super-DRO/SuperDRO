package org.superdro.platform.desktop.serial

import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.superdro.core.connection.LinkFailure
import org.superdro.core.connection.LinkFailureException
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.TransportKind

/**
 * The desktop serial transport on a port list in memory.
 * The tests use real threads, because the transport reads on its link dispatcher.
 */
class SerialPortTransportTest {

    private val ports = FakeSerialPorts(listOf(Ports.cp2102, Ports.rfcomm))
    private val usb = AdapterAddress.UsbSerial(vendorId = 0x10C4, productId = 0xEA60, serial = "0001", baudRate = 9_600)
    private val spp = AdapterAddress.BluetoothSpp(mac = "24:6F:28:A1:B2:C3", serialPort = "/dev/rfcomm0")
    private val transports = mutableListOf<SerialPortTransport>()

    private fun transport(address: AdapterAddress, detachCheckMs: Long = 100L): SerialPortTransport =
        SerialPortTransport(address, ports, Dispatchers.IO, detachCheckMs = detachCheckMs, detachPollMs = 10L).also(transports::add)

    @AfterTest
    fun closeAll() = runBlocking {
        transports.forEach { transport -> transport.close() }
    }

    @Test
    fun aUsbLinkOpensWithTheStoredBaudRate8N1AndNoDtrOrRts() = runBlocking {
        val transport = transport(usb)
        transport.open()
        assertEquals("/dev/ttyUSB0", transport.openedPortId)
        val (id, settings) = ports.openSettings.single()
        assertEquals("/dev/ttyUSB0", id)
        // A stored 9600 stays 9600.
        assertEquals(9_600, settings.baudRate)
        assertEquals(8, settings.dataBits)
        assertEquals(1, settings.stopBits)
        assertFalse(settings.dtr)
        assertFalse(settings.rts)
        assertEquals(200, settings.readTimeoutMs)
        assertEquals(1_000, settings.writeTimeoutMs)
        assertEquals(2_048, settings.readBufferBytes)
    }

    @Test
    fun anSppLinkOpensItsRfcommPortWithTheWriteWatchdogOf4000Ms() = runBlocking {
        val transport = transport(spp)
        transport.open()
        val (id, settings) = ports.openSettings.single()
        assertEquals("/dev/rfcomm0", id)
        assertEquals(4_000, settings.writeTimeoutMs)
        assertEquals(1_024, settings.readBufferBytes)
        assertFalse(settings.dtr)
        assertFalse(settings.rts)
    }

    @Test
    fun theBytesOfTheAdapterComeInOrderAndTheWritesGoOut() = runBlocking {
        val transport = transport(usb)
        transport.open()
        val port = ports.openPort("/dev/ttyUSB0")
        val received = async { withTimeout(5_000) { transport.incoming.first() } }
        port.feed("X1200;".encodeToByteArray())
        assertContentEquals("X1200;".encodeToByteArray(), received.await())

        transport.write("#get:mac;".encodeToByteArray())
        transport.write(ByteArray(0))
        assertEquals(listOf("#get:mac;"), port.written.map { bytes -> bytes.decodeToString() })
    }

    @Test
    fun noPortForTheAddressIsDeviceGone() = runBlocking {
        ports.ports = listOf(Ports.rfcomm)
        val failure = assertFailsWith<LinkFailureException> { transport(usb).open() }
        assertEquals(LinkFailure.DeviceGone, failure.failure)
        assertTrue(ports.openSettings.isEmpty())
    }

    @Test
    fun theFailureOfTheOpenGoesToTheSession() = runBlocking {
        ports.openFailure = LinkFailure.PermissionMissing
        assertEquals(LinkFailure.PermissionMissing, assertFailsWith<LinkFailureException> { transport(usb).open() }.failure)
        ports.openFailure = LinkFailure.Refused
        assertEquals(LinkFailure.Refused, assertFailsWith<LinkFailureException> { transport(spp).open() }.failure)
    }

    @Test
    fun aReadFailureOfAPortThatGoesAwayIsDeviceGone() = runBlocking {
        val transport = transport(usb)
        transport.open()
        val port = ports.openPort("/dev/ttyUSB0")
        val result = async { runCatching { transport.incoming.toList() } }
        ports.ports = listOf(Ports.rfcomm)
        port.failRead()
        val failure = withTimeout(5_000) { result.await() }.exceptionOrNull()
        assertEquals(LinkFailure.DeviceGone, (failure as LinkFailureException).failure)
        // The next write gives the same cause.
        assertEquals(LinkFailure.DeviceGone, assertFailsWith<LinkFailureException> { transport.write(byteArrayOf(7)) }.failure)
    }

    @Test
    fun aReadFailureOfAPortThatStaysIsLost() = runBlocking {
        val transport = transport(spp)
        transport.open()
        val port = ports.openPort("/dev/rfcomm0")
        val result = async { runCatching { transport.incoming.toList() } }
        port.failRead()
        val failure = withTimeout(5_000) { result.await() }.exceptionOrNull()
        assertEquals(LinkFailure.Lost, (failure as LinkFailureException).failure)
    }

    @Test
    fun aWriteThatDoesNotEndInTheLimitIsLost() = runBlocking {
        val transport = SerialPortTransport(
            spp,
            ports,
            Dispatchers.IO,
            settings = SerialLineSettings.spp().copy(writeTimeoutMs = 100),
        ).also(transports::add)
        transport.open()
        val port = ports.openPort("/dev/rfcomm0")
        port.blockWrites = true
        val started = System.nanoTime()
        val failure = assertFailsWith<LinkFailureException> { transport.write(byteArrayOf(7)) }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        port.blockWrites = false
        assertEquals(LinkFailure.Lost, failure.failure)
        assertTrue(elapsedMs in 90..2_000, "elapsed $elapsedMs ms")
    }

    @Test
    fun aWriteFailureIsLost() = runBlocking {
        val transport = transport(usb)
        transport.open()
        ports.openPort("/dev/ttyUSB0").writeFailure = IOException("broken pipe")
        assertEquals(LinkFailure.Lost, assertFailsWith<LinkFailureException> { transport.write(byteArrayOf(7)) }.failure)
    }

    @Test
    fun closeEndsTheReadWithNoFailureAndClosesThePort() = runBlocking {
        val transport = transport(usb)
        transport.open()
        val port = ports.openPort("/dev/ttyUSB0")
        val result = async { runCatching { transport.incoming.toList() } }
        Thread.sleep(50)
        transport.close()
        transport.close()
        assertTrue(withTimeout(5_000) { result.await() }.isSuccess)
        assertTrue(port.closed)
        assertEquals(LinkFailure.Lost, assertFailsWith<LinkFailureException> { transport.write(byteArrayOf(7)) }.failure)
    }

    @Test
    fun aTransportOpensOnlyOneTime() = runBlocking {
        val transport = transport(usb)
        transport.open()
        assertFailsWith<IllegalStateException> { transport.open() }
        val closed = transport(usb)
        closed.close()
        assertEquals(LinkFailure.Refused, assertFailsWith<LinkFailureException> { closed.open() }.failure)
    }

    @Test
    fun theFactoryMakesTransportsOfItsLinkTypeOnly() {
        val factory = SerialTransportFactory(TransportKind.UsbSerial, ports, Dispatchers.IO)
        assertEquals(usb, factory.create(usb).address)
        assertFailsWith<IllegalArgumentException> { factory.create(spp) }
        assertFailsWith<IllegalArgumentException> { SerialTransportFactory(TransportKind.Tcp, ports, Dispatchers.IO) }
        assertFailsWith<IllegalArgumentException> { SerialPortTransport(AdapterAddress.Tcp("10.0.0.2"), ports, Dispatchers.IO) }
    }
}
