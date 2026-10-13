package org.superdro.core.connection.tcp

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.port
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/**
 * A TCP server on the loopback interface, for the tests of [TcpTransport]. It acts as an adapter:
 * the test accepts the connection and then reads or writes on the accepted socket.
 */
internal class LocalTcpServer private constructor(
    private val selector: SelectorManager,
    private val server: ServerSocket,
) : AutoCloseable {

    /** The port that the server listens on. */
    val port: Int get() = server.port

    /** Waits for the next client. */
    suspend fun accept(): Socket = withContext(Dispatchers.IO) { server.accept() }

    override fun close() {
        server.close()
        selector.close()
    }

    companion object {
        /** Starts a server on [host] with a free port. */
        suspend fun start(host: String = "127.0.0.1"): LocalTcpServer {
            val selector = SelectorManager(Dispatchers.IO)
            val server = try {
                aSocket(selector).tcp().bind(host, 0)
            } catch (failure: Throwable) {
                selector.close()
                throw failure
            }
            return LocalTcpServer(selector, server)
        }
    }
}
