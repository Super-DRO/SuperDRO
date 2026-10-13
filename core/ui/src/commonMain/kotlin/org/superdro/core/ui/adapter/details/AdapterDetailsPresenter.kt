package org.superdro.core.ui.adapter.details

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.superdro.core.connection.AdapterInfo
import org.superdro.core.connection.AdapterRestartState
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.DeviceSession
import org.superdro.core.connection.IdentityCheck
import org.superdro.core.connection.RestartResult
import org.superdro.core.connection.SessionEvent
import org.superdro.core.connection.SessionState
import org.superdro.core.model.AdapterProfile
import org.superdro.core.model.Channels
import org.superdro.core.model.MachineProfile
import org.superdro.core.protocol.AdapterCommand
import org.superdro.core.protocol.ChannelRules
import org.superdro.core.protocol.CommandResult
import org.superdro.core.protocol.InputChannel
import org.superdro.core.protocol.InputMode
import org.superdro.core.protocol.ReferenceState
import org.superdro.core.readout.input.InputSnapshot
import org.superdro.core.resources.Res
import org.superdro.core.resources.adapter_cleared
import org.superdro.core.resources.adapter_commands_off
import org.superdro.core.resources.adapter_two_way_off
import org.superdro.core.resources.connection_restart_failed
import org.superdro.core.resources.readout_no_adapter
import org.superdro.core.resources.readout_waiting_for_adapter
import org.superdro.core.ui.adapter.AdapterTexts
import org.superdro.core.ui.adapter.AdapterUiDeps
import org.superdro.core.ui.adapter.profile.AdapterProfileActions
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.sound.UiSound
import org.superdro.core.ui.text.UiText

/**
 * One channel row of the Adapter Details screen.
 *
 * @property name The row name: "Input X" to "Aux 2", or "Probe" and "Tach" on a declared-input row.
 * @property modes The supported modes in the fixed order of the modes. A declared-input row has none.
 * @property current The current mode of the adapter.
 * @property selection The mode that the user selected. "Save" and "Cancel" show when it is not [current].
 * @property rawValue The count of the channel, the value of `T`, or the probe form.
 * @property refreshRate The refresh rate, for example "50.00 Hz", or null for "N/A" (the probe form).
 * @property busy True while a command of the channel runs: the buttons are disabled.
 * @property reference The last reference mark state of the channel, or null.
 * @property message The failure text of the last command, or null.
 * @property notice A short success text, for example "Counter cleared", or null.
 * @property gear The settings screen of the gear button, or null for no gear.
 */
@Immutable
data class ChannelRowUi(
    val channel: InputChannel,
    val name: StringResource,
    val declared: Boolean,
    val modes: List<InputMode>,
    val current: InputMode,
    val selection: InputMode,
    val rawValue: String,
    val refreshRate: String?,
    val showSave: Boolean,
    val showReference: Boolean,
    val showClear: Boolean,
    val busy: Boolean,
    val reference: ReferenceState?,
    val message: UiText?,
    val notice: UiText?,
    val gear: GearTarget?,
)

/**
 * The state of the Adapter Details screen.
 *
 * @property connection The text of a session that is not `Connected`, or null.
 * @property commandsNote The reason when the commands are off, or null.
 * @property restartNeeded True after a mode write: "Some changes will not take effect until the adapter restarts."
 * @property showRestart True when "Restart Now" shows.
 * @property restartDialog The text of the "Restart failed" dialog, or null.
 */
@Immutable
data class AdapterDetailsUiState(
    val connection: UiText? = null,
    val name: String = AdapterTexts.NOT_VALID,
    val model: String = AdapterTexts.NOT_VALID,
    val bluetoothId: String = AdapterTexts.NOT_VALID,
    val firmwareVersion: String = AdapterTexts.NOT_VALID,
    val firmwareBuild: String = AdapterTexts.NOT_VALID,
    val firmwareDate: String = AdapterTexts.NOT_VALID,
    val adapterAlias: String? = null,
    val hasProfile: Boolean = false,
    val identityDiffers: Boolean = false,
    val commandsAllowed: Boolean = false,
    val commandsNote: UiText? = null,
    val rows: List<ChannelRowUi> = emptyList(),
    val restartNeeded: Boolean = false,
    val showRestart: Boolean = false,
    val restartBusy: Boolean = false,
    val restartState: AdapterRestartState = AdapterRestartState.Idle,
    val restartDialog: UiText? = null,
)

/**
 * The presenter of the Adapter Details screen of one tab.
 *
 * Rules:
 * - At open, the presenter calls [DeviceSession.openAdapterDetails]. The session then queues
 *   `"#get:name;"`, `"#get:version;"`, `"#get:build;"`, `"#get:date;"`, and `#get_mode:` for
 *   `x y z w u v` in this order, and does that again at each change to `Connected` while the
 *   screen is open. At close, the presenter calls [DeviceSession.closeAdapterDetails].
 * - The commands `#set_mode`, `#ref_axis`, `#clear_counter`, and `#reboot` go out only when the
 *   switch "Adapter configuration commands" of the machine profile is on and two-way communication
 *   is on. Else the buttons do not show.
 * - The buttons of a channel are disabled while a command of the channel runs. The end of the
 *   command (also a timeout or an error) enables them again.
 * - The reference mark search plays the short sound when it finishes and the error sound at the
 *   user timeout.
 * - The "Dirty Axes" status does not show.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdapterDetailsPresenter(
    private val deviceId: DeviceId,
    private val deps: AdapterUiDeps,
) : ScopedPresenter(deps.context) {

    private data class Local(
        val selections: Map<InputChannel, InputMode> = emptyMap(),
        val messages: Map<InputChannel, UiText> = emptyMap(),
        val notices: Map<InputChannel, UiText> = emptyMap(),
        val pending: Set<InputChannel> = emptySet(),
        val references: Map<InputChannel, ReferenceState> = emptyMap(),
        val intervals: Map<Char, Long?> = emptyMap(),
        val restartBusy: Boolean = false,
        val restartDialog: UiText? = null,
    )

    private val local = MutableStateFlow(Local())
    private val messageFlow = MutableSharedFlow<UiText>(extraBufferCapacity = 16)
    private var opened: DeviceSession? = null

    private val session: StateFlow<DeviceSession?> =
        deps.session(deviceId).stateIn(scope, SharingStarted.Eagerly, deps.connections.sessions.value[deviceId])

    private val machine: StateFlow<MachineProfile?> = deps.machine(deviceId).stateIn(scope, SharingStarted.Eagerly, null)

    private val adapter: StateFlow<AdapterProfile?> = deps.tabAdapter(deviceId).stateIn(scope, SharingStarted.Eagerly, null)

    private val info: StateFlow<AdapterInfo> =
        session.flatMapLatest { current -> current?.adapterInfo ?: flowOf(AdapterInfo()) }.stateIn(scope, SharingStarted.Eagerly, AdapterInfo())

    private val sessionState: StateFlow<SessionState?> =
        session.flatMapLatest { current -> current?.state ?: flowOf(null) }.stateIn(scope, SharingStarted.Eagerly, null)

    private val snapshot: StateFlow<InputSnapshot> =
        session.flatMapLatest { current -> current?.inputs ?: flowOf(InputSnapshot.EMPTY) }.stateIn(scope, SharingStarted.Eagerly, InputSnapshot.EMPTY)

    /** The copy, export, import, and forget actions of the adapter profile of the tab. */
    val actions: AdapterProfileActions = AdapterProfileActions(scope, deps, { adapter.value }, { text -> messageFlow.tryEmit(text) })

    /** The short messages for the snackbar of the frame. */
    val messages: SharedFlow<UiText> = messageFlow.asSharedFlow()

    /** The state of the screen. */
    val uiState: StateFlow<AdapterDetailsUiState> = combine(
        combine(machine, adapter) { profile, stored -> profile to stored },
        combine(info, sessionState) { adapterInfo, state -> adapterInfo to state },
        snapshot,
        local,
    ) { (profile, stored), (adapterInfo, state), inputs, ui -> stateOf(profile, stored, adapterInfo, state, inputs, ui) }
        .stateIn(scope, SharingStarted.Eagerly, AdapterDetailsUiState())

    init {
        scope.launch {
            session.collect { current ->
                if (current !== opened) {
                    opened?.closeAdapterDetails()
                    current?.openAdapterDetails()
                    opened = current
                }
            }
        }
        scope.launch { session.flatMapLatest { current -> current?.events ?: emptyFlow() }.collect(::onEvent) }
        scope.launch {
            while (isActive) {
                updateIntervals()
                delay(RATE_UPDATE_MS)
            }
        }
    }

    override fun close() {
        opened?.closeAdapterDetails()
        opened = null
        super.close()
    }

    // ---------------------------------------------------------------------------------------
    // Events

    /** The user selected [mode] in the mode list of [channel]. */
    fun onSelectMode(channel: InputChannel, mode: InputMode) =
        local.update { ui -> ui.copy(selections = ui.selections + (channel to mode), messages = ui.messages - channel, notices = ui.notices - channel) }

    /** "Cancel": the selection goes back to the current mode. Nothing goes to the adapter. */
    fun onCancel(channel: InputChannel) = local.update { ui -> ui.copy(selections = ui.selections - channel) }

    /** "Save": sends `#set_mode:<ch>,<id>;`. */
    fun onSave(channel: InputChannel) {
        val row = uiState.value.rows.firstOrNull { item -> item.channel == channel } ?: return
        if (!row.showSave || row.busy || row.selection == InputMode.Unknown) return
        run(channel, AdapterCommand.SetMode(channel, row.selection)) { result ->
            if (result == CommandResult.Completed) local.update { ui -> ui.copy(selections = ui.selections - channel) }
        }
    }

    /** "Reference": sends `#ref_axis:<ch>,15;`. */
    fun onReference(channel: InputChannel) {
        val row = uiState.value.rows.firstOrNull { item -> item.channel == channel } ?: return
        if (!row.showReference || row.busy) return
        run(channel, AdapterCommand.RefAxis(channel)) {}
    }

    /** "Clear": sends `#clear_counter:<ch>;`. */
    fun onClear(channel: InputChannel) {
        val row = uiState.value.rows.firstOrNull { item -> item.channel == channel } ?: return
        if (!row.showClear || row.busy) return
        run(channel, AdapterCommand.ClearCounter(channel)) { result ->
            if (result == CommandResult.Completed) local.update { ui -> ui.copy(notices = ui.notices + (channel to UiText.of(Res.string.adapter_cleared))) }
        }
    }

    /**
     * "Restart Now": sends `#reboot:1000;`. With no session, nothing goes out and
     * the "Restart failed" dialog shows.
     */
    fun onRestart() {
        val current = session.value
        if (current == null) {
            local.update { ui -> ui.copy(restartDialog = UiText.of(Res.string.connection_restart_failed)) }
            return
        }
        if (local.value.restartBusy) return
        local.update { ui -> ui.copy(restartBusy = true) }
        scope.launch {
            val dialog: UiText? = when (val result = current.restartAdapter()) {
                RestartResult.Accepted -> null
                RestartResult.NoSession -> UiText.of(Res.string.connection_restart_failed)
                is RestartResult.NotAccepted ->
                    (result.result as? CommandResult.Failed)?.let { failed -> UiText.Plain(failed.failure.message) }
                        ?: UiText.of(Res.string.connection_restart_failed)
            }
            local.update { ui -> ui.copy(restartBusy = false, restartDialog = dialog) }
        }
    }

    /** Closes the "Restart failed" dialog. */
    fun onRestartDialogDismissed() = local.update { ui -> ui.copy(restartDialog = null) }

    // ---------------------------------------------------------------------------------------
    // Private parts

    private fun run(channel: InputChannel, command: AdapterCommand, done: (CommandResult) -> Unit) {
        val current = session.value
        if (current == null) {
            local.update { ui -> ui.copy(messages = ui.messages + (channel to AdapterDetailsRules.failureText(command, CommandResult.Cancelled)!!)) }
            return
        }
        local.update { ui -> ui.copy(pending = ui.pending + channel, messages = ui.messages - channel, notices = ui.notices - channel) }
        scope.launch {
            val result = current.send(command)
            local.update { ui ->
                val text = AdapterDetailsRules.failureText(command, result)
                ui.copy(pending = ui.pending - channel, messages = if (text == null) ui.messages - channel else ui.messages + (channel to text))
            }
            done(result)
        }
    }

    private fun onEvent(event: SessionEvent) {
        when (event) {
            is SessionEvent.CommandFailed -> {
                val channel = event.command.channel ?: return
                local.update { ui -> ui.copy(messages = ui.messages + (channel to UiText.Plain(event.failure.message))) }
            }
            is SessionEvent.Reference -> {
                local.update { ui -> ui.copy(references = ui.references + (event.channel to event.state)) }
                when (event.state) {
                    ReferenceState.Finished -> deps.sounds.play(UiSound.Short, deviceId)
                    ReferenceState.TimedOut -> deps.sounds.play(UiSound.Error, deviceId)
                    ReferenceState.InProgress, ReferenceState.Cancelled -> Unit
                }
            }
            else -> Unit
        }
    }

    private suspend fun updateIntervals() {
        val current = session.value ?: return
        val now = clock.nowMs()
        val intervals = current.readHistory { history ->
            RATE_INPUTS.associateWith { input ->
                val channel = history.channel(input) ?: return@associateWith null
                val previous = if (channel.size >= 2) channel.timeAt(channel.size - 2) else null
                AdapterDetailsRules.interval(previous, channel.newestTime, now)
            }
        }
        local.update { ui -> ui.copy(intervals = intervals) }
    }

    private fun stateOf(
        profile: MachineProfile?,
        stored: AdapterProfile?,
        adapterInfo: AdapterInfo,
        state: SessionState?,
        inputs: InputSnapshot,
        ui: Local,
    ): AdapterDetailsUiState {
        val values = adapterInfo.values
        val twoWay = adapterInfo.twoWayCommunication && profile?.twoWayCommunication != false
        val configuration = profile?.adapterConfigurationCommands == true
        val connected = state is SessionState.Connected
        val allowed = twoWay && configuration && connected
        val model = AdapterDetailsRules.model(values, stored)
        val rows = if (AdapterDetailsRules.showDeclared(values, stored)) {
            InputChannel.entries.filter { channel -> AdapterDetailsRules.declaredInput(channel) in stored!!.declaredInputs }
                .map { channel -> declaredRow(channel, model, adapterInfo, inputs, ui, allowed) }
        } else {
            InputChannel.entries.map { channel -> modeRow(channel, model, adapterInfo, inputs, ui, allowed) }
        }
        val restartNeeded = adapterInfo.restartNeeded.isNotEmpty()
        return AdapterDetailsUiState(
            connection = when (state) {
                is SessionState.Connected -> null
                is SessionState.Connecting, SessionState.AwaitingData, SessionState.NoData -> UiText.of(Res.string.readout_waiting_for_adapter)
                else -> UiText.of(Res.string.readout_no_adapter)
            },
            name = AdapterDetailsRules.fieldText(values.name.value),
            model = AdapterDetailsRules.fieldText(model),
            bluetoothId = AdapterDetailsRules.fieldText(AdapterDetailsRules.bluetoothId(values, stored)),
            firmwareVersion = AdapterDetailsRules.fieldText(values.version.value),
            firmwareBuild = AdapterDetailsRules.fieldText(values.build.value),
            firmwareDate = AdapterDetailsRules.dateText(values.date.value, deps.timeZone),
            adapterAlias = stored?.alias,
            hasProfile = stored != null,
            identityDiffers = adapterInfo.identityCheck is IdentityCheck.Differs,
            commandsAllowed = allowed,
            commandsNote = when {
                !twoWay -> UiText.of(Res.string.adapter_two_way_off)
                !configuration -> UiText.of(Res.string.adapter_commands_off)
                else -> null
            },
            rows = rows,
            restartNeeded = restartNeeded,
            showRestart = restartNeeded && allowed,
            restartBusy = ui.restartBusy || adapterInfo.restart == AdapterRestartState.Restarting,
            restartState = adapterInfo.restart,
            restartDialog = ui.restartDialog,
        )
    }

    private fun modeRow(channel: InputChannel, model: String?, adapterInfo: AdapterInfo, inputs: InputSnapshot, ui: Local, allowed: Boolean): ChannelRowUi {
        val modes = AdapterDetailsRules.modesOf(adapterInfo.values, channel)
        val current = modes.current
        val supported = ChannelRules.modeList(modes)
        val selection = ui.selections[channel]?.takeIf { mode -> mode in supported } ?: current
        val unchanged = channel !in adapterInfo.restartNeeded && selection == current
        val view = AdapterDetailsRules.rawView(current)
        return ChannelRowUi(
            channel = channel,
            name = AdapterTexts.channelName(channel),
            declared = false,
            modes = supported,
            current = current,
            selection = selection,
            rawValue = AdapterDetailsRules.rawValue(view, channel, inputs),
            refreshRate = AdapterDetailsRules.rateInput(view, channel)?.let { input -> AdapterDetailsRules.refreshRate(ui.intervals[input]) },
            showSave = allowed && ChannelRules.showSave(selection, current),
            showReference = allowed && ChannelRules.showReference(model, channel, current, unchanged),
            showClear = allowed && ChannelRules.showClear(current, unchanged),
            busy = channel in adapterInfo.busyChannels || channel in ui.pending,
            reference = if (channel.referenceAllowed) ui.references[channel] ?: adapterInfo.values.references[channel]?.value else null,
            message = ui.messages[channel],
            notice = ui.notices[channel],
            gear = AdapterDetailsRules.gear(channel, current),
        )
    }

    private fun declaredRow(channel: InputChannel, model: String?, adapterInfo: AdapterInfo, inputs: InputSnapshot, ui: Local, allowed: Boolean): ChannelRowUi {
        val view = AdapterDetailsRules.declaredView(channel)
        val encoder = channel.referenceAllowed
        return ChannelRowUi(
            channel = channel,
            name = AdapterTexts.declaredRowName(channel),
            declared = true,
            modes = emptyList(),
            current = InputMode.Unknown,
            selection = InputMode.Unknown,
            rawValue = AdapterDetailsRules.rawValue(view, channel, inputs),
            refreshRate = AdapterDetailsRules.rateInput(view, channel)?.let { input -> AdapterDetailsRules.refreshRate(ui.intervals[input]) },
            showSave = false,
            // The button shows only when the command can go out (a `tda-` model).
            showReference = allowed && encoder && ChannelRules.isReferenceModel(model),
            showClear = allowed && encoder,
            busy = channel in adapterInfo.busyChannels || channel in ui.pending,
            reference = if (encoder) ui.references[channel] ?: adapterInfo.values.references[channel]?.value else null,
            message = ui.messages[channel],
            notice = ui.notices[channel],
            gear = AdapterDetailsRules.declaredGear(channel),
        )
    }

    companion object {
        /** The time between two updates of the refresh rates. */
        const val RATE_UPDATE_MS: Long = 500L

        /** The inputs of the refresh rates: the six channels and `t`. */
        val RATE_INPUTS: List<Char> = Channels.axisChannels + Channels.T
    }
}
