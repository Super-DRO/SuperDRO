package org.superdro.core.connection

import kotlinx.coroutines.flow.Flow
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.TransportKind

/**
 * One byte link to one adapter. A session owns one transport at a time. A new link gets a new
 * transport and a new parser state.
 *
 * Rules for an implementation:
 * 1. The session collects [incoming] on its own dispatcher, with no change of thread. Thus
 *    [incoming] must not block the collector. A read that blocks runs on a thread of the
 *    transport (for example the link dispatcher of the platform module) and gives its chunks
 *    through a channel. A suspending read (for example a Ktor socket) can run in the collector.
 * 2. [close] closes the socket. This stops a blocked read.
 * 3. A failure is a [LinkFailureException]. The transport does not try again.
 *
 * The contract holds no platform type.
 */
interface Transport {

    /** The address of the adapter. */
    val address: AdapterAddress

    /**
     * The bytes from the adapter, in the order of arrival. The flow completes when the link
     * closes. A read failure ends the flow with a [LinkFailureException] ([LinkFailure.Lost]).
     */
    val incoming: Flow<ByteArray>

    /** Opens the link. Throws a [LinkFailureException] when the open fails. */
    suspend fun open()

    /**
     * Writes [bytes] to the adapter. A TCP transport writes one command for each call. Throws a
     * [LinkFailureException] ([LinkFailure.Lost]) when the write fails.
     */
    suspend fun write(bytes: ByteArray)

    /** Closes the link. A second call does nothing. */
    suspend fun close()
}

/**
 * Makes a [Transport] for an address of one link type. The connection
 * manager gets one factory for each [TransportKind]. The TCP factory and the BLE factory are
 * common code. The SPP factory and the USB factory are Android code.
 */
fun interface TransportFactory {
    /** Makes a new transport for [address]. The transport is not open. */
    fun create(address: AdapterAddress): Transport
}
