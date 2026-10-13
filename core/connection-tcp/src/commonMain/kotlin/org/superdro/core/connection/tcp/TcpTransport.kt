package org.superdro.core.connection.tcp

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.superdro.core.connection.LinkFailure
import org.superdro.core.connection.LinkFailureException
import org.superdro.core.connection.Transport
import org.superdro.core.model.AdapterAddress

/**
 * The connect step of [TcpTransport]. The production connector uses Ktor sockets. A test can give
 * a connector that never ends, to examine the connect limit with virtual time.
 */
internal fun interface TcpConnector {
    /** Opens a TCP stream to [host] and [port] with `TCP_NODELAY` on. */
    suspend fun connect(host: String, port: Int): Socket
}

/** The Ktor connector: `aSocket(selector).tcp().connect(host, port) { noDelay = true }`. */
internal class KtorTcpConnector(private val selector: SelectorManager) : TcpConnector {
    override suspend fun connect(host: String, port: Int): Socket =
        aSocket(selector).tcp().connect(host, port) { noDelay = true }
}

/**
 * The Wi-Fi TCP link to one adapter. The adapter
 * is the TCP server. The app is the client.
 *
 * - [open] makes one connect attempt with a limit of [TcpLinkDefaults.CONNECT_TIMEOUT_MS]. A failure
 *   or a timeout is [LinkFailure.Refused]. The session reconnect policy makes the retries. The
 *   limit also holds when the name lookup of a host name blocks a thread.
 * - The host can be an IPv4 address, an IPv6 address, or a host name.
 * - [incoming] reads into a buffer of [TcpLinkDefaults.READ_BUFFER_BYTES]. The socket has no read
 *   timeout: the no-data watchdog of the session finds a half-open link. A read result less
 *   than 0 or a read exception ends the flow with [LinkFailure.Lost].
 * - [write] writes one command for each call, with a limit of [TcpLinkDefaults.WRITE_TIMEOUT_MS]. A
 *   failure or a timeout is [LinkFailure.Lost].
 * - [close] closes the socket. This stops a read that waits. Then [incoming] completes with no
 *   failure.
 *
 * Collect [incoming] one time, after [open]. Each link gets a new transport.
 *
 * @param dispatcher The dispatcher of the connect, the reads, and the writes. The default is
 *   `Dispatchers.IO`. Android can give the link dispatcher of the service.
 */
class TcpTransport internal constructor(
    override val address: AdapterAddress.Tcp,
    private val connector: TcpConnector,
    private val dispatcher: CoroutineDispatcher,
) : Transport {

    /**
     * Makes a transport for [address] that connects with [selector].
     *
     * @param selector The Ktor selector. One selector can serve all TCP transports.
     */
    constructor(
        address: AdapterAddress.Tcp,
        selector: SelectorManager,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(address, KtorTcpConnector(selector), dispatcher)

    /** The device ID of the link: `"<host>:<port>"`. */
    val deviceId: String = TcpAddressRules.deviceId(address)

    private val stateLock = Mutex()
    private val writeLock = Mutex()

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var input: ByteReadChannel? = null

    @Volatile
    private var output: ByteWriteChannel? = null

    @Volatile
    private var openStarted = false

    @Volatile
    private var closed = false

    @Volatile
    private var writeFailed = false

    private val connectScope = CoroutineScope(SupervisorJob() + dispatcher)

    /** A socket that the connect made, before [open] takes it. Guarded by [stateLock]. */
    private var pendingSocket: Socket? = null

    /** True after [open] stopped to wait for the connect. Guarded by [stateLock]. */
    private var connectAbandoned = false

    override val incoming: Flow<ByteArray> = flow {
        val channel = input ?: if (closed) return@flow else error("Call open() before the collection of incoming.")
        val buffer = ByteArray(TcpLinkDefaults.READ_BUFFER_BYTES)
        while (true) {
            val count = try {
                channel.readAvailable(buffer, 0, buffer.size)
            } catch (failure: Throwable) {
                currentCoroutineContext().ensureActive()
                if (closed) return@flow
                throw LinkFailureException(LinkFailure.Lost, "The TCP read from $deviceId failed.", failure)
            }
            if (count < 0) {
                if (closed) return@flow
                throw LinkFailureException(LinkFailure.Lost, "The adapter at $deviceId closed the TCP link.")
            }
            if (count > 0) emit(buffer.copyOf(count))
        }
    }.flowOn(dispatcher)

    override suspend fun open() {
        stateLock.withLock {
            check(!openStarted) { "A TcpTransport opens only one time. Make a new transport for each link." }
            openStarted = true
        }
        if (closed) throw LinkFailureException(LinkFailure.Refused, "The transport for $deviceId is closed.")
        if (!TcpAddressRules.isValid(address)) {
            throw LinkFailureException(LinkFailure.Refused, "The TCP address $deviceId is not valid.")
        }
        // The connect runs in the scope of the transport, not of the caller. Thus the limit of 5000 ms
        // also holds when the name lookup blocks a thread: the open does not wait for its end.
        val attempt = connectScope.async {
            val opened = connector.connect(address.host, address.port)
            val keep = stateLock.withLock {
                if (connectAbandoned || closed) false else true.also { pendingSocket = opened }
            }
            if (!keep) {
                opened.close()
                throw CancellationException("The TCP connect to $deviceId was abandoned.")
            }
            opened
        }
        val connected = try {
            withTimeoutOrNull(TcpLinkDefaults.CONNECT_TIMEOUT_MS) { attempt.await() }
        } catch (failure: Throwable) {
            abandonConnect(attempt)
            currentCoroutineContext().ensureActive()
            throw LinkFailureException(LinkFailure.Refused, "The TCP connect to $deviceId failed.", failure)
        }
        if (connected == null) {
            abandonConnect(attempt)
            throw LinkFailureException(
                LinkFailure.Refused,
                "The TCP connect to $deviceId did not end in ${TcpLinkDefaults.CONNECT_TIMEOUT_MS} ms.",
            )
        }
        stateLock.withLock {
            pendingSocket = null
            if (closed) {
                connected.close()
                throw LinkFailureException(LinkFailure.Refused, "The transport for $deviceId closed during the open.")
            }
            socket = connected
            input = connected.openReadChannel()
            output = connected.openWriteChannel(autoFlush = false)
        }
    }

    override suspend fun write(bytes: ByteArray) {
        writeLock.withLock {
            val channel = output
            if (channel == null || closed || writeFailed) {
                throw LinkFailureException(LinkFailure.Lost, "The TCP link to $deviceId is not open.")
            }
            val written = try {
                withContext(dispatcher) {
                    withTimeoutOrNull(TcpLinkDefaults.WRITE_TIMEOUT_MS) {
                        channel.writeFully(bytes, 0, bytes.size)
                        channel.flush()
                        true
                    }
                }
            } catch (failure: Throwable) {
                if (failure is CancellationException) currentCoroutineContext().ensureActive()
                writeFailed = true
                throw LinkFailureException(LinkFailure.Lost, "The TCP write to $deviceId failed.", failure)
            }
            if (written == null) {
                writeFailed = true
                throw LinkFailureException(
                    LinkFailure.Lost,
                    "The TCP write to $deviceId did not end in ${TcpLinkDefaults.WRITE_TIMEOUT_MS} ms.",
                )
            }
        }
    }

    override suspend fun close() {
        val toClose = stateLock.withLock {
            if (closed) return
            closed = true
            socket
        }
        // Ktor's Socket.close() first flushes the bytes that wait. A peer that does not read keeps
        // that flush open, and the socket then stays open. Thus cancel the two channels first: the
        // read that waits stops at once, and the flush has nothing to send.
        val cause = CancellationException("The transport for $deviceId closed the link.")
        output?.cancel(cause)
        input?.cancel(cause)
        toClose?.close()
        connectScope.cancel()
    }

    /** Stops the wait for [attempt]. A socket that the connect made already is closed. */
    private suspend fun abandonConnect(attempt: Job) {
        val leftover = withContext(NonCancellable) {
            stateLock.withLock {
                connectAbandoned = true
                pendingSocket.also { pendingSocket = null }
            }
        }
        leftover?.close()
        attempt.cancel()
    }
}
