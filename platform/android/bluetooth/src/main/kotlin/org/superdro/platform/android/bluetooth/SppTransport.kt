package org.superdro.platform.android.bluetooth

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.superdro.core.connection.LinkFailure
import org.superdro.core.connection.LinkFailureException
import org.superdro.core.connection.Transport
import org.superdro.core.model.AdapterAddress

/** One RFCOMM socket. The Android implementation wraps `BluetoothSocket`. */
internal interface SppSocket : Closeable {
    /** Connects. Blocks until the link is open. Throws an [IOException] when the connect fails. */
    fun connect()

    val input: InputStream
    val output: OutputStream
}

/** The Android Bluetooth calls of [SppTransport]. A JVM unit test gives a fake. */
internal interface SppPlatform {
    /** Gives the reason why a link to [mac] cannot open, or null (see [SppRules.linkProblem]). */
    fun linkProblem(mac: String): LinkFailure?

    /** Calls `cancelDiscovery()` only when the app has the permission (see [SppRules.mayCancelDiscovery]). */
    fun cancelDiscoveryIfPermitted()

    /** Makes the insecure RFCOMM socket with the SPP UUID. The socket is not connected. */
    fun createSocket(mac: String): SppSocket
}

/**
 * The Bluetooth Classic SPP link to one adapter.
 *
 * - [open] checks the permission, the radio, and the pairing ([LinkFailure.PermissionMissing],
 *   [LinkFailure.RadioOff], [LinkFailure.DeviceGone]). Then it calls `cancelDiscovery()` only with
 *   the permission, makes the insecure RFCOMM socket with the SPP UUID (no reflection), and makes one
 *   `connect()`. A connect failure is [LinkFailure.Refused]. The transport does not try again.
 * - [incoming] makes a read that blocks, with a buffer of [Spp.READ_BUFFER_BYTES], on the link
 *   dispatcher. A `read()` result less than 0, or an exception, is [LinkFailure.Lost]. The read
 *   runs in the scope of the transport: when the collection stops, the flow ends at once, and the
 *   read stops at [close].
 * - [write] writes and flushes on the link dispatcher. A write that does not end in
 *   [Spp.WRITE_WATCHDOG_MS] is [LinkFailure.Lost]. The transport then closes the socket, so
 *   the read also ends with [LinkFailure.Lost]. A write exception is [LinkFailure.Lost].
 * - While one BEL byte waits or goes out, a second BEL byte is not queued: [write] returns at once
 *   with no write. [isBelPending] tells the session this state.
 * - [close] closes the socket. This stops a read that blocks, and a connect that blocks.
 *
 * The transport makes no thread of its own. An exception on the read path ends only the flow
 * of this transport. Collect [incoming] one time, after [open].
 *
 * @param linkDispatcher The dispatcher of the reads, the writes, and the connect. The read
 *   blocks one thread of it while the link is open.
 */
class SppTransport internal constructor(
    override val address: AdapterAddress.BluetoothSpp,
    private val platform: SppPlatform,
    linkDispatcher: CoroutineDispatcher,
    private val writeWatchdogMs: Long = Spp.WRITE_WATCHDOG_MS,
) : Transport {

    /**
     * Makes a transport for [address] on the Android Bluetooth stack of [context].
     *
     * @param linkDispatcher The link dispatcher of the service.
     */
    constructor(
        address: AdapterAddress.BluetoothSpp,
        context: android.content.Context,
        linkDispatcher: CoroutineDispatcher,
    ) : this(address, AndroidSppPlatform(context), linkDispatcher)

    private val ioScope = CoroutineScope(SupervisorJob() + linkDispatcher)
    private val stateLock = Mutex()
    private val writeLock = Mutex()
    private val belPending = AtomicBoolean(false)

    @Volatile
    private var socket: SppSocket? = null

    @Volatile
    private var connected = false

    @Volatile
    private var openStarted = false

    @Volatile
    private var closedByUser = false

    @Volatile
    private var failure: LinkFailure? = null

    /** True while one BEL byte waits for the write lock or goes out. A second BEL byte is not queued. */
    val isBelPending: Boolean get() = belPending.get()

    override val incoming: Flow<ByteArray> = flow {
        val input = socket?.takeIf { connected }?.input
        if (input == null) {
            if (closedByUser) return@flow
            error("Call open() before the collection of incoming.")
        }
        if (closedByUser) return@flow
        val chunks = Channel<ByteArray>(READ_QUEUE_CHUNKS)
        val reader = ioScope.launch { readInto(input, chunks) }
        // A reader that did not start (the scope stopped) must also end the flow.
        reader.invokeOnCompletion { chunks.close() }
        try {
            for (chunk in chunks) emit(chunk)
        } finally {
            // The collection stopped. The reader stops at its next chunk, or at close().
            chunks.cancel()
        }
    }

    override suspend fun open() {
        stateLock.withLock {
            check(!openStarted) { "An SppTransport opens only one time. Make a new transport for each link." }
            openStarted = true
        }
        if (closedByUser) throw LinkFailureException(LinkFailure.Refused, "The SPP transport for ${address.mac} is closed.")
        val newSocket = try {
            platform.linkProblem(address.mac)?.let { problem ->
                throw LinkFailureException(problem, "The SPP link to ${address.mac} cannot open: $problem.")
            }
            platform.cancelDiscoveryIfPermitted()
            platform.createSocket(address.mac)
        } catch (failure: LinkFailureException) {
            throw failure
        } catch (failure: SecurityException) {
            throw LinkFailureException(LinkFailure.PermissionMissing, "The SPP link to ${address.mac} has no permission.", failure)
        } catch (failure: Exception) {
            throw LinkFailureException(LinkFailure.Refused, "The RFCOMM socket for ${address.mac} failed.", failure)
        }
        stateLock.withLock {
            if (closedByUser) {
                runCatching { newSocket.close() }
                throw LinkFailureException(LinkFailure.Refused, "The SPP transport closed during the open.")
            }
            socket = newSocket
        }
        // The connect blocks. It runs in the scope of the transport, so that a cancel of the caller
        // can close the socket and does not wait for the end of the connect.
        val attempt = ioScope.async { newSocket.connect() }
        try {
            attempt.await()
        } catch (cancel: CancellationException) {
            // The caller stopped, or close() stopped the transport scope. A closed socket ends the
            // connect that blocks.
            closeSocketQuietly()
            currentCoroutineContext().ensureActive()
            throw LinkFailureException(LinkFailure.Refused, "The SPP transport closed during the connect.", cancel)
        } catch (connectFailure: SecurityException) {
            closeSocketQuietly()
            throw LinkFailureException(LinkFailure.PermissionMissing, "The SPP connect to ${address.mac} has no permission.", connectFailure)
        } catch (connectFailure: Exception) {
            closeSocketQuietly()
            throw LinkFailureException(LinkFailure.Refused, "The SPP connect to ${address.mac} failed.", connectFailure)
        }
        stateLock.withLock {
            if (closedByUser) throw LinkFailureException(LinkFailure.Refused, "The SPP transport closed during the connect.")
            connected = true
        }
    }

    override suspend fun write(bytes: ByteArray) {
        val isBel = bytes.size == 1 && bytes[0] == Spp.BEL
        if (isBel && !belPending.compareAndSet(false, true)) return
        try {
            writeLock.withLock { writeNow(bytes) }
        } finally {
            if (isBel) belPending.set(false)
        }
    }

    override suspend fun close() {
        stateLock.withLock {
            if (closedByUser) return
            closedByUser = true
        }
        closeSocketQuietly()
        ioScope.cancel()
    }

    private suspend fun writeNow(bytes: ByteArray) {
        failure?.let { cause -> throw LinkFailureException(cause, "The SPP link to ${address.mac} failed.") }
        val out = socket?.output
        if (!connected || closedByUser || out == null) {
            throw LinkFailureException(LinkFailure.Lost, "The SPP link to ${address.mac} is not open.")
        }
        val attempt = ioScope.async {
            out.write(bytes)
            out.flush()
        }
        val written = try {
            withTimeoutOrNull(writeWatchdogMs) {
                attempt.await()
                true
            }
        } catch (writeFailure: Exception) {
            if (writeFailure is CancellationException) currentCoroutineContext().ensureActive()
            markLost()
            throw LinkFailureException(LinkFailure.Lost, "The SPP write to ${address.mac} failed.", writeFailure)
        }
        if (written == null) {
            // Write stuck for 4000 ms: the link is lost. Close the socket, so that the write
            // thread and the read end.
            markLost()
            closeSocketQuietly()
            throw LinkFailureException(
                LinkFailure.Lost,
                "The SPP write to ${address.mac} did not end in $writeWatchdogMs ms.",
            )
        }
    }

    /** Reads until the end of the link. A failure closes [chunks] with the failure. */
    private suspend fun readInto(input: InputStream, chunks: SendChannel<ByteArray>) {
        val buffer = ByteArray(Spp.READ_BUFFER_BYTES)
        while (true) {
            val count = try {
                input.read(buffer)
            } catch (readFailure: Exception) {
                endRead(chunks, readFailure, "The SPP read from ${address.mac} failed.")
                return
            }
            if (count < 0) {
                endRead(chunks, null, "The adapter ${address.mac} closed the SPP link.")
                return
            }
            if (count > 0) {
                try {
                    chunks.send(buffer.copyOf(count))
                } catch (_: CancellationException) {
                    // close() stopped the scope, or the collection stopped.
                    return
                }
            }
        }
    }

    private fun endRead(chunks: SendChannel<ByteArray>, cause: Exception?, message: String) {
        if (closedByUser) {
            chunks.close()
            return
        }
        markLost()
        chunks.close(LinkFailureException(LinkFailure.Lost, message, cause))
    }

    private fun markLost() {
        if (failure == null) failure = LinkFailure.Lost
    }

    private fun closeSocketQuietly() {
        try {
            socket?.close()
        } catch (_: IOException) {
            // The socket is closed already.
        }
    }

    private companion object {
        /** The chunks that wait for the collector. The read waits when the queue is full. */
        const val READ_QUEUE_CHUNKS = 64
    }
}
