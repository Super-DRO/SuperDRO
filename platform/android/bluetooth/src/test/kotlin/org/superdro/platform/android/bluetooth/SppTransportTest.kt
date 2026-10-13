package org.superdro.platform.android.bluetooth

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.superdro.core.connection.LinkFailure
import org.superdro.core.connection.LinkFailureException
import org.superdro.core.model.AdapterAddress

// The watchdog test reads the virtual time of the test scheduler.
@OptIn(ExperimentalCoroutinesApi::class)
class SppTransportTest {

    private val executor = Executors.newCachedThreadPool()
    private val linkDispatcher = executor.asCoroutineDispatcher()
    private val address = AdapterAddress.BluetoothSpp("24:6F:28:A1:B2:C3")

    @AfterTest
    fun stopThreads() {
        executor.shutdownNow()
    }

    private fun transport(platform: FakeSppPlatform) = SppTransport(address, platform, linkDispatcher)

    @Test
    fun theOpenReportsAMissingPermissionARadioThatIsOffAndAnUnpairedDevice() = runTest {
        for (problem in listOf(LinkFailure.PermissionMissing, LinkFailure.RadioOff, LinkFailure.DeviceGone)) {
            val platform = FakeSppPlatform(problem = problem)
            val failure = assertFailsWith<LinkFailureException> { transport(platform).open() }
            assertEquals(problem, failure.failure)
            assertEquals(listOf("linkProblem"), platform.calls)
        }
    }

    @Test
    fun theOpenCancelsTheDiscoveryAndThenMakesOneConnect() = realTime {
        val platform = FakeSppPlatform()
        val transport = transport(platform)
        transport.open()
        assertEquals(listOf("linkProblem", "cancelDiscovery", "createSocket"), platform.calls)
        assertEquals(1, platform.socket.connectCount)
        assertFailsWith<IllegalStateException> { transport.open() }
        assertEquals(1, platform.socket.connectCount)
        transport.close()
    }

    @Test
    fun aConnectFailureIsRefusedWithNoRetry() = realTime {
        val platform = FakeSppPlatform()
        platform.socket.connectFailure = IOException("read failed, socket might closed or timeout")
        val failure = assertFailsWith<LinkFailureException> { transport(platform).open() }
        assertEquals(LinkFailure.Refused, failure.failure)
        assertEquals(1, platform.socket.connectCount)
        assertTrue(platform.socket.isClosed)
    }

    @Test
    fun aSecurityExceptionIsPermissionMissing() = realTime {
        val platform = FakeSppPlatform(createFailure = SecurityException("Need android.permission.BLUETOOTH_CONNECT"))
        assertEquals(LinkFailure.PermissionMissing, assertFailsWith<LinkFailureException> { transport(platform).open() }.failure)
        val connectPlatform = FakeSppPlatform()
        connectPlatform.socket.connectFailure = SecurityException("Need android.permission.BLUETOOTH_CONNECT")
        assertEquals(LinkFailure.PermissionMissing, assertFailsWith<LinkFailureException> { transport(connectPlatform).open() }.failure)
    }

    @Test
    fun theReadGivesTheBytesInOrderWithABufferOf1024Bytes() = realTime {
        val platform = FakeSppPlatform()
        val transport = transport(platform)
        transport.open()
        val stream = ByteArray(3000) { index -> ('0' + index % 10).code.toByte() }
        platform.socket.send(stream)
        val chunks = mutableListOf<ByteArray>()
        withTimeout(5000) {
            transport.incoming
                .runningFold(0) { total, bytes ->
                    chunks += bytes
                    total + bytes.size
                }
                .first { total -> total >= stream.size }
        }
        assertTrue(chunks.all { chunk -> chunk.size <= Spp.READ_BUFFER_BYTES })
        assertContentEquals(stream, chunks.flatMap { chunk -> chunk.toList() }.toByteArray())
        transport.close()
    }

    @Test
    fun theEndOfTheStreamIsLost() = realTime {
        val platform = FakeSppPlatform()
        val transport = transport(platform)
        transport.open()
        platform.socket.send("X1;".encodeToByteArray())
        platform.socket.sendEnd()
        val chunks = mutableListOf<ByteArray>()
        val failure = assertFailsWith<LinkFailureException> { transport.incoming.collect { bytes -> chunks += bytes } }
        assertEquals(LinkFailure.Lost, failure.failure)
        assertEquals("X1;", chunks.joinToString("") { bytes -> bytes.decodeToString() })
        transport.close()
    }

    @Test
    fun aReadFailureEndsOnlyTheFlowWithLost() = realTime {
        for (readFailure in listOf(IOException("Software caused connection abort"), IllegalStateException("listener failed"))) {
            val platform = FakeSppPlatform()
            val transport = transport(platform)
            transport.open()
            platform.socket.failRead(readFailure)
            val failure = assertFailsWith<LinkFailureException> { transport.incoming.toList() }
            assertEquals(LinkFailure.Lost, failure.failure)
            assertEquals(LinkFailure.Lost, assertFailsWith<LinkFailureException> { transport.write(byteArrayOf(0x07)) }.failure)
            transport.close()
        }
    }

    @Test
    fun aWriteWritesAndFlushes() = realTime {
        val platform = FakeSppPlatform()
        val transport = transport(platform)
        transport.open()
        transport.write("#get:mac;".encodeToByteArray())
        transport.write(byteArrayOf(Spp.BEL))
        assertEquals("#get:mac;\u0007", platform.socket.writtenBytes().decodeToString())
        assertEquals(2, platform.socket.flushCount)
        transport.close()
    }

    @Test
    fun aWriteExceptionIsLost() = realTime {
        val platform = FakeSppPlatform()
        val transport = transport(platform)
        transport.open()
        platform.socket.writeFailure = IOException("Broken pipe")
        assertEquals(LinkFailure.Lost, assertFailsWith<LinkFailureException> { transport.write("#get:mac;".encodeToByteArray()) }.failure)
        platform.socket.writeFailure = null
        assertEquals(LinkFailure.Lost, assertFailsWith<LinkFailureException> { transport.write(byteArrayOf(Spp.BEL)) }.failure)
        transport.close()
    }

    @Test
    fun aWriteThatDoesNotEndIn4000MsIsLost() = runTest {
        val platform = FakeSppPlatform()
        val transport = transport(platform)
        withContext(Dispatchers.Default) { transport.open() }
        platform.socket.writeGate = CountDownLatch(1)
        val reader = async(Dispatchers.Default) { runCatching { transport.incoming.toList() } }

        val start = testScheduler.currentTime
        val failure = assertFailsWith<LinkFailureException> { transport.write("#get:mac;".encodeToByteArray()) }
        assertEquals(LinkFailure.Lost, failure.failure)
        assertEquals(Spp.WRITE_WATCHDOG_MS, testScheduler.currentTime - start)

        // The watchdog closes the socket: the read also ends with Lost.
        assertTrue(platform.socket.isClosed)
        val readFailure = withContext(Dispatchers.Default) { withTimeout(5000) { reader.await() } }.exceptionOrNull()
        assertIs<LinkFailureException>(readFailure)
        assertEquals(LinkFailure.Lost, readFailure.failure)
        transport.close()
    }

    @Test
    fun noSecondBelByteIsQueuedWhileOneWaits() = realTime {
        val platform = FakeSppPlatform()
        val transport = transport(platform)
        transport.open()
        val gate = CountDownLatch(1)
        platform.socket.writeGate = gate
        assertFalse(transport.isBelPending)

        val first = launch { transport.write(byteArrayOf(Spp.BEL)) }
        withTimeout(2000) { while (!transport.isBelPending) delay(5) }
        // The second BEL byte returns at once with no write.
        withTimeout(500) { transport.write(byteArrayOf(Spp.BEL)) }
        val command = launch { transport.write("#get:mac;".encodeToByteArray()) }
        delay(100)
        assertTrue(command.isActive, "A command still waits for the write in progress.")

        gate.countDown()
        first.join()
        command.join()
        assertFalse(transport.isBelPending)
        transport.write(byteArrayOf(Spp.BEL))
        assertEquals("\u0007#get:mac;\u0007", platform.socket.writtenBytes().decodeToString())
        transport.close()
    }

    @Test
    fun theCloseStopsAReadThatBlocks() = realTime {
        val platform = FakeSppPlatform()
        val transport = transport(platform)
        transport.open()
        val reader = async { transport.incoming.toList() }
        delay(200)
        assertTrue(reader.isActive)
        transport.close()
        transport.close()
        assertEquals(emptyList<ByteArray>(), withTimeout(3000) { reader.await() })
        assertTrue(platform.socket.isClosed)
        assertEquals(emptyList<ByteArray>(), transport.incoming.toList())
        assertEquals(LinkFailure.Lost, assertFailsWith<LinkFailureException> { transport.write(byteArrayOf(Spp.BEL)) }.failure)
    }

    @Test
    fun theCloseStopsAConnectThatBlocks() = realTime {
        val platform = FakeSppPlatform()
        platform.socket.connectBlocks = true
        val transport = transport(platform)
        val opening = async { runCatching { transport.open() } }
        withTimeout(2000) { while (platform.socket.connectCount == 0) delay(5) }
        transport.close()
        val failure = withTimeout(3000) { opening.await() }.exceptionOrNull()
        assertIs<LinkFailureException>(failure)
        assertEquals(LinkFailure.Refused, failure.failure)
    }

    /** Runs [block] with real time: the fake socket blocks real threads. */
    private fun realTime(block: suspend CoroutineScope.() -> Unit): TestResult = runTest(timeout = 60.seconds) {
        withContext(Dispatchers.Default) { block() }
    }
}
