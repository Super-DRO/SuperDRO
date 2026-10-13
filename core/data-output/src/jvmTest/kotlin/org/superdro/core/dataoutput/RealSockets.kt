package org.superdro.core.dataoutput

import java.io.InputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeout
import org.superdro.core.connection.NetworkInterfaceInfo
import org.superdro.core.model.BindInterface
import org.superdro.core.model.DataOutputConfig
import org.superdro.core.util.InMemorySecretStore

/** The helpers of the tests with real sockets on the loopback address. */
object RealSockets {

    const val LOOPBACK: String = "127.0.0.1"

    /** The values of the socket tests. */
    val testValues = OutputValues(xAbsolute = 12700.0, yAbsolute = -3175.0, zAbsolute = 0.0, updatedAtEpochMs = 1791727402517L)

    /** The default record of the test values. */
    const val DEFAULT_RECORD: String = "2026-10-11T14:03:22.517Z;12.7000;-3.1750;0.0000\n"

    /** Gives a port that is free now. */
    fun freePort(): Int = ServerSocket(0).use { socket -> socket.localPort }

    /** Gives true when a TCP connection to [port] on the loopback address opens. */
    fun canConnect(port: Int): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(LOOPBACK, port), 1000)
            true
        }
    } catch (exception: ConnectException) {
        false
    }

    /** Opens a client socket with a read timeout. */
    fun connect(port: Int, readTimeoutMs: Int = 3000): Socket = Socket().apply {
        connect(InetSocketAddress(LOOPBACK, port), 2000)
        soTimeout = readTimeoutMs
    }

    /** Reads one line with the line feed, or null at the end of the stream. */
    fun readLine(input: InputStream): String? {
        val bytes = java.io.ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) return if (bytes.size() == 0) null else bytes.toString(Charsets.UTF_8)
            bytes.write(value)
            if (value == '\n'.code) return bytes.toString(Charsets.UTF_8)
        }
    }

    /** Gives true when the stream ends (or fails) in [timeoutMs] with no data. */
    fun endsWithNoData(input: InputStream, timeoutMs: Int, socket: Socket): Boolean {
        socket.soTimeout = timeoutMs
        return try {
            input.read() < 0
        } catch (exception: SocketTimeoutException) {
            false
        } catch (exception: java.io.IOException) {
            true
        }
    }

    /**
     * Reads until the end of the stream and gives the text.
     *
     * @throws SocketTimeoutException when the stream does not end in [timeoutMs].
     */
    fun readUntilEnd(socket: Socket, timeoutMs: Int): String {
        socket.soTimeout = timeoutMs
        val bytes = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(256)
        val input = socket.getInputStream()
        while (true) {
            val count = try {
                input.read(buffer)
            } catch (exception: SocketTimeoutException) {
                throw exception
            } catch (exception: java.io.IOException) {
                -1
            }
            if (count < 0) return bytes.toString(Charsets.UTF_8)
            bytes.write(buffer, 0, count)
        }
    }

    /** Waits until [condition] is true. */
    suspend fun waitUntil(timeoutMs: Long = 5000L, condition: () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!condition()) delay(10L)
        }
    }

    /** One output on the loopback interface, with the real Ktor sockets. */
    class LoopbackOutput(
        config: DataOutputConfig,
        retryDelayMs: Long = TcpOutputServer.RETRY_DELAY_MS,
        listenSockets: ((KtorListenSocketFactory) -> ListenSocketFactory) = { factory -> factory },
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sockets = KtorListenSocketFactory(Dispatchers.IO)
        val store = InMemorySecretStore()
        val values = MutableStateFlow(testValues)
        val interfaces = FakeNetworkInterfaces(listOf(NetworkInterfaceInfo("lo", BindInterface.Localhost, listOf(LOOPBACK))))
        val controller = DataOutputController(
            profileId = "p1",
            parentScope = scope,
            values = values,
            platform = DataOutputPlatform(
                networkInterfaces = interfaces,
                secretStore = store,
                listenSockets = listenSockets(sockets),
                retryDelayMs = retryDelayMs,
            ),
            config = config.copy(bindInterface = BindInterface.Localhost),
            profileName = "Mill",
        )

        fun close() {
            controller.close()
            scope.cancel()
            sockets.close()
        }
    }
}
