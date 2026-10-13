package org.superdro.core.dataoutput

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.superdro.core.dataoutput.RealSockets.DEFAULT_RECORD
import org.superdro.core.dataoutput.RealSockets.LoopbackOutput
import org.superdro.core.dataoutput.RealSockets.canConnect
import org.superdro.core.dataoutput.RealSockets.connect
import org.superdro.core.dataoutput.RealSockets.endsWithNoData
import org.superdro.core.dataoutput.RealSockets.freePort
import org.superdro.core.dataoutput.RealSockets.readLine
import org.superdro.core.dataoutput.RealSockets.waitUntil
import org.superdro.core.model.DataOutputConfig
import org.superdro.core.model.DataOutputSecurity

/** The TCP server output on the loopback address with real sockets. */
class TcpSocketOutputTest {

    private fun plainConfig(port: Int) =
        DataOutputConfig(enabled = true, port = port, security = DataOutputSecurity.PlainText, plainTextConfirmed = true)

    @Test
    fun aClientOnTheSelectedAddressAndPortGetsTheRecordsOfTheTest() = runBlocking {
        val port = freePort()
        val output = LoopbackOutput(plainConfig(port))
        try {
            // No server socket while the output is off.
            assertFalse(canConnect(port))
            output.controller.setMachineOnline(true)
            assertFalse(canConnect(port))
            output.controller.requestStart()
            waitUntil { output.controller.state.value.sender == SenderState.Ready }
            assertEquals(listOf("127.0.0.1:$port"), output.controller.state.value.listening)

            connect(port).use { client ->
                val input = client.getInputStream()
                // The bytes of example 1, then a record at least each 500 ms with the same time
                // stamp while the values do not change.
                val started = System.nanoTime()
                val first = readLine(input)
                val second = readLine(input)
                val third = readLine(input)
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                assertEquals(DEFAULT_RECORD, first)
                assertEquals(48, first!!.encodeToByteArray().size)
                assertEquals(DEFAULT_RECORD, second)
                assertEquals(DEFAULT_RECORD, third)
                assertTrue(elapsedMs < 2000, "Three records took $elapsedMs ms.")
                // The client sends bytes: the server discards them and continues.
                client.getOutputStream().write("#Z;\n".encodeToByteArray())
                client.getOutputStream().flush()
                assertEquals(DEFAULT_RECORD, readLine(input))
                waitUntil { output.controller.state.value.clients.size == 1 }

                // The switch off closes the server and the client at once.
                output.controller.setConfig(output.controller.currentConfig.copy(enabled = false))
                // A record that was on the way can still arrive. Then the stream ends.
                val rest = RealSockets.readUntilEnd(client, 2000)
                assertEquals(DEFAULT_RECORD.repeat(rest.length / DEFAULT_RECORD.length), rest)
            }
            assertFalse(canConnect(port))
        } finally {
            output.close()
        }
    }

    @Test
    fun aChangeGoesOutAtOnce() = runBlocking {
        val port = freePort()
        val output = LoopbackOutput(plainConfig(port))
        try {
            output.controller.setMachineOnline(true)
            output.controller.requestStart()
            waitUntil { output.controller.state.value.sender == SenderState.Ready }
            connect(port).use { client ->
                waitUntil { output.controller.state.value.clients.size == 1 }
                output.values.value = RealSockets.testValues.copy(xAbsolute = 25400.0, updatedAtEpochMs = 1791727402617L)
                assertEquals("2026-10-11T14:03:22.617Z;25.4000;-3.1750;0.0000\n", readLine(client.getInputStream()))
            }
        } finally {
            output.close()
        }
    }

    @Test
    fun anAcceptErrorGivesANewServerOnTheSamePort() = runBlocking {
        val port = freePort()
        var created = 0
        val output = LoopbackOutput(plainConfig(port), retryDelayMs = 300L) { factory ->
            ListenSocketFactory { host, bindPort ->
                val socket = factory.bind(host, bindPort)
                created++
                if (created == 1) FailingAccept(socket) else socket
            }
        }
        try {
            output.controller.setMachineOnline(true)
            output.controller.requestStart()
            waitUntil { output.controller.state.value.sender == SenderState.Ready }
            // The first accept() fails: the state is Failed, and the port is closed.
            connect(port).use { client -> assertTrue(endsWithNoData(client.getInputStream(), 2000, client)) }
            waitUntil { output.controller.state.value.sender is SenderState.Failed }
            val failed = output.controller.state.value.sender as SenderState.Failed
            assertEquals(DataOutputErrorType.AcceptFailed, failed.error.type)
            assertEquals("Simulated accept() error", failed.error.detail)
            // A new server listens on the same port after the retry time.
            waitUntil { output.controller.state.value.sender == SenderState.Ready }
            assertEquals(2, created)
            connect(port).use { client -> assertEquals(DEFAULT_RECORD, readLine(client.getInputStream())) }
        } finally {
            output.close()
        }
    }

    /** A socket whose first accept() takes the client, closes it, and fails. */
    private class FailingAccept(private val socket: ListenSocket) : ListenSocket by socket {
        override suspend fun accept(): ByteLink {
            socket.accept().close()
            throw java.io.IOException("Simulated accept() error")
        }
    }
}
