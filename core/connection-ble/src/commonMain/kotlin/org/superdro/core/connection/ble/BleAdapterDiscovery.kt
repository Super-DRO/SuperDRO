package org.superdro.core.connection.ble

import dev.bluefalcon.core.BlueFalcon
import dev.bluefalcon.core.BluetoothManagerState
import dev.bluefalcon.core.BluetoothPeripheral
import dev.bluefalcon.core.ServiceFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.superdro.core.connection.AdapterDiscovery
import org.superdro.core.connection.DiscoveredAdapter
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.TransportKind

/**
 * The BLE scan for the add-device list. While a collector collects [adapters], the discovery scans
 * for devices that advertise the Nordic UART Service. The scan stops when the collection stops.
 * When the radio goes off and on again, the scan starts again.
 *
 * A scan that fails (for example with no `BLUETOOTH_SCAN` permission) gives no device. The
 * capability source reports the permission and the radio.
 */
class BleAdapterDiscovery(private val blueFalcon: BlueFalcon) : AdapterDiscovery {

    override val kind: TransportKind = TransportKind.Ble

    override val adapters: Flow<List<DiscoveredAdapter>> = channelFlow {
        launch {
            blueFalcon.peripherals
                .map(::discoveredAdapters)
                .distinctUntilChanged()
                .collect { list -> send(list) }
        }
        try {
            blueFalcon.managerState.collectLatest { state ->
                if (state == BluetoothManagerState.Ready) {
                    try {
                        blueFalcon.scan(listOf(ServiceFilter(NordicUartService.serviceUuid)))
                    } catch (scanFailure: Exception) {
                        if (scanFailure is CancellationException) throw scanFailure
                        // No scan: the list stays as it is. The capability source tells the reason.
                    }
                }
            }
        } finally {
            withContext(NonCancellable) {
                try {
                    blueFalcon.stopScanning()
                } catch (_: Exception) {
                    // The scan stopped already, or the facade is closed.
                }
            }
        }
    }

    companion object {
        /**
         * Gives the rows of the add-device list: each peripheral that advertises the Nordic UART
         * Service, or that has no advertised service list (the engine filtered the scan). The name
         * is the advertised name, else the ID. The detail is the ID: the Bluetooth address on
         * Android. The rows are in the order of the name.
         */
        fun discoveredAdapters(peripherals: Collection<BluetoothPeripheral>): List<DiscoveredAdapter> =
            peripherals
                .filter { peripheral ->
                    peripheral.advertisedServiceUUIDs.isEmpty() ||
                        NordicUartService.serviceUuid in peripheral.advertisedServiceUUIDs
                }
                .map { peripheral ->
                    DiscoveredAdapter(
                        address = AdapterAddress.Ble(peripheral.uuid),
                        displayName = peripheral.name?.takeIf { name -> name.isNotBlank() } ?: peripheral.uuid,
                        detail = peripheral.uuid,
                    )
                }
                .sortedWith(compareBy({ adapter -> adapter.displayName.lowercase() }, { adapter -> adapter.detail }))
    }
}
