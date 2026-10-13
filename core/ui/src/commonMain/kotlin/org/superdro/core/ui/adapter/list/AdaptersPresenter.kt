package org.superdro.core.ui.adapter.list

import androidx.compose.runtime.Immutable
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.DeviceSession
import org.superdro.core.connection.SessionState
import org.superdro.core.model.AdapterProfile
import org.superdro.core.model.DeclaredInput
import org.superdro.core.model.MachineProfile
import org.superdro.core.resources.Res
import org.superdro.core.resources.adapter_list_connected
import org.superdro.core.resources.adapter_list_identified
import org.superdro.core.resources.adapter_list_last_seen
import org.superdro.core.resources.adapter_list_legacy
import org.superdro.core.resources.adapter_list_not_identified
import org.superdro.core.resources.adapter_list_this_machine
import org.superdro.core.resources.adapter_list_used_by
import org.superdro.core.store.StoreResult
import org.superdro.core.ui.adapter.AdapterUiDeps
import org.superdro.core.ui.adapter.profile.AdapterProfileActions
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.toUiText

/**
 * One row of the adapters list.
 *
 * @property parts The parts of the row text, joined by " • ".
 * @property connectedDevice The device whose `Connected` session uses the profile, or null.
 */
@Immutable
data class AdapterRowUi(val id: String, val alias: String, val parts: List<UiText>, val connectedDevice: DeviceId?)

/** The dialog of a manual adapter profile for a legacy adapter ("Create New Adapter Profile"). */
@Immutable
data class CreateAdapterUi(
    val name: String = "",
    val declared: Set<DeclaredInput> = emptySet(),
    val error: UiText? = null,
    val busy: Boolean = false,
)

/** The state of the adapters screen. */
@Immutable
data class AdaptersUiState(
    val loaded: Boolean = false,
    val rows: List<AdapterRowUi> = emptyList(),
    val create: CreateAdapterUi? = null,
)

/** The screen that a row tap opens. */
sealed interface AdapterRowTarget {
    /** The Adapter Details of the tab of [deviceId]: the adapter is connected there. */
    data class Online(val deviceId: DeviceId) : AdapterRowTarget

    /** The offline adapter details of the profile [adapterProfileId]. */
    data class Offline(val adapterProfileId: String) : AdapterRowTarget
}

/**
 * The presenter of the adapters screen: one row for each stored adapter profile, "Create New
 * Adapter Profile" (a manual profile for a legacy adapter), and the import of an adapter profile
 * file as a new profile. The list sends no byte to an adapter.
 *
 * @param selectedDevice The device of the selected tab, or null: its row gets "★ This machine profile".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdaptersPresenter(
    private val deps: AdapterUiDeps,
    private val selectedDevice: DeviceId?,
) : ScopedPresenter(deps.context) {

    private val create = MutableStateFlow<CreateAdapterUi?>(null)
    private val messageFlow = MutableSharedFlow<UiText>(extraBufferCapacity = 16)

    /** The import of a file as a new adapter profile. */
    val actions: AdapterProfileActions = AdapterProfileActions(scope, deps, { null }, { text -> messageFlow.tryEmit(text) })

    /** The short messages for the snackbar of the frame. */
    val messages: SharedFlow<UiText> = messageFlow.asSharedFlow()

    private val states: Flow<Map<DeviceId, SessionState>> = deps.connections.sessions.flatMapLatest { sessions -> statesOf(sessions.values.toList()) }

    /** The state of the screen. */
    val uiState: StateFlow<AdaptersUiState> = combine(
        deps.adapters.adapterProfiles(),
        deps.machines.profiles(),
        states,
        create,
    ) { adapters, machines, sessions, dialog ->
        AdaptersUiState(loaded = true, rows = adapters.map { adapter -> rowOf(adapter, machines, sessions) }, create = dialog)
    }.stateIn(scope, SharingStarted.Eagerly, AdaptersUiState())

    /** The screen of a tap on the row [row]. */
    fun targetOf(row: AdapterRowUi): AdapterRowTarget {
        val device = row.connectedDevice
        return if (device != null && device == selectedDevice) AdapterRowTarget.Online(device) else AdapterRowTarget.Offline(row.id)
    }

    /** "Create New Adapter Profile": opens the dialog with the six input check boxes. */
    fun onCreate() {
        scope.launch {
            val name = AdapterProfileActions.uniqueAlias(deps, NEW_NAME, null)
            create.value = CreateAdapterUi(name = name)
        }
    }

    /** The user typed the name of the new profile. */
    fun onCreateName(text: String) = create.update { dialog -> dialog?.copy(name = text, error = null) }

    /** The user changed the check box of [input]. */
    fun onCreateToggle(input: DeclaredInput) = create.update { dialog ->
        dialog?.copy(declared = if (input in dialog.declared) dialog.declared - input else dialog.declared + input)
    }

    /** Writes the new legacy adapter profile. */
    fun onCreateConfirm() {
        val dialog = create.value ?: return
        if (dialog.busy) return
        create.value = dialog.copy(busy = true)
        scope.launch {
            val now = deps.wallClock.now()
            val profile = AdapterProfile(alias = dialog.name, legacy = true, declaredInputs = dialog.declared, createdOn = now)
            when (val result = deps.adapters.saveAdapterProfile(profile)) {
                is StoreResult.Ok -> create.value = null
                is StoreResult.Refused -> create.update { current -> current?.copy(busy = false, error = result.refusal.toUiText()) }
            }
        }
    }

    /** Closes the dialog with no write. */
    fun onCreateDismiss() {
        create.value = null
    }

    private fun statesOf(sessions: List<DeviceSession>): Flow<Map<DeviceId, SessionState>> =
        if (sessions.isEmpty()) {
            flowOf(emptyMap())
        } else {
            combine(sessions.map { session -> session.state.map { state -> session.id to state } }) { pairs -> pairs.toMap() }
        }

    private fun rowOf(adapter: AdapterProfile, machines: List<MachineProfile>, sessions: Map<DeviceId, SessionState>): AdapterRowUi {
        val users = machines.filter { machine -> machine.adapterProfileId == adapter.id }
        val connected = users.map { machine -> DeviceId(machine.id) }.firstOrNull { id -> sessions[id] is SessionState.Connected }
        val parts = buildList {
            when {
                connected != null -> add(UiText.of(Res.string.adapter_list_connected))
                selectedDevice != null && users.any { machine -> machine.id == selectedDevice.value } -> add(UiText.of(Res.string.adapter_list_this_machine))
            }
            if (users.isNotEmpty()) add(UiText.of(Res.string.adapter_list_used_by, users.joinToString(", ") { machine -> machine.name }))
            val identity = adapter.identity
            when {
                adapter.legacy -> add(UiText.of(Res.string.adapter_list_legacy))
                identity != null -> add(UiText.of(Res.string.adapter_list_identified, identity.takeLast(IDENTITY_TAIL)))
                else -> add(UiText.of(Res.string.adapter_list_not_identified))
            }
            adapter.lastSeenOn?.let { seen -> add(UiText.of(Res.string.adapter_list_last_seen, dateTimeText(seen, deps.timeZone))) }
        }
        return AdapterRowUi(adapter.id, adapter.alias, parts, connected)
    }

    companion object {
        /** The number of the last characters of the identity in a row: "Identified (…a1b2c5)". */
        const val IDENTITY_TAIL: Int = 6

        /** The name of a new manual adapter profile. */
        const val NEW_NAME: String = "Legacy adapter"

        /** `yyyy-MM-dd HH:mm` in the local time of [timeZone]. */
        fun dateTimeText(instant: Instant, timeZone: TimeZone): String {
            val local = instant.toLocalDateTime(timeZone)
            fun pad(value: Int, length: Int) = value.toString().padStart(length, '0')
            return "${pad(local.year, 4)}-${pad(local.month.number, 2)}-${pad(local.day, 2)} ${pad(local.hour, 2)}:${pad(local.minute, 2)}"
        }
    }
}
