package org.superdro.core.connection.ble

import dev.bluefalcon.core.BlueFalcon
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.superdro.core.connection.Transport
import org.superdro.core.connection.TransportFactory
import org.superdro.core.model.AdapterAddress

/**
 * Makes a [BleTransport] for each BLE address. The factory is common code. The platform gives
 * only the BlueFalcon engine.
 *
 * @param blueFalcon The one BlueFalcon client of the app. All BLE transports share it.
 * @param dispatcher The dispatcher of the internal collectors of each transport.
 */
class BleTransportFactory(
    private val blueFalcon: BlueFalcon,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : TransportFactory {

    /** Makes a new transport for [address]. [address] must be [AdapterAddress.Ble]. */
    override fun create(address: AdapterAddress): Transport {
        require(address is AdapterAddress.Ble) { "BleTransportFactory makes only BLE transports, not ${address.kind}." }
        return BleTransport(address, blueFalcon, dispatcher)
    }
}
