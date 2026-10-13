package org.superdro.platform.android.usb

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import org.superdro.core.connection.Transport
import org.superdro.core.connection.TransportFactory
import org.superdro.core.model.AdapterAddress

/**
 * Makes a [UsbSerialTransport] for each USB serial address.
 *
 * @param linkDispatcher The link dispatcher of the service.
 *   Each open USB link uses one of its threads for the read loop.
 */
class UsbSerialTransportFactory(
    context: Context,
    private val linkDispatcher: CoroutineDispatcher,
) : TransportFactory {

    private val platform = AndroidUsbPlatform(context)

    /** Makes a new transport for [address]. [address] must be [AdapterAddress.UsbSerial]. */
    override fun create(address: AdapterAddress): Transport {
        require(address is AdapterAddress.UsbSerial) { "UsbSerialTransportFactory makes only USB transports, not ${address.kind}." }
        return UsbSerialTransport(address, platform, linkDispatcher)
    }
}
