package org.superdro.core.connection.tcp

import io.ktor.network.selector.SelectorManager
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
 * All transports of one factory share one Ktor [SelectorManager]. Call [close] when the connection
 * manager stops, to stop the selector.
 *
 * @param dispatcher The dispatcher of the connect, the reads, and the writes. The default is
 *   `Dispatchers.IO`. Android can give the link dispatcher of the service.
 */
class TcpTransportFactory(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : TransportFactory, AutoCloseable {

    private val selector: SelectorManager = SelectorManager(dispatcher)

    /** Makes a new transport for [address]. [address] must be [AdapterAddress.Tcp]. */
    override fun create(address: AdapterAddress): Transport {
        require(address is AdapterAddress.Tcp) { "TcpTransportFactory makes only TCP transports, not ${address.kind}." }
        return TcpTransport(address, selector, dispatcher)
    }

    /** Stops the selector. The transports of this factory cannot connect after this call. */
    override fun close() {
        selector.close()
    }
}
