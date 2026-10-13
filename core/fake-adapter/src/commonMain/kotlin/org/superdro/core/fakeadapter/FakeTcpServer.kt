package org.superdro.core.fakeadapter

import com.toxicbakery.logging.Arbor
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.network.sockets.port
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.superdro.core.connection.LinkFailureException
import org.superdro.core.model.AdapterAddress

/**
 * The fake adapter behind a local TCP server, for the Wi-Fi TCP transport. The server has one client
 * at a time. A new client replaces the
 * old client, also when the old link is half open.
 *
 * - A refused connect: call [close], or give a port with no server.
 * - A half-open link: give a scenario with [FakeFaults.stopDataAfterMs]. The adapter then sends
 *   nothing, but the socket stays open.
 * - A refused open of the scenario ([FakeFaults.refuseOpens]) closes the socket of the client at once.
 *
 * @param adapter The fake adapter. Each client is a new link to it.
 * @param host The address of the server. The default is the loopback address.
 * @param port The port. The default is 8080. 0 selects a free port.
 * @param dispatcher The dispatcher of the sockets.
 */
class FakeTcpServer(
    private val adapter: FakeAdapter,
    private val host: String = "127.0.0.1",
    private val port: Int = AdapterAddress.Tcp.DEFAULT_PORT,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {

    private val log = Arbor.tag("FakeTcpServer")
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var selector: SelectorManager? = null
    private var server: ServerSocket? = null
    private var client: Job? = null

    /** The port of the server after [start]. */
    var boundPort: Int = port
        private set

    /** The address of the server for a session after [start]. */
    val address: AdapterAddress.Tcp get() = AdapterAddress.Tcp(host, boundPort)

    /** Starts the server and gives its port. */
    suspend fun start(): Int {
        val manager = SelectorManager(dispatcher)
        val socket = try {
            aSocket(manager).tcp().bind(host, port) { reuseAddress = true }
        } catch (failure: Throwable) {
            manager.close()
            throw failure
        }
        selector = manager
        server = socket
        boundPort = socket.port
        scope.launch { accept(socket) }
        log.d { "Fake adapter server on $host:$boundPort" }
        return boundPort
    }

    private suspend fun accept(socket: ServerSocket) {
        while (scope.isActive) {
            val connection = try {
                socket.accept()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                return
            }
            client?.cancel()
            client = scope.launch { serve(connection) }
        }
    }

    private suspend fun serve(socket: Socket) {
        val transport = adapter.transport(address)
        try {
            transport.open()
        } catch (failure: LinkFailureException) {
            socket.close()
            return
        }
        try {
            coroutineScope {
                val input = socket.openReadChannel()
                val output = socket.openWriteChannel(autoFlush = false)
                launch {
                    val buffer = ByteArray(READ_BUFFER_BYTES)
                    while (true) {
                        val count = input.readAvailable(buffer, 0, buffer.size)
                        if (count < 0) break
                        if (count > 0) transport.write(buffer.copyOf(count))
                    }
                    transport.close()
                }
                transport.incoming.collect { bytes ->
                    output.writeFully(bytes, 0, bytes.size)
                    output.flush()
                }
                cancel()
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            log.d { "The fake TCP link ended: ${failure.message}" }
        } finally {
            transport.close()
            socket.close()
        }
    }

    /** Stops the server and the client. A connect after this call is refused. */
    override fun close() {
        client?.cancel()
        server?.close()
        selector?.close()
        scope.cancel()
    }

    private companion object {
        const val READ_BUFFER_BYTES = 1024
    }
}
