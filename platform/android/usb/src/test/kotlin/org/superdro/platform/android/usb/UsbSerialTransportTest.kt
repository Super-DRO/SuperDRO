package org.superdro.platform.android.usb

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.superdro.core.connection.LinkFailure
import org.superdro.core.connection.LinkFailureException
import org.superdro.core.model.AdapterAddress

// The write limit test reads the virtual time of the test scheduler.
@OptIn(ExperimentalCoroutinesApi::class)
class UsbSerialTransportTest {

    private val executor = Executors.newCachedThreadPool()
    private val linkDispatcher = executor.asCoroutineDispatcher()
    private val address = AdapterAddress.UsbSerial(0x10C4, 0xEA60, serial = "0001", baudRate = 9600)

    @AfterTest
    fun stopThreads() {
        executor.shutdownNow()
    }

    private fun transport(platform: FakeUsbPlatform) = UsbSerialTransport(address, platform, linkDispatcher)

    @Test
    fun theOpenUsesTheStoredBaudRateWith8N1AndNoDtrOrRts() = realTime {
        val platform = FakeUsbPlatform()
        val transport = transport(platform)
        transport.open()
        assertEquals(listOf(address to UsbLineSettings(baudRate = 9600)), platform.openedWith)
        assertEquals(9600, transport.lineSettings.baudRate)
        assertEquals(false, transport.lineSettings.dtr)
        assertEquals(false, transport.lineSettings.rts)
        assertFailsWith<IllegalStateException> { transport.open() }
        assertEquals(1, platform.openedWith.size)
        transport.close()
    }

    @Test
    fun theOpenFailuresKeepTheirCause() = realTime {
        for (cause in listOf(LinkFailure.DeviceGone, LinkFailure.PermissionMissing, LinkFailure.Refused)) {
            val platform = FakeUsbPlatform(openFailure = LinkFailureException(cause))
            assertEquals(cause, assertFailsWith<LinkFailureException> { transport(platform).open() }.failure)
        }
        val security = FakeUsbPlatform(openFailure = SecurityException("User has not given permission to device"))
        assertEquals(LinkFailure.PermissionMissing, assertFailsWith<LinkFailureException> { transport(security).open() }.failure)
        val io = FakeUsbPlatform(openFailure = IOException("Error setting baud rate"))
        assertEquals(LinkFailure.Refused, assertFailsWith<LinkFailureException> { transport(io).open() }.failure)
    }

    @Test
    fun theReadIsABulkReadWithA200MsTimeout() = realTime {
        val platform = FakeUsbPlatform()
        val transport = transport(platform)
        transport.open()
        val reader = async { withTimeout(5000) { transport.incoming.first() } }
        delay(500)
        platform.port.send("X1234;".encodeToByteArray())
        assertEquals("X1234;", reader.await().decodeToString())
        synchronized(platform.port.readTimeouts) {
            assertTrue(platform.port.readTimeouts.size >= 2, "A read with no data ends after its timeout, and the loop reads again.")
            assertTrue(platform.port.readTimeouts.all { timeout -> timeout == 200 })
            assertTrue(platform.port.readBufferSizes.all { size -> size in 64..16384 })
        }
        transport.close()
    }

    @Test
    fun aReadFailureOfADetachedDeviceIsDeviceGone() = realTime {
        val platform = FakeUsbPlatform()
        val transport = transport(platform)
        transport.open()
        platform.attached = false
        platform.port.failRead(IOException("USB get_status request failed"))
        val failure = assertFailsWith<LinkFailureException> { withTimeout(5000) { transport.incoming.toList() } }
        assertEquals(LinkFailure.DeviceGone, failure.failure)
        assertEquals(LinkFailure.DeviceGone, assertFailsWith<LinkFailureException> { transport.write(byteArrayOf(0x07)) }.failure)
        transport.close()
    }

    @Test
    fun aReadFailureOfAnAttachedDeviceIsLost() = realTime {
        val platform = FakeUsbPlatform()
        val transport = transport(platform)
        transport.open()
        platform.port.failRead(IOException("Error reading"))
        val failure = assertFailsWith<LinkFailureException> { withTimeout(5000) { transport.incoming.toList() } }
        assertEquals(LinkFailure.Lost, failure.failure)
        transport.close()
    }

    @Test
    fun aWriteGoesToThePortWithA1000MsTotalLimit() = realTime {
        val platform = FakeUsbPlatform()
        val transport = transport(platform)
        transport.open()
        transport.write("#get:mac;".encodeToByteArray())
        val (bytes, timeout) = platform.port.writes.single()
        assertContentEquals("#get:mac;".encodeToByteArray(), bytes)
        assertEquals(1000, timeout)
        transport.close()
    }

    @Test
    fun aWriteFailureIsLost() = realTime {
        val platform = FakeUsbPlatform()
        val transport = transport(platform)
        transport.open()
        platform.port.writeFailure = IOException("Error writing 9 bytes at offset 0 of total 9")
        assertEquals(LinkFailure.Lost, assertFailsWith<LinkFailureException> { transport.write("#get:mac;".encodeToByteArray()) }.failure)
        transport.close()
    }

    @Test
    fun aWriteThatDoesNotEndIn1000MsIsLost() = runTest {
        val platform = FakeUsbPlatform()
        val transport = transport(platform)
        withContext(Dispatchers.Default) { transport.open() }
        platform.port.writeGate = CountDownLatch(1)
        val start = testScheduler.currentTime
        val failure = assertFailsWith<LinkFailureException> { transport.write("#get:mac;".encodeToByteArray()) }
        assertEquals(LinkFailure.Lost, failure.failure)
        assertEquals(1000L, testScheduler.currentTime - start)
        transport.close()
    }

    @Test
    fun theCloseEndsTheReadLoop() = realTime {
        val platform = FakeUsbPlatform()
        val transport = transport(platform)
        transport.open()
        val reader = async { transport.incoming.toList() }
        delay(300)
        assertTrue(reader.isActive)
        transport.close()
        transport.close()
        assertEquals(emptyList<ByteArray>(), withTimeout(3000) { reader.await() })
        assertTrue(platform.port.closed)
        assertEquals(emptyList<ByteArray>(), transport.incoming.toList())
        assertEquals(LinkFailure.Lost, assertFailsWith<LinkFailureException> { transport.write(byteArrayOf(0x07)) }.failure)
    }

    /** Runs [block] with real time: the fake port blocks real threads. */
    private fun realTime(block: suspend CoroutineScope.() -> Unit): TestResult = runTest(timeout = 60.seconds) {
        withContext(Dispatchers.Default) { block() }
    }
}
