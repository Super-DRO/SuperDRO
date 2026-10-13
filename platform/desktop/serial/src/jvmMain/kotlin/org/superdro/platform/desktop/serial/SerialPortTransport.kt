package org.superdro.platform.desktop.serial

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.superdro.core.connection.LinkFailure
import org.superdro.core.connection.LinkFailureException
import org.superdro.core.connection.Transport
import org.superdro.core.model.AdapterAddress

/**
 * The link to one adapter through a serial port of the operating system: an SPP adapter through
 * its RFCOMM port, or a USB serial adapter.
 *
 * - [open] finds the port of the address in the port list ([SerialPortRules.portIdFor]) and opens
 *   it with [settings]: 8N1, the stored baud rate of a USB address, and DTR and RTS false. No
 *   port gives [LinkFailure.DeviceGone]. The other failures come from [SerialPorts.open]. The
 *   transport does not try again: the session decides.
 * - [incoming] reads into a buffer of [SerialLineSettings.readBufferBytes] on the link dispatcher.
 *   Each read waits at most [SerialLineSettings.readTimeoutMs].
 * - [write] has the time limit [SerialLineSettings.writeTimeoutMs]: 4000 ms for an SPP link
 *   and 1000 ms for a USB link. A failure or a timeout is [LinkFailure.Lost].
 * - A read or write failure of a port that goes away from the port list (a removed USB cable) is
 *   [LinkFailure.DeviceGone]. Each other failure is [LinkFailure.Lost].
 * - [close] closes the port. The read loop then ends with no failure.
 *
 * Collect [incoming] one time, after [open].
 *
 * @param linkDispatcher The link dispatcher. The read loop holds one of its threads while the
 *   link is open.
 */
class SerialPortTransport(
    override val address: AdapterAddress,
    private val ports: SerialPorts,
    linkDispatcher: CoroutineDispatcher,
    val settings: SerialLineSettings = SerialLineSettings.of(address),
    private val detachCheckMs: Long = DETACH_CHECK_MS,
    private val detachPollMs: Long = DETACH_POLL_MS,
) : Transport {

    init {
        require(address is AdapterAddress.BluetoothSpp || address is AdapterAddress.UsbSerial) {
            "A ${address.kind} address has no serial port."
        }
    }

    private val ioScope = CoroutineScope(SupervisorJob() + linkDispatcher)
    private val stateLock = Mutex()
    private val writeLock = Mutex()

    @Volatile
    private var port: OpenSerialPort? = null

    @Volatile
    private var portId: String? = null

    private val openGuard = Any()
    private var pendingPort: OpenSerialPort? = null
    private var openAbandoned = false

    @Volatile
    private var openStarted = false

    @Volatile
    private var closedByUser = false

    @Volatile
    private var failure: LinkFailure? = null

    /** The port that [open] selected, or null before the open. */
    val openedPortId: String? get() = portId

    override val incoming: Flow<ByteArray> = flow {
        val openPort = port
        if (openPort == null) {
            if (closedByUser) return@flow
            error("Call open() before the collection of incoming.")
        }
        if (closedByUser) return@flow
        val chunks = Channel<ByteArray>(READ_QUEUE_CHUNKS)
        val reader = ioScope.launch { readInto(openPort, chunks) }
        reader.invokeOnCompletion { chunks.close() }
        try {
            for (chunk in chunks) emit(chunk)
        } finally {
            chunks.cancel()
        }
    }

    override suspend fun open() {
        stateLock.withLock {
            check(!openStarted) { "A SerialPortTransport opens only one time. Make a new transport for each link." }
            openStarted = true
        }
        if (closedByUser) throw LinkFailureException(LinkFailure.Refused, "The serial transport of ${describe()} is closed.")
        // The open blocks, for an RFCOMM port during the Bluetooth connect. It runs in the scope of
        // the transport. A port that opens after the caller stopped, or after close(), closes at once.
        val attempt = ioScope.async {
            val id = SerialPortRules.portIdFor(address, ports.list())
                ?: throw LinkFailureException(LinkFailure.DeviceGone, "No serial port for ${describe()}.")
            portId = id
            val opened = ports.open(id, settings)
            synchronized(openGuard) {
                if (openAbandoned) {
                    runCatching { opened.close() }
                    throw CancellationException("The open of ${describe()} was abandoned.")
                }
                pendingPort = opened
            }
            opened
        }
        val opened = try {
            attempt.await()
        } catch (openFailure: LinkFailureException) {
            throw openFailure
        } catch (cancel: CancellationException) {
            abandonOpen()
            currentCoroutineContext().ensureActive()
            throw LinkFailureException(LinkFailure.Refused, "The serial transport closed during the open.", cancel)
        } catch (openFailure: Exception) {
            throw LinkFailureException(LinkFailure.Refused, "The open of ${describe()} failed.", openFailure)
        }
        stateLock.withLock {
            if (closedByUser) {
                runCatching { opened.close() }
                throw LinkFailureException(LinkFailure.Refused, "The serial transport closed during the open.")
            }
            port = opened
        }
    }

    override suspend fun write(bytes: ByteArray) {
        writeLock.withLock {
            failure?.let { cause -> throw LinkFailureException(cause, "The serial link to ${describe()} failed.") }
            val openPort = port
            if (openPort == null || closedByUser) {
                throw LinkFailureException(LinkFailure.Lost, "The serial link to ${describe()} is not open.")
            }
            if (bytes.isEmpty()) return
            val attempt = ioScope.async { openPort.write(bytes) }
            val written = try {
                withTimeoutOrNull(settings.writeTimeoutMs.toLong()) {
                    attempt.await()
                    true
                }
            } catch (writeFailure: Exception) {
                if (writeFailure is CancellationException) currentCoroutineContext().ensureActive()
                val cause = classifyFailure()
                throw LinkFailureException(cause, "The serial write to ${describe()} failed.", writeFailure)
            }
            if (written == null) {
                // A write that is stuck: the port is usually still there. Check it one time, with no wait.
                val cause = classifyFailure(waitForDetach = false)
                throw LinkFailureException(cause, "The serial write to ${describe()} did not end in ${settings.writeTimeoutMs} ms.")
            }
        }
    }

    override suspend fun close() {
        val openPort = stateLock.withLock {
            if (closedByUser) return
            closedByUser = true
            port
        }
        abandonOpen()
        try {
            openPort?.close()
        } catch (_: IOException) {
            // The port is closed already, or the device went away.
        }
        ioScope.cancel()
    }

    private fun abandonOpen() {
        synchronized(openGuard) {
            openAbandoned = true
            pendingPort?.let { opened -> runCatching { opened.close() } }
        }
    }

    private suspend fun readInto(openPort: OpenSerialPort, chunks: SendChannel<ByteArray>) {
        val buffer = ByteArray(settings.readBufferBytes)
        while (currentCoroutineContext().isActive && !closedByUser) {
            val count = try {
                openPort.read(buffer)
            } catch (readFailure: Exception) {
                if (closedByUser) {
                    chunks.close()
                    return
                }
                val cause = classifyFailure()
                chunks.close(LinkFailureException(cause, "The serial read from ${describe()} failed.", readFailure))
                return
            }
            if (count > 0) {
                try {
                    chunks.send(buffer.copyOf(count))
                } catch (_: CancellationException) {
                    return
                }
            }
        }
        chunks.close()
    }

    /**
     * Gives [LinkFailure.DeviceGone] when the port goes away from the port list in [detachCheckMs]
     * (or is gone now, when [waitForDetach] is false), else [LinkFailure.Lost]. Keeps the result for
     * the next calls.
     */
    private suspend fun classifyFailure(waitForDetach: Boolean = true): LinkFailure {
        failure?.let { cause -> return cause }
        var waited = 0L
        var gone = !present()
        while (waitForDetach && !gone && waited < detachCheckMs) {
            delay(detachPollMs)
            waited += detachPollMs
            gone = !present()
        }
        val cause = if (gone) LinkFailure.DeviceGone else LinkFailure.Lost
        failure = cause
        return cause
    }

    private fun present(): Boolean = SerialPortRules.isPresent(address, portId, ports.list())

    private fun describe(): String = when (address) {
        is AdapterAddress.UsbSerial -> "%04X:%04X #%d".format(address.vendorId, address.productId, address.portIndex + 1)
        is AdapterAddress.BluetoothSpp -> address.serialPort ?: address.mac
        else -> address.kind.name
    }

    companion object {
        /** After a failure, the transport looks this long for the end of the port. */
        const val DETACH_CHECK_MS: Long = 500L

        /** The interval of the checks for the end of the port. */
        const val DETACH_POLL_MS: Long = 50L

        private const val READ_QUEUE_CHUNKS = 64
    }
}
