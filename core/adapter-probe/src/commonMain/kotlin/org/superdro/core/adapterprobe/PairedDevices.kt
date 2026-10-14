package org.superdro.core.adapterprobe

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.superdro.core.connection.DiscoveredAdapter
import org.superdro.core.connection.LinkCapability
import org.superdro.core.connection.PairedListState

/**
 * The paired Bluetooth devices of the Link screen.
 *
 * @property state The state of the list, or null before the first read. [PairedListState.PermissionMissing]
 *   shows the permission request, [PairedListState.RadioOff] shows "Bluetooth is off", and
 *   [PairedListState.NoPaired] shows "No paired device".
 * @property devices The paired devices. The list is empty when the state is not readable.
 */
data class PairedDevices(
    val state: PairedListState? = null,
    val devices: List<DiscoveredAdapter> = emptyList(),
) {
    companion object {

        /** Gives the paired devices from the Bluetooth [capability] and the [paired] list of the discovery. */
        fun of(capability: LinkCapability, paired: List<DiscoveredAdapter>): PairedDevices {
            val state = PairedListState.of(capability, paired)
            return PairedDevices(state, if (state.readable) paired else emptyList())
        }
    }
}

/**
 * The presenter of the paired Bluetooth devices of the Link screen. Without `BLUETOOTH_CONNECT`
 * or with the radio off, the discovery gives an empty list. The [capability] tells the cause, so
 * that the screen does not tell "No paired device" when the tablet has a paired adapter.
 *
 * @param capability The Bluetooth radio and the Bluetooth permission.
 * @param adapters The paired devices of the discovery.
 */
class PairedDevicesPresenter(capability: Flow<LinkCapability>, adapters: Flow<List<DiscoveredAdapter>>, scope: CoroutineScope) {

    /** The paired devices and the state of the list. */
    val state: StateFlow<PairedDevices> =
        combine(capability, adapters, PairedDevices::of).stateIn(scope, SharingStarted.Eagerly, PairedDevices())
}
