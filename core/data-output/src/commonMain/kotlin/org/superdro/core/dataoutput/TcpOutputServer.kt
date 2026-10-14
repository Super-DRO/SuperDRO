package org.superdro.core.dataoutput

import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import org.superdro.core.util.Clock

/**
 * The TCP server of the data output.
 *
 * - The server has sockets only between [start] and [stop]. [stop] closes the server
 *   sockets and all clients at once.
 * - It listens on each address that [bindHosts] gives (the Wi-Fi interface by default), on
 *   [port]. It accepts any number of clients.
 * - It sends no first message. It reads and discards the inbound bytes.
 * - Each client has one slot with the newest record. A new record replaces a record that the
 *   client did not get yet. A new client gets the next record, not an old one. In the PSK mode,
 *   a client gets records only after the TLS handshake.
 * - The end of the stream, a read error, or a write error closes the client.
 * - A bind failure, an `accept()` error, or no address gives [SenderState.Failed] with the error,
 *   and a new server after [retryDelayMs]. An `accept()` error closes the server and its
 *   clients.
 *
 * Call [start], [stop], and [restart] on one thread. [publish] can come from each thread.
 *
 * @param bindHosts Gives the bind addresses at each start of a server.
 * @param port The port. A test can give 0 for a free port of the system.
 * @param onError Gets each error.
 * @param onWrite Gets the result of each write to each client.
 */
class TcpOutputServer(
    private val scope: CoroutineScope,
    private val sockets: ListenSocketFactory,
    private val security: StreamSecurity,
    private val bindHosts: suspend () -> List<String>,
    private val port: Int,
    private val clock: Clock,
    private val onError: (DataOutputError) -> Unit = {},
    private val onWrite: (Boolean) -> Unit = {},
    private val retryDelayMs: Long = RETRY_DELAY_MS,
) {
    private val _state = MutableStateFlow<SenderState>(SenderState.Stopped)

    /** The state of the server. */
    val state: StateFlow<SenderState> = _state.asStateFlow()

    private val _listening = MutableStateFlow<List<String>>(emptyList())

    /** The bound addresses as `host:port` (`[host]:port` for IPv6), while the server listens. */
    val listening: StateFlow<List<String>> = _listening.asStateFlow()

    private val _clients = MutableStateFlow<List<String>>(emptyList())

    /** The addresses of the clients that get records. */
    val clients: StateFlow<List<String>> = _clients.asStateFlow()

    /** The current run, or null while the server is stopped. A late change of an old run has no effect. */
    @Volatile
    private var current: Run? = null

    /** Starts the server. A second call has no effect while the server runs. */
    fun start() {
        if (current != null) return
        val run = Run()
        current = run
        _state.value = SenderState.Starting
        run.job = scope.launch { run.serve() }
    }

    /** Stops the server: it closes the server sockets and all clients at once. */
    fun stop() {
        val run = current ?: return
        current = null
        run.close()
        _state.value = SenderState.Stopped
        _listening.value = emptyList()
        _clients.value = emptyList()
    }

    /** Stops the server and starts a new one, for example after a change of the addresses. */
    fun restart() {
        stop()
        start()
    }

    /** Puts [record] into the slot of each client. A record that a client did not get yet goes away. */
    fun publish(record: ByteArray) {
        current?.publish { record }
    }

    /**
     * Puts the bytes of [record] into the slot of each client. The server calls [record] only when
     * one or more clients are connected, and only one time for all clients.
     */
    fun publish(record: () -> ByteArray) {
        current?.publish(record)
    }

    /** One start of the server: its job, its sockets, and its clients. */
    private inner class Run {
        var job: Job? = null
        private val listeners = MutableStateFlow<List<ListenSocket>>(emptyList())
        private val clientList = MutableStateFlow<List<Client>>(emptyList())

        @Volatile
        private var closed = false

        fun publish(record: () -> ByteArray) {
            val clients = clientList.value
            if (clients.isEmpty()) return
            val bytes = record()
            clients.forEach { client -> client.slot.trySend(bytes) }
        }

        /**
         * Closes the sockets, the clients, and the job at once. The server sockets close first, so
         * that the ports are free before a client sees the end of its stream. The job stops last,
         * because a cancel of a socket call closes the socket and does not wait for the port.
         */
        fun close() {
            closed = true
            closeSockets()
            job?.cancel()
        }

        private fun closeSockets() {
            val bound = listeners.value
            listeners.value = emptyList()
            bound.forEach { listener -> runCatching { listener.close() } }
            val clients = clientList.value
            clientList.value = emptyList()
            clients.forEach { client ->
                client.slot.close()
                runCatching { client.records.close() }
            }
            publishLists()
        }

        /** Sets the state, only while this run is the current run. */
        private fun setState(transform: (SenderState) -> SenderState) {
            _state.update { state -> if (current === this && !closed) transform(state) else state }
        }

        private fun publishLists() {
            val hosts = listeners.value.map { listener -> hostAndPort(listener.host, listener.port) }
            val clients = clientList.value.map { client -> client.host }
            _listening.update { value -> if (current === this && !closed) hosts else value }
            _clients.update { value -> if (current === this && !closed) clients else value }
        }

        suspend fun serve() {
            while (true) {
                setState { SenderState.Starting }
                val hosts = bindHosts()
                if (hosts.isEmpty()) {
                    fail(DataOutputError(DataOutputErrorType.NoAddress, clock.nowMs(), port = port))
                    continue
                }
                val bound = mutableListOf<ListenSocket>()
                try {
                    var bindError: DataOutputError? = null
                    for (host in hosts) {
                        try {
                            bound += sockets.bind(host, port)
                        } catch (exception: CancellationException) {
                            throw exception
                        } catch (exception: Exception) {
                            val error = DataOutputError(DataOutputErrorType.PortBindFailed, clock.nowMs(), host, port, exception.message)
                            bindError = error
                            onError(error)
                        }
                    }
                    checkActive()
                    if (bound.isEmpty()) {
                        // The loop gave each bind error to onError already.
                        fail(bindError ?: DataOutputError(DataOutputErrorType.PortBindFailed, clock.nowMs(), port = port), report = false)
                        continue
                    }
                    listeners.value = bound.toList()
                    publishLists()
                    updateActivity()
                    val error = acceptAll(bound)
                    closeSockets()
                    fail(error)
                } finally {
                    // A stop in each phase closes the sockets of this phase.
                    bound.forEach { listener -> runCatching { listener.close() } }
                }
            }
        }

        /** Throws when the run stopped while it bound the sockets. */
        private suspend fun checkActive() {
            currentCoroutineContext().ensureActive()
            if (closed) throw CancellationException("The server stopped.")
        }

        /** Runs the accept loop of each socket. Gives the first `accept()` error. */
        private suspend fun acceptAll(bound: List<ListenSocket>): DataOutputError = coroutineScope {
            val failure = CompletableDeferred<DataOutputError>()
            val clientScope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext.job))
            val loops = bound.map { listener ->
                launch {
                    while (true) {
                        val link = try {
                            listener.accept()
                        } catch (exception: CancellationException) {
                            throw exception
                        } catch (exception: Exception) {
                            // A stop closes the socket. That is not an accept() error.
                            checkActive()
                            failure.complete(
                                DataOutputError(DataOutputErrorType.AcceptFailed, clock.nowMs(), listener.host, listener.port, exception.message),
                            )
                            return@launch
                        }
                        clientScope.launch { serveClient(link) }
                    }
                }
            }
            val error = failure.await()
            loops.forEach { loop -> loop.cancel() }
            clientScope.cancel()
            error
        }

        private suspend fun serveClient(link: ByteLink) {
            val records = try {
                security.open(link)
            } catch (exception: CancellationException) {
                link.close()
                throw exception
            } catch (exception: Exception) {
                link.close()
                if (closed) return
                onError(DataOutputError(DataOutputErrorType.HandshakeFailed, clock.nowMs(), link.remoteHost, detail = exception.message))
                return
            }
            val client = Client(link.remoteHost, records)
            clientList.update { list -> list + client }
            if (closed) {
                // The run stopped in the handshake.
                client.slot.close()
                records.close()
            }
            publishLists()
            updateActivity()
            try {
                coroutineScope {
                    val writer = launch {
                        for (record in client.slot) {
                            try {
                                records.send(record)
                                onWrite(true)
                            } catch (exception: CancellationException) {
                                throw exception
                            } catch (exception: Exception) {
                                // A stop closes the client. That is not a write error.
                                if (closed) throw ClientClosedException()
                                onWrite(false)
                                onError(DataOutputError(DataOutputErrorType.PublishFailed, clock.nowMs(), client.host, detail = exception.message))
                                throw ClientClosedException()
                            }
                        }
                    }
                    try {
                        records.drainInbound()
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: Exception) {
                        // A read error closes the client.
                    }
                    writer.cancel()
                }
            } catch (exception: ClientClosedException) {
                // The write failed. The client closes below.
            } finally {
                client.slot.close()
                records.close()
                clientList.update { list -> list - client }
                publishLists()
                updateActivity()
            }
        }

        private suspend fun fail(error: DataOutputError, report: Boolean = true) {
            if (report) onError(error)
            setState { SenderState.Failed(error, error.atMs + retryDelayMs) }
            delay(retryDelayMs)
        }

        private fun updateActivity() {
            val listening = listeners.value.isNotEmpty()
            val hasClients = clientList.value.isNotEmpty()
            setState { state ->
                when (state) {
                    SenderState.Ready, SenderState.Active, SenderState.Starting ->
                        if (!listening) state else if (hasClients) SenderState.Active else SenderState.Ready
                    else -> state
                }
            }
        }
    }

    private class Client(val host: String, val records: RecordLink) {
        val slot = Channel<ByteArray>(Channel.CONFLATED)
    }

    private class ClientClosedException : Exception()

    companion object {
        /** The time from a failure to the new server. */
        const val RETRY_DELAY_MS: Long = 5000L
    }
}
