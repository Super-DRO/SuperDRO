package org.superdro.platform.android.usb

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

/** One open USB serial port. The Android implementation wraps `UsbSerialPort`. */
internal interface UsbPort {
    /** A bulk read with [timeoutMs]. Gives 0 when no data came in the time. Throws an [IOException] at a failure. */
    fun read(buffer: ByteArray, timeoutMs: Int): Int

    /** Writes in chunks of the endpoint packet size, with the total limit [timeoutMs]. Throws an [IOException] at a failure or a timeout. */
    fun write(bytes: ByteArray, timeoutMs: Int)

    /** Closes the port and its connection. */
    fun close()
}

/** The Android USB calls of [UsbSerialTransport]. A JVM unit test gives a fake. */
internal interface UsbPlatform {
    /**
     * Finds the device of [address], opens the port with [settings], and gives it. Throws a
     * [LinkFailureException]: [LinkFailure.DeviceGone] (no such device or port),
     * [LinkFailure.PermissionMissing] (no USB permission), or [LinkFailure.Refused] (the open or the
     * line settings failed).
     */
    fun openPort(address: AdapterAddress.UsbSerial, settings: UsbLineSettings): UsbPort

    /** True while a device of [address] is attached. */
    fun isAttached(address: AdapterAddress.UsbSerial): Boolean
}

/**
 * The USB serial link to one adapter, with
 * usb-serial-for-android.
 *
 * - [open] opens the port with 8N1 and the baud rate of the profile, with no change of a stored
 *   value. DTR and RTS stay false. The failures: [LinkFailure.DeviceGone],
 *   [LinkFailure.PermissionMissing], and [LinkFailure.Refused]. The transport does not try again.
 * - [incoming] makes bulk reads with a timeout of [UsbSerialDefaults.READ_TIMEOUT_MS] into a buffer
 *   of [UsbSerialDefaults.READ_BUFFER_BYTES], on the link dispatcher.
 * - [write] writes in chunks of the endpoint packet size with a total limit of
 *   [UsbSerialDefaults.WRITE_TIMEOUT_MS]. A failure or a timeout is [LinkFailure.Lost].
 * - A read or write failure of a device that goes away (the `USB_DEVICE_DETACHED` case) is
 *   [LinkFailure.DeviceGone]: the session goes to `WaitingForDevice(DeviceGone)`, and the attach of
 *   the same device starts it again through the capability source. Each other failure is
 *   [LinkFailure.Lost].
 * - [close] closes the port. The read loop then ends with no failure.
 *
 * Collect [incoming] one time, after [open].
 *
 * @param linkDispatcher The link dispatcher of the service. The read loop uses one of its
 *   threads while the link is open.
 */
class UsbSerialTransport internal constructor(
    override val address: AdapterAddress.UsbSerial,
    private val platform: UsbPlatform,
    linkDispatcher: CoroutineDispatcher,
    private val writeTimeoutMs: Int = UsbSerialDefaults.WRITE_TIMEOUT_MS,
) : Transport {

    /**
     * Makes a transport for [address] on the USB host of [context].
     *
     * @param linkDispatcher The link dispatcher of the service.
     */
    constructor(
        address: AdapterAddress.UsbSerial,
        context: android.content.Context,
        linkDispatcher: CoroutineDispatcher,
    ) : this(address, AndroidUsbPlatform(context), linkDispatcher)

    /** The line settings of the link: the stored baud rate with no change, 8N1, DTR and RTS false. */
    val lineSettings: UsbLineSettings = UsbLineSettings.of(address)

    private val ioScope = CoroutineScope(SupervisorJob() + linkDispatcher)
    private val stateLock = Mutex()
    private val writeLock = Mutex()

    @Volatile
    private var port: UsbPort? = null

    private val openGuard = Any()
    private var pendingPort: UsbPort? = null
    private var openAbandoned = false

    @Volatile
    private var openStarted = false

    @Volatile
    private var closedByUser = false

    @Volatile
    private var failure: LinkFailure? = null

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
            check(!openStarted) { "A UsbSerialTransport opens only one time. Make a new transport for each link." }
            openStarted = true
        }
        if (closedByUser) throw LinkFailureException(LinkFailure.Refused, "The USB transport for ${address.describe()} is closed.")
        // The open blocks. It runs in the scope of the transport. A port that opens after the caller
        // stopped, or after close(), is closed at once.
        val attempt = ioScope.async {
            val opened = platform.openPort(address, lineSettings)
            synchronized(openGuard) {
                if (openAbandoned) {
                    runCatching { opened.close() }
                    throw CancellationException("The open of ${address.describe()} was abandoned.")
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
            throw LinkFailureException(LinkFailure.Refused, "The USB transport closed during the open.", cancel)
        } catch (openFailure: SecurityException) {
            throw LinkFailureException(LinkFailure.PermissionMissing, "The USB open of ${address.describe()} has no permission.", openFailure)
        } catch (openFailure: Exception) {
            throw LinkFailureException(LinkFailure.Refused, "The USB open of ${address.describe()} failed.", openFailure)
        }
        stateLock.withLock {
            if (closedByUser) {
                runCatching { opened.close() }
                throw LinkFailureException(LinkFailure.Refused, "The USB transport closed during the open.")
            }
            port = opened
        }
    }

    override suspend fun write(bytes: ByteArray) {
        writeLock.withLock {
            failure?.let { cause -> throw LinkFailureException(cause, "The USB link to ${address.describe()} failed.") }
            val openPort = port
            if (openPort == null || closedByUser) {
                throw LinkFailureException(LinkFailure.Lost, "The USB link to ${address.describe()} is not open.")
            }
            if (bytes.isEmpty()) return
            val attempt = ioScope.async { openPort.write(bytes, writeTimeoutMs) }
            val written = try {
                withTimeoutOrNull(writeTimeoutMs.toLong()) {
                    attempt.await()
                    true
                }
            } catch (writeFailure: Exception) {
                if (writeFailure is CancellationException) currentCoroutineContext().ensureActive()
                val cause = classifyFailure()
                throw LinkFailureException(cause, "The USB write to ${address.describe()} failed.", writeFailure)
            }
            if (written == null) {
                // A write that is stuck: the device is usually still there. Check it one time, with no wait.
                val cause = classifyFailure(waitForDetach = false)
                throw LinkFailureException(cause, "The USB write to ${address.describe()} did not end in $writeTimeoutMs ms.")
            }
        }
    }

    override suspend fun close() {
        val openPort = stateLock.withLock {
            if (closedByUser) return
            closedByUser = true
            port
        }
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

    private suspend fun readInto(openPort: UsbPort, chunks: SendChannel<ByteArray>) {
        val buffer = ByteArray(UsbSerialDefaults.READ_BUFFER_BYTES)
        while (currentCoroutineContext().isActive && !closedByUser) {
            val count = try {
                openPort.read(buffer, UsbSerialDefaults.READ_TIMEOUT_MS)
            } catch (readFailure: Exception) {
                if (closedByUser) {
                    chunks.close()
                    return
                }
                val cause = classifyFailure()
                chunks.close(LinkFailureException(cause, "The USB read from ${address.describe()} failed.", readFailure))
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
     * Gives [LinkFailure.DeviceGone] when the device goes away in [UsbSerialDefaults.DETACH_CHECK_MS]
     * (or is gone now, when [waitForDetach] is false), else [LinkFailure.Lost]. Keeps the result for
     * the next calls.
     */
    private suspend fun classifyFailure(waitForDetach: Boolean = true): LinkFailure {
        failure?.let { cause -> return cause }
        var waited = 0L
        var gone = !platform.isAttached(address)
        while (waitForDetach && !gone && waited < UsbSerialDefaults.DETACH_CHECK_MS) {
            delay(UsbSerialDefaults.DETACH_POLL_MS)
            waited += UsbSerialDefaults.DETACH_POLL_MS
            gone = !platform.isAttached(address)
        }
        val cause = if (gone) LinkFailure.DeviceGone else LinkFailure.Lost
        if (failure == null) failure = cause
        return failure ?: cause
    }

    private companion object {
        /** The chunks that wait for the collector. The read waits when the queue is full. */
        const val READ_QUEUE_CHUNKS = 64
    }
}

/** A text for the messages: `"10C4:EA60 #0"`. */
internal fun AdapterAddress.UsbSerial.describe(): String = "%04X:%04X #%d".format(vendorId, productId, portIndex)
