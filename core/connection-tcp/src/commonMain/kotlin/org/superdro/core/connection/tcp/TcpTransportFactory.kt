package org.superdro.core.connection.tcp

import io.ktor.network.selector.SelectorManager
import kotlin.jvm.Synchronized
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import org.superdro.core.connection.Transport
import org.superdro.core.connection.TransportFactory
import org.superdro.core.model.AdapterAddress

/**
 * Makes a [TcpTransport] for each Wi-Fi TCP address.
 * The factory is common code: Android and the desktop JVM need no platform code for the TCP link.
 *
 * All transports of one factory share one Ktor [SelectorManager]. The factory makes the selector
 * at the first connect, on the dispatcher of the connect. The make of a selector reads the class
 * path (`SelectorProvider`). Thus it must not occur when the DI graph starts on the main thread.
 * Call [close] when the connection manager stops, to stop the selector.
 *
 * @param dispatcher The dispatcher of the connect, the reads, and the writes. The default is
 *   `Dispatchers.IO`. Android can give the link dispatcher of the service.
 * @param makeSelector Makes the selector. A test can count the calls.
 */
class TcpTransportFactory internal constructor(
    private val dispatcher: CoroutineDispatcher,
    private val makeSelector: () -> SelectorManager,
) : TransportFactory, AutoCloseable {

    /** Makes a factory with a Ktor selector on [dispatcher]. */
    constructor(dispatcher: CoroutineDispatcher = Dispatchers.IO) : this(dispatcher, { SelectorManager(dispatcher) })

    private var selector: SelectorManager? = null
    private var closed = false

    /** Makes a new transport for [address]. [address] must be [AdapterAddress.Tcp]. */
    override fun create(address: AdapterAddress): Transport {
        require(address is AdapterAddress.Tcp) { "TcpTransportFactory makes only TCP transports, not ${address.kind}." }
        return TcpTransport(address, TcpConnector { host, port -> KtorTcpConnector(selector()).connect(host, port) }, dispatcher)
    }

    /** Stops the selector. The transports of this factory cannot connect after this call. */
    @Synchronized
    override fun close() {
        closed = true
        selector?.close()
    }

    /** Gives the one selector of the factory. The first call makes it. */
    @Synchronized
    private fun selector(): SelectorManager {
        check(!closed) { "The TCP transport factory is closed." }
        return selector ?: makeSelector().also { made -> selector = made }
    }
}
