package org.superdro.core.dataoutput

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.superdro.core.dataoutput.RealSockets.LOOPBACK
import org.superdro.core.dataoutput.RealSockets.canConnect
import org.superdro.core.dataoutput.RealSockets.freePort

/** The sockets of the system: a close releases the port before it returns. */
class SystemSocketsTest {

    private val sockets = systemListenSockets(Dispatchers.IO)

    /** Gives true when a new socket can bind to [port] on the loopback address. */
    private fun canBind(port: Int): Boolean = runCatching {
        ServerSocket().use { socket ->
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(LOOPBACK, port))
        }
    }.isSuccess

    @Test
    fun aCloseWithAnAcceptCallReleasesThePortAtOnce() = runBlocking {
        repeat(REPEATS) {
            val port = freePort()
            val socket = sockets.bind(LOOPBACK, port)
            val started = CompletableDeferred<Unit>()
            val accept = launch(Dispatchers.Default) {
                started.complete(Unit)
                runCatching { socket.accept() }
            }
            started.await()
            // Let the accept call start on its thread.
            repeat(10) { yield() }
            socket.close()
            assertTrue(canBind(port), "The port is in use after the close.")
            assertFalse(canConnect(port))
            accept.join()
        }
    }

    @Test
    fun aCloseAfterAnAcceptReleasesThePortAtOnce() = runBlocking {
        repeat(REPEATS) {
            val port = freePort()
            val socket = sockets.bind(LOOPBACK, port)
            Socket(LOOPBACK, port).use { client ->
                val link = socket.accept()
                assertEquals(LOOPBACK, link.remoteHost)
                link.write("#".encodeToByteArray())
                assertEquals('#'.code, client.getInputStream().read())
                link.close()
            }
            socket.close()
            assertTrue(canBind(port), "The port is in use after the close.")
            assertFalse(canConnect(port))
        }
    }

    private companion object {
        /** The number of tries of each test. An old fault occurred in about one of four tries. */
        const val REPEATS = 40
    }
}
