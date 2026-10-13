package org.superdro.platform.android.bluetooth

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import org.superdro.core.connection.Transport
import org.superdro.core.connection.TransportFactory
import org.superdro.core.model.AdapterAddress

/**
 * Makes an [SppTransport] for each Bluetooth Classic address.
 *
 * @param linkDispatcher The link dispatcher of the service.
 *   Each open SPP link blocks one of its threads with the read. Thus the dispatcher must have one
 *   thread for each open link, and more threads for the writes.
 */
class SppTransportFactory(
    context: Context,
    private val linkDispatcher: CoroutineDispatcher,
) : TransportFactory {

    private val platform = AndroidSppPlatform(context)

    /** Makes a new transport for [address]. [address] must be [AdapterAddress.BluetoothSpp]. */
    override fun create(address: AdapterAddress): Transport {
        require(address is AdapterAddress.BluetoothSpp) { "SppTransportFactory makes only SPP transports, not ${address.kind}." }
        return SppTransport(address, platform, linkDispatcher)
    }
}
