package org.superdro.core.ui.tabs.remembered

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.DeviceRef
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.TransportKind
import org.superdro.core.store.json.TabStore
import org.superdro.core.store.repository.MachineRepository
import org.superdro.core.ui.presenter.PresenterContext
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.session.Connections

/** The state of a remembered machine in the pane. */
enum class MachineLinkState {
    /** The session is online. */
    Online,

    /** The machine is not paused, but its session is not online. */
    Offline,

    /** The user paused the machine: it does not connect automatically. */
    Paused,
}

/**
 * One row of the remembered machines pane.
 *
 * @property transport The link type of the profile, or null when the profile has no link.
 * @property address The address text, or null when the profile has no link.
 */
@Immutable
data class RememberedMachineRow(
    val profileId: String,
    val name: String,
    val transport: TransportKind?,
    val address: String?,
    val state: MachineLinkState,
)

/**
 * The state of the remembered machines pane.
 *
 * @property confirmRemove The machine of the open remove question, or null.
 */
@Immutable
data class RememberedMachinesUiState(
    val rows: List<RememberedMachineRow> = emptyList(),
    val confirmRemove: RememberedMachineRow? = null,
)

/**
 * The presenter of the remembered machines pane: one row for each remembered
 * machine with its name, link type, address, and state
 * ("online", "offline", or "paused"), and the actions Connect, Pause, Edit, and Remove.
 *
 * "Remove" asks first. It stops the session and removes the machine from the remembered list. The
 * machine profile stays in the machine profiles.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RememberedMachinesPresenter(
    private val connections: Connections,
    private val tabStore: TabStore,
    private val machines: MachineRepository,
    context: PresenterContext,
) : ScopedPresenter(context) {

    private val state = MutableStateFlow(RememberedMachinesUiState())
    private var profiles: Map<String, MachineProfile> = emptyMap()

    /** The state of the pane. */
    val uiState: StateFlow<RememberedMachinesUiState> = state.asStateFlow()

    init {
        val sessionStates = connections.sessions.flatMapLatest { map ->
            if (map.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(map.values.map { session -> session.state.map { value -> session.id to value } }) { pairs -> pairs.toMap() }
            }
        }
        scope.launch {
            combine(tabStore.state, machines.profiles(), sessionStates) { tabs, profileList, states ->
                profiles = profileList.associateBy { profile -> profile.id }
                tabs.remembered.map { machine ->
                    val profile = profiles[machine.profileId]
                    val sessionState = states[DeviceId(machine.profileId)]
                    RememberedMachineRow(
                        profileId = machine.profileId,
                        name = profile?.name ?: machine.profileId,
                        transport = profile?.adapter?.kind,
                        address = profile?.adapter?.let(::addressText),
                        state = when {
                            machine.paused -> MachineLinkState.Paused
                            sessionState?.isOnline == true -> MachineLinkState.Online
                            else -> MachineLinkState.Offline
                        },
                    )
                }
            }.collect { rows ->
                state.update { old -> old.copy(rows = rows, confirmRemove = old.confirmRemove?.let { open -> rows.firstOrNull { it.profileId == open.profileId } }) }
            }
        }
    }

    /** "Connect": the machine is not paused any more, and its session starts. */
    fun connect(profileId: String) {
        tabStore.setPaused(profileId, false)
        val profile = profiles[profileId] ?: return
        val address = profile.adapter ?: return
        connections.connect(DeviceRef(DeviceId(profileId), profile.name, address, profile.twoWayCommunication))
    }

    /** "Pause": the session goes to `Disconnected`, and the machine does not connect automatically. */
    fun pause(profileId: String) {
        tabStore.setPaused(profileId, true)
        connections.disconnect(DeviceId(profileId))
    }

    /** "Remove": opens the question. */
    fun askRemove(profileId: String) = state.update { old -> old.copy(confirmRemove = old.rows.firstOrNull { it.profileId == profileId }) }

    /** The question closes with no change. */
    fun cancelRemove() = state.update { old -> old.copy(confirmRemove = null) }

    /** The answer "Remove": the session stops, and the machine leaves the remembered list. The profile stays. */
    fun confirmRemove() {
        val row = state.value.confirmRemove ?: return
        connections.remove(DeviceId(row.profileId))
        tabStore.forget(row.profileId)
        state.update { old -> old.copy(confirmRemove = null) }
    }
}

/** Gives the address text of a link: the Bluetooth address, the USB IDs, the host and port, or the scenario. */
fun addressText(address: AdapterAddress): String = when (address) {
    // The desktop SPP link has a serial port of the system and can have no MAC address.
    is AdapterAddress.BluetoothSpp -> address.mac.ifBlank { address.serialPort.orEmpty() }
    is AdapterAddress.UsbSerial -> buildString {
        append(hex4(address.vendorId)).append(':').append(hex4(address.productId))
        address.serial?.let { serial -> append(' ').append(serial) }
    }
    is AdapterAddress.Tcp -> if (':' in address.host) "[${address.host}]:${address.port}" else "${address.host}:${address.port}"
    is AdapterAddress.Ble -> address.id
    is AdapterAddress.Fake -> address.scenario
}

private fun hex4(value: Int): String = value.toString(16).padStart(4, '0')
