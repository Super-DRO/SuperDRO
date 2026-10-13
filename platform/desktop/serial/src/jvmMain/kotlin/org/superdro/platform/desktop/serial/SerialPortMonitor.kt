package org.superdro.platform.desktop.serial

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.superdro.core.connection.AdapterDiscovery
import org.superdro.core.connection.AttachedUsbDevice
import org.superdro.core.connection.DiscoveredAdapter
import org.superdro.core.connection.TransportFactory
import org.superdro.core.connection.Transport
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.TransportKind

/**
 * Reads the serial ports of the system at an interval for the adapter list and the capability
 * source. jSerialComm has no event for an attach or a detach, so the monitor reads the list again
 * each [intervalMs].
 * A USB adapter that the user attaches is in [attachedUsb] after one interval or less. A session
 * in `WaitingForDevice(DeviceGone)` then starts again.
 *
 * @param source The port list of the system.
 * @param scope The scope of the platform. The monitor runs while it is active.
 * @param intervalMs The time between two reads.
 */
class SerialPortMonitor(
    private val source: SerialPorts,
    scope: CoroutineScope,
    private val intervalMs: Long = POLL_INTERVAL_MS,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val current = MutableStateFlow<List<SerialPortInfo>>(emptyList())
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** The ports of the last read. The value is empty until the first read. */
    val ports: StateFlow<List<SerialPortInfo>> = current.asStateFlow()

    /** The attached USB serial devices for the capability state. */
    val attachedUsb: StateFlow<List<AttachedUsbDevice>> =
        current.map(SerialPortRules::attachedUsb).distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        scope.launch(dispatcher) {
            while (isActive) {
                current.value = source.list()
                withTimeoutOrNull(intervalMs) { wake.receive() }
            }
        }
    }

    /** Reads the ports again now, for example after a request of the user. */
    fun refresh() {
        wake.trySend(Unit)
    }

    companion object {
        /** The time between two reads of the port list. */
        const val POLL_INTERVAL_MS: Long = 2000L
    }
}

/**
 * The adapter list of one link type for the add-device sheet: the RFCOMM ports for
 * [TransportKind.BluetoothSpp], the USB serial ports for [TransportKind.UsbSerial].
 * The flow gives the current list first, then a new list at each change.
 */
class SerialAdapterDiscovery(
    override val kind: TransportKind,
    monitor: SerialPortMonitor,
) : AdapterDiscovery {

    init {
        require(kind == TransportKind.BluetoothSpp || kind == TransportKind.UsbSerial) { "No serial port discovery for $kind." }
    }

    override val adapters: Flow<List<DiscoveredAdapter>> = monitor.ports
        .map { ports -> if (kind == TransportKind.BluetoothSpp) SerialPortRules.sppAdapters(ports) else SerialPortRules.usbAdapters(ports) }
        .distinctUntilChanged()
}

/**
 * Makes a [SerialPortTransport] for each address of one link type: the SPP
 * transport or the USB serial transport of the desktop app.
 *
 * @param linkDispatcher The link dispatcher.
 */
class SerialTransportFactory(
    val kind: TransportKind,
    private val ports: SerialPorts,
    private val linkDispatcher: CoroutineDispatcher,
) : TransportFactory {

    init {
        require(kind == TransportKind.BluetoothSpp || kind == TransportKind.UsbSerial) { "No serial port transport for $kind." }
    }

    /** Makes a new transport for [address]. The link type of [address] must be [kind]. */
    override fun create(address: AdapterAddress): Transport {
        require(address.kind == kind) { "This factory makes only $kind transports, not ${address.kind}." }
        return SerialPortTransport(address, ports, linkDispatcher)
    }
}
