package org.superdro.core.connection.ble

import dev.bluefalcon.core.Uuid
import dev.bluefalcon.core.toUuid
import org.superdro.core.model.TransportKind

/**
 * The Nordic UART Service of a BLE adapter. The BLE
 * transport writes the commands to [RX_CHARACTERISTIC] and reads the adapter messages from the
 * notifications of [TX_CHARACTERISTIC]. The UI does not offer a BLE link now.
 */
object NordicUartService {

    /** The link type of this module. */
    val kind: TransportKind = TransportKind.Ble

    /** The UUID of the service: `6E400001-B5A3-F393-E0A9-E50E24DCCA9E`. */
    const val SERVICE: String = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"

    /** The characteristic that the app writes (the RX of the adapter): `6E400002-...`. */
    const val RX_CHARACTERISTIC: String = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"

    /** The characteristic that the adapter notifies (the TX of the adapter): `6E400003-...`. */
    const val TX_CHARACTERISTIC: String = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"

    /** [SERVICE] as a BlueFalcon UUID. */
    val serviceUuid: Uuid = SERVICE.toUuid()

    /** [RX_CHARACTERISTIC] as a BlueFalcon UUID. */
    val rxUuid: Uuid = RX_CHARACTERISTIC.toUuid()

    /** [TX_CHARACTERISTIC] as a BlueFalcon UUID. */
    val txUuid: Uuid = TX_CHARACTERISTIC.toUuid()
}

/**
 * The values of the BLE link: the open limit, the service wait, the MTU, the MTU wait, and the
 * write limit. The disconnect limit, the check interval, and the wait after a full queue are values
 * of the transport module.
 */
object BleLinkDefaults {

    /** The time limit of [BleTransport.open]: the connect, the discovery, the MTU, and the subscription. */
    const val OPEN_TIMEOUT_MS: Long = 10_000L

    /** The time limit of one write, for all chunks. The same limit as the Bluetooth write watchdog. */
    const val WRITE_TIMEOUT_MS: Long = 4000L

    /** The ATT MTU that the transport asks for. The adapter can give a smaller value. */
    const val REQUESTED_MTU: Int = 517

    /** The time that the transport waits for the MTU exchange. */
    const val MTU_WAIT_MS: Long = 2000L

    /** The time that the transport waits for the services before it asks for a discovery. */
    const val SERVICE_WAIT_MS: Long = 3000L

    /** The time limit of the disconnect in [BleTransport.close]. */
    const val DISCONNECT_TIMEOUT_MS: Long = 2000L

    /** The interval of the checks for the services, the characteristics, and the MTU. */
    const val POLL_INTERVAL_MS: Long = 50L

    /** The wait before a chunk goes again after the engine refused it for a full queue. */
    const val BACKPRESSURE_RETRY_MS: Long = 10L

    /**
     * The notifications that wait for the collector of [BleTransport.incoming]. At a fast BLE
     * link of some hundred notifications each second, this is more than one second of data.
     */
    const val NOTIFICATION_QUEUE: Int = 2048
}
