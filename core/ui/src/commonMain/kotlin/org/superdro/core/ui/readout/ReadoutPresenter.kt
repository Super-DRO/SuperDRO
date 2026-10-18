package org.superdro.core.ui.readout

import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.math.floor
import kotlin.math.pow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.DeviceSession
import org.superdro.core.connection.SessionState
import org.superdro.core.functions.compensation.ToolCompensation
import org.superdro.core.functions.graphics.PositionSlider
import org.superdro.core.model.AdapterProfile
import org.superdro.core.model.AxisButtonMode
import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.ProbeProfile
import org.superdro.core.model.SidePanelMode
import org.superdro.core.model.TachometerMode
import org.superdro.core.model.Tool
import org.superdro.core.model.Workspace
import org.superdro.core.readout.FeedValues
import org.superdro.core.readout.ReadoutFrame
import org.superdro.core.readout.ReadoutPatterns
import org.superdro.core.readout.expression.ExpressionError
import org.superdro.core.readout.expression.ExpressionEvaluator
import org.superdro.core.readout.expression.ExpressionResult
import org.superdro.core.readout.input.AxisValueStatus
import org.superdro.core.readout.input.ChannelStatus
import org.superdro.core.readout.input.InputSnapshot
import org.superdro.core.readout.operations.ActionButtons
import org.superdro.core.readout.operations.AxisOperations
import org.superdro.core.readout.operations.Divisions
import org.superdro.core.readout.operations.GlobalButtons
import org.superdro.core.readout.operations.OperationRequest
import org.superdro.core.readout.operations.OperationResult
import org.superdro.core.readout.operations.TachometerActions
import org.superdro.core.readout.operations.ZeroButtons
import org.superdro.core.resources.Res
import org.superdro.core.resources.readout_hint_add_point
import org.superdro.core.resources.readout_hint_clear
import org.superdro.core.resources.readout_hint_hold
import org.superdro.core.resources.readout_hint_zero
import org.superdro.core.resources.readout_no_adapter
import org.superdro.core.resources.readout_undo_absolute_zero
import org.superdro.core.resources.readout_undo_all_absolute_zero
import org.superdro.core.resources.readout_undo_all_clear
import org.superdro.core.resources.readout_undo_all_zero
import org.superdro.core.resources.readout_undo_center
import org.superdro.core.resources.readout_undo_clear
import org.superdro.core.resources.readout_undo_compensation
import org.superdro.core.resources.readout_undo_half
import org.superdro.core.resources.readout_undo_hold
import org.superdro.core.resources.readout_undo_set
import org.superdro.core.resources.readout_undo_zero
import org.superdro.core.resources.readout_waiting_for_adapter
import org.superdro.core.store.json.AppSettingsStore
import org.superdro.core.store.repository.AdapterRepository
import org.superdro.core.store.repository.MachineRepository
import org.superdro.core.store.repository.ProbeRepository
import org.superdro.core.store.repository.ToolRepository
import org.superdro.core.ui.navigation.AxisDetailsRoute
import org.superdro.core.ui.navigation.DivisionsRoute
import org.superdro.core.ui.navigation.EncoderSettingsRoute
import org.superdro.core.ui.navigation.KeypadRoute
import org.superdro.core.ui.navigation.TabReadoutPresenter
import org.superdro.core.ui.navigation.TabRoute
import org.superdro.core.ui.navigation.TachometerPresetRoute
import org.superdro.core.ui.graphics.proximity.ProximityToneRunner
import org.superdro.core.ui.presenter.PresenterContext
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.session.Connections
import org.superdro.core.ui.sound.SoundController
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.toUiText
import org.superdro.core.resources.fns_undo_compound_clear
import org.superdro.core.resources.fns_undo_head_tilt_clear

/**
 * The presenter of the readout screen of one tab.
 * The tab owns it ([org.superdro.core.ui.navigation.TabEntryScope.readout]), so the sheets of the
 * tab and the function screens of the other areas use the same object.
 *
 * Data flow: two paths, so that a new position does not make the main UI state again.
 * - The stored path: the machine profile with its active workspace, the hold values, and the
 *   instrument give the stored frame (a [ReadoutFrame] with no inputs). The stored frame, the
 *   session state, and the local state give [uiState].
 * - The live path: the session inputs become a key with the axis sums, the statuses, and the
 *   projections (no time), so a sample with the same values does not continue. One collector
 *   makes the [frame] from the stored frame and the key, and then the value of each axis and
 *   the RPM text from that one frame.
 *
 * An operation of `core:readout` gives a new workspace. The presenter writes the changed fields
 * in one write transaction of [MachineRepository]. It sends no adapter command. An action at a
 * press uses [latestFrame]: the frame with the newest inputs of the session.
 *
 * Rules:
 * - The tab keeps the last values while the session is not online.
 * - An operation that needs a position needs a `Connected` session.
 * - Each offset change shows the snackbar with "Undo" for [UNDO_MS]. The undo
 *   restores all offsets of the action.
 * - A one-time hint shows after the action, and never blocks the action.
 * - In the tachometer modes "preset" and "direct entry" no input exists: `R` = 0.
 *
 * @param deviceId The device of the tab: the ID of its machine profile.
 * @param context The context of the presenter scope. The sheets of the tab use it for their own
 *   presenters.
 * @param probes The probe profiles, for the reference instrument of the measurement mode.
 *   Null gives no instrument.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalAtomicApi::class)
class ReadoutPresenter(
    override val deviceId: DeviceId,
    connections: Connections,
    private val machines: MachineRepository,
    adapters: AdapterRepository,
    tools: ToolRepository,
    private val appSettings: AppSettingsStore,
    private val sounds: SoundController,
    val context: PresenterContext,
    probes: ProbeRepository? = null,
) : ScopedPresenter(context), TabReadoutPresenter {

    private val profileId: String = deviceId.value
    private val ids = AtomicLong(0L)
    private val hold = MutableStateFlow<Map<AxisId, Double>>(emptyMap())
    private val local = MutableStateFlow(LocalState())
    @Volatile
    private var lastOnline: InputSnapshot = InputSnapshot.EMPTY

    /** The snapshot that the live path used last: online, or the last online one. */
    @Volatile
    private var lastShown: InputSnapshot = InputSnapshot.EMPTY
    private var undoTimer: Job? = null
    private var hintTimer: Job? = null
    private var messageTimer: Job? = null
    private var pendingTap: Job? = null

    private val session: StateFlow<DeviceSession?> = connections.sessions
        .map { sessions -> sessions[deviceId] }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, connections.sessions.value[deviceId])

    override val sessionState: StateFlow<SessionState?> = session
        .flatMapLatest { current -> current?.state ?: flowOf(null) }
        .stateIn(scope, SharingStarted.Eagerly, session.value?.state?.value)

    private val profile: StateFlow<MachineProfile?> = machines.profile(profileId).stateIn(scope, SharingStarted.Eagerly, null)

    private val workspace: StateFlow<Workspace?> = machines.activeWorkspace(profileId).stateIn(scope, SharingStarted.Eagerly, null)

    private val adapter: StateFlow<AdapterProfile?> = profile
        .map { current -> current?.adapterProfileId }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(null) else adapters.adapterProfile(id) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    private val library: StateFlow<List<Tool>> = tools.library(profileId).stateIn(scope, SharingStarted.Eagerly, emptyList())

    // The reference instrument of the measurement mode: its X and Y offsets add to the
    // X and Y readouts. The graphics area selects it (ProbeRoles.measurementInstrumentProfileId).
    private val instrument: StateFlow<ProbeProfile?> = profile
        .map { current -> current?.probeRoles?.measurementInstrumentProfileId }
        .distinctUntilChanged()
        .flatMapLatest { id ->
            if (id == null || probes == null) flowOf(null) else probes.library(profileId).map { list -> list.firstOrNull { probe -> probe.id == id } }
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * The stored frame: the profile, the active workspace, the hold values, and the instrument,
     * with no inputs. Null until the profile and the workspace load.
     */
    private val stored: StateFlow<ReadoutFrame?> = combine(profile, workspace, hold, instrument) { current, active, held, probe ->
        if (current == null || active == null) null else ReadoutFrame(current, active, emptyMap(), probe, held)
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * The inputs of the session as a [LiveInputs] key. The tab keeps the last online inputs while
     * the session is not online. A new snapshot with the same key (for example only a new time)
     * stops here.
     */
    private val liveInputs: Flow<LiveInputs> = session
        .flatMapLatest { current ->
            if (current == null) flowOf(LiveInputs.of(null, lastOnline)) else combine(current.state, current.inputs, ::shownInputs)
        }
        .distinctUntilChanged()

    private val _frame = MutableStateFlow<ReadoutFrame?>(null)
    private val axisValues: Map<AxisId, MutableStateFlow<AxisValueUiState>> =
        AxisId.entries.associateWith { axis -> MutableStateFlow(AxisValueUiState(axis)) }
    private val _rpmText = MutableStateFlow<String?>(null)

    override val frame: StateFlow<ReadoutFrame?> = _frame.asStateFlow()

    /**
     * The RPM text of the tachometer for the status strip, or null when the tachometer is off. It
     * changes with each new RPM value, so only the RPM chip reads it.
     */
    val rpmText: StateFlow<String?> = _rpmText.asStateFlow()

    /** The axes of the proximity target of the active workspace. The axis details sheet shows its switch with them. */
    val proximityAxes: StateFlow<Set<AxisId>> = workspace
        .map { active -> active?.proximity?.axes.orEmpty().toSet() }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    init {
        // The live path: one collector gives the frame, the axis values, and the RPM text of one
        // set of inputs, so the values of the axes always agree with the frame.
        scope.launch {
            combine(stored, liveInputs) { base, inputs -> base to inputs }.collect { (base, inputs) -> onLive(base, inputs) }
        }
        // The proximity warning tone of the tab: the graphics area gives the runner.
        ProximityToneRunner(frame, sounds, deviceId, clock).start(scope)
    }

    /** The UI state of the readout screen. The values of the axes are in [axisValue], the RPM is in [rpmText]. */
    val uiState: StateFlow<ReadoutUiState> = combine(
        stored,
        sessionState,
        adapter,
        library,
        combine(appSettings.settings, local) { settings, state -> settings to state },
    ) { current, state, adapterProfile, tools, (settings, ui) ->
        val globalFraction = settings.readoutPaneFraction.coerceIn(READOUT_PANE_MIN_FRACTION, READOUT_PANE_MAX_FRACTION)
        val globalWeight = settings.graphicalPaneWeight.coerceIn(GRAPHICAL_PANE_MIN_WEIGHT, GRAPHICAL_PANE_MAX_WEIGHT)
        if (current == null) {
            ReadoutUiState(
                deviceId = deviceId,
                readoutPaneFraction = globalFraction,
                graphicalPaneWeight = globalWeight,
                undo = ui.undo,
                message = ui.message,
                hint = ui.hint,
                hintAnchorTag = ui.anchorTag,
            )
        } else {
            val perMachine = settings.paneSizeScope == org.superdro.core.model.PaneSizeScope.EachMachine
            val fraction = if (perMachine) {
                current.profile.display.readoutPaneFraction.coerceIn(READOUT_PANE_MIN_FRACTION, READOUT_PANE_MAX_FRACTION)
            } else {
                globalFraction
            }
            val weight = if (perMachine) {
                current.profile.display.graphicalPaneWeight.coerceIn(GRAPHICAL_PANE_MIN_WEIGHT, GRAPHICAL_PANE_MAX_WEIGHT)
            } else {
                globalWeight
            }
            uiStateOf(
                current = current,
                state = state,
                adapterProfile = adapterProfile,
                tools = tools,
                showReminder = settings.showSetupReminder,
                placement = settings.graphicalViewInPortrait,
                readoutPaneFraction = fraction,
                graphicalPaneWeight = weight,
                ui = ui,
            )
        }
    }.stateIn(scope, SharingStarted.Eagerly, ReadoutUiState(deviceId))

    /** The value of [axis]. Each row collects only the flow of its own axis. */
    fun axisValue(axis: AxisId): StateFlow<AxisValueUiState> = axisValues.getValue(axis)

    /**
     * Gives the frame with the newest inputs of the session: [frame] can be one display frame
     * older. While the session is not online, it gives [frame].
     */
    override fun latestFrame(): ReadoutFrame? {
        val current = frame.value ?: return null
        val live = session.value ?: return current
        val state = live.state.value
        if (!state.isOnline) return current
        return buildFrame(current.with(hold = hold.value), LiveInputs.of(state, live.currentInputs()))
    }

    // ---------------------------------------------------------------------------------------
    // TabReadout

    override fun positionUnavailable(): UiText? = when (sessionState.value) {
        is SessionState.Connected -> null
        is SessionState.Connecting, SessionState.AwaitingData, SessionState.NoData -> UiText.of(Res.string.readout_waiting_for_adapter)
        else -> UiText.of(Res.string.readout_no_adapter)
    }

    override fun apply(result: OperationResult, label: UiText?) {
        hold.value = result.hold
        val current = frame.value ?: return
        commit(current, complete(current, current.workspace), complete(current, result.workspace), label, writeAll = false)
    }

    override fun applyWorkspace(label: UiText?, transform: (Workspace) -> Workspace) {
        val current = frame.value ?: return
        val base = complete(current, current.workspace)
        commit(current, base, complete(current, transform(base)), label, writeAll = true, transform = transform)
    }

    /**
     * Shows [text] in the message tooltip of the readout for [MESSAGE_MS]. A screen entry of a
     * different area uses `TabEntryScope.showMessage` (the snackbar of the frame), because a
     * screen hides the readout.
     */
    override fun showMessage(text: UiText) = showMessage(text, anchorTag = null)

    /** Shows [text] in the message tooltip anchored to [anchorTag] for [MESSAGE_MS]. */
    fun showMessage(text: UiText, anchorTag: String?) {
        val id = nextId()
        local.update { state -> state.copy(message = MessageUiState(id, text), anchorTag = anchorTag ?: state.anchorTag) }
        messageTimer?.cancel()
        messageTimer = scope.launch {
            delay(MESSAGE_MS)
            onMessageShown(id)
        }
    }

    /** The screen showed the message [id]. */
    fun onMessageShown(id: Long) {
        local.update { state ->
            if (state.message?.id == id) {
                state.copy(message = null, anchorTag = if (state.hint == null) null else state.anchorTag)
            } else {
                state
            }
        }
    }

    /** Dismisses the currently shown hint or info message tooltip immediately. */
    fun onDismissMessage() {
        hintTimer?.cancel()
        messageTimer?.cancel()
        local.update { state -> state.copy(message = null, hint = null, anchorTag = null) }
    }

    // ---------------------------------------------------------------------------------------
    // The rows

    /**
     * A tap on the value of [axis]: the keypad. A tap does not change a value. The "Error"
     * state opens the encoder settings of the input. The tachometer row follows its mode.
     * Gives the route to open, or null.
     */
    fun onValueTap(axis: AxisId): TabRoute? {
        val current = frame.value ?: return null
        val value = axisValue(axis).value
        if (value.state == ValueState.Error) return value.errorInput?.let(::EncoderSettingsRoute)
        if (axis == AxisId.Tachometer) return onTachometerTap(current, valueTag(AxisId.Tachometer))
        return KeypadRoute(axis)
    }

    /** A tap on the label of [axis]: the axis details. */
    fun onLabelTap(axis: AxisId): TabRoute? {
        val current = frame.value ?: return null
        return if (axis == AxisId.Tachometer) onTachometerTap(current, rowTag(AxisId.Tachometer)) else AxisDetailsRoute(axis)
    }

    /** A tap on an action button of [axis] with [mode]. Gives the route to open, or null. */
    fun onActionTap(axis: AxisId, mode: AxisButtonMode): TabRoute? {
        val current = latestFrame() ?: return null
        val label = labelOf(current.profile, axis)
        val tag = actionTag(axis, mode)
        when (mode) {
            AxisButtonMode.SetZero -> {
                if (!requirePosition(tag)) return null
                apply(ZeroButtons.tap(current, axis), UiText.of(Res.string.readout_undo_zero, label))
                if (!current.profile.display.shortPressZero) showHint(Hint.ZeroLongPress, tag)
            }
            AxisButtonMode.ToggleIncremental -> {
                apply(AxisOperations.toggleMode(current, axis), null)
                showHint(Hint.ClearLongPress, tag)
            }
            AxisButtonMode.ToggleUnits -> {
                val result = ActionButtons.toggleUnits(current, axis)
                if (result.request == OperationRequest.OpenDivisionsSheet) return DivisionsRoute
                apply(result, null)
            }
            AxisButtonMode.SetCenter -> {
                if (!requirePosition(tag)) return null
                apply(AxisOperations.half(current, axis), UiText.of(Res.string.readout_undo_half, label))
            }
            AxisButtonMode.DivideCircle -> return if (axis == AxisId.Angular) DivisionsRoute else null
            AxisButtonMode.Hold -> {
                if (!requirePosition(tag)) return null
                val first = current.hold[axis] == null
                apply(AxisOperations.holdTap(current, axis), UiText.of(Res.string.readout_undo_hold, label))
                if (first) showHint(Hint.HoldLongPress, tag)
            }
        }
        return null
    }

    /** A long press on an action button of [axis] with [mode]. */
    fun onActionLongPress(axis: AxisId, mode: AxisButtonMode) {
        val current = latestFrame() ?: return
        val label = labelOf(current.profile, axis)
        val tag = actionTag(axis, mode)
        when (mode) {
            AxisButtonMode.SetZero -> {
                if (current.profile.display.shortPressZero) return
                if (!requirePosition(tag)) return
                apply(ZeroButtons.longPress(current, axis), UiText.of(Res.string.readout_undo_absolute_zero, label))
            }
            AxisButtonMode.ToggleIncremental ->
                apply(ActionButtons.longPress(current, axis, mode), UiText.of(Res.string.readout_undo_clear, label))
            AxisButtonMode.Hold -> apply(AxisOperations.holdLongPress(current, axis), null)
            AxisButtonMode.ToggleUnits, AxisButtonMode.SetCenter, AxisButtonMode.DivideCircle -> Unit
        }
    }

    /** Center in the absolute mode (the axis details): `L(axis, shown / 2)`. */
    fun onCenterAbsolute(axis: AxisId) {
        val current = latestFrame() ?: return
        if (!requirePosition()) return
        apply(AxisOperations.center(current, axis), UiText.of(Res.string.readout_undo_center, labelOf(current.profile, axis)))
    }

    // ---------------------------------------------------------------------------------------
    // The global buttons

    /** The global Zero Set button, tap. */
    fun onZeroSetTap() {
        val current = latestFrame() ?: return
        if (!requirePosition(ZERO_SET_TAG)) return
        apply(ZeroButtons.globalTap(current), UiText.of(Res.string.readout_undo_all_zero))
        if (!current.profile.display.shortPressZero) showHint(Hint.ZeroLongPress, ZERO_SET_TAG)
    }

    /** The global Zero Set button, long press: no action with `short_press_zero_set` on. */
    fun onZeroSetLongPress() {
        val current = latestFrame() ?: return
        if (current.profile.display.shortPressZero) return
        if (!requirePosition(ZERO_SET_TAG)) return
        apply(ZeroButtons.globalLongPress(current), UiText.of(Res.string.readout_undo_all_absolute_zero))
    }

    /** The global Mode button, tap. */
    fun onModeTap() {
        val current = latestFrame() ?: return
        apply(GlobalButtons.modeTap(current), null)
        showHint(Hint.ClearLongPress, MODE_TAG)
    }

    /** The global Mode button, long press: clears the offsets of the mode (no action in mixed modes). */
    fun onModeLongPress() {
        val current = latestFrame() ?: return
        apply(GlobalButtons.modeLongPress(current), UiText.of(Res.string.readout_undo_all_clear))
    }

    /** The global Units button, tap. */
    fun onUnitsTap() {
        val current = latestFrame() ?: return
        apply(GlobalButtons.unitsTap(current), null)
    }

    // ---------------------------------------------------------------------------------------
    // The function strip

    /**
     * A tap on the function button [button]. The radius or diameter button toggles the X display.
     * Each other button gives the route of its area.
     */
    fun onFunctionTap(button: FunctionButton): TabRoute? {
        if (button == FunctionButton.RadiusDiameter) {
            applyWorkspace(null) { workspace ->
                val x = workspace.axisStates[AxisId.X] ?: AxisState()
                workspace.copy(axisStates = workspace.axisStates + (AxisId.X to x.copy(diameterMode = !x.diameterMode)))
            }
            return null
        }
        return FunctionStripRules.route(button)
    }

    /**
     * A long press on the function button [button]. An active compensation button clears the
     * compensation at once, with no question and with undo. The compound slide
     * and the head tilt buttons clear their projection: the angle and the switches stay stored.
     * Gives a route to open, or null.
     */
    fun onFunctionLongPress(button: FunctionButton): TabRoute? {
        val state = uiState.value.functions.firstOrNull { item -> item.button == button }
        if (state != null && state.active && FunctionStripRules.isCompensation(button)) {
            val undo = when (button) {
                FunctionButton.CompoundCompensation -> Res.string.fns_undo_compound_clear
                FunctionButton.HeadTiltCompensation -> Res.string.fns_undo_head_tilt_clear
                else -> Res.string.readout_undo_compensation
            }
            val clear: (Workspace) -> Workspace = when (button) {
                FunctionButton.RadiusCompensation -> ToolCompensation::clearRadius
                FunctionButton.HeightCompensation -> ToolCompensation::clearHeight
                FunctionButton.CompoundCompensation -> { workspace -> workspace.copy(compoundProjection = workspace.compoundProjection.copy(active = false)) }
                FunctionButton.HeadTiltCompensation -> { workspace -> workspace.copy(headTilt = workspace.headTilt.copy(active = false)) }
                else -> ToolCompensation::clearLatheOffset
            }
            applyWorkspace(UiText.of(undo), clear)
            return null
        }
        return FunctionStripRules.longPressRoute(button)
    }

    // ---------------------------------------------------------------------------------------
    // The tachometer

    /** The RPM or SFM button of the tachometer row. */
    fun onTachometerToggle() {
        val current = latestFrame() ?: return
        apply(ActionButtons.toggleUnits(current, AxisId.Tachometer), null)
    }

    /** The preset list of the preset sheet. It always starts with 0. */
    fun presets(): List<Double> = TachometerActions.presetList(frame.value?.profile?.tachometer?.presets.orEmpty())

    /** Selects the preset [rpm]. */
    fun onPresetSelected(rpm: Double) {
        val current = latestFrame() ?: return
        apply(TachometerActions.selectPreset(current, rpm), UiText.of(Res.string.readout_undo_set, labelOf(current.profile, AxisId.Tachometer)))
    }

    private fun onTachometerTap(current: ReadoutFrame, anchorTag: String? = null): TabRoute? = when (val request = TachometerActions.tap(current).request) {
        is OperationRequest.ShowMessage -> {
            showMessage(request.message.toUiText(), anchorTag)
            null
        }
        is OperationRequest.OpenPresetSheet -> TachometerPresetRoute
        is OperationRequest.OpenKeypad -> KeypadRoute(request.axis)
        else -> null
    }

    // ---------------------------------------------------------------------------------------
    // The keypad and the divisions

    /** The display value of [axis], or NaN. The relative entries `++x` and `--x` add to it. */
    fun currentDisplay(axis: AxisId): Double = latestFrame()?.reading(axis)?.display ?: Double.NaN

    /** The live result of the keypad text [text] for [axis], or null for an empty text. */
    fun keypadPreview(axis: AxisId, text: String): ExpressionResult? =
        if (text.isBlank()) null else ExpressionEvaluator.evaluate(text, currentDisplay(axis))

    /**
     * Sets the keypad text [text] on [axis]: `i(V)` in incremental mode, `h(V)` in absolute mode.
     * Gives null when the value is set, or the text of the refusal: the live check
     * or the error of the expression.
     */
    fun onKeypadSet(axis: AxisId, text: String): UiText? {
        val current = latestFrame() ?: return UiText.of(Res.string.readout_no_adapter)
        if (needsPosition(current, axis)) {
            positionUnavailable()?.let { refusal ->
                sounds.error(deviceId)
                return refusal
            }
        }
        val result = AxisOperations.keypadText(current, axis, text) { expression ->
            when (val value = ExpressionEvaluator.evaluate(expression)) {
                is ExpressionResult.Value -> Result.success(value.value)
                is ExpressionResult.Error -> Result.failure(ExpressionFailure(value.error))
            }
        }
        return result.fold(
            onSuccess = { operation ->
                apply(operation, UiText.of(Res.string.readout_undo_set, labelOf(current.profile, axis)))
                null
            },
            onFailure = { error -> (error as? ExpressionFailure)?.error?.toUiText() ?: UiText.Plain(error.message.orEmpty()) },
        )
    }

    /** The value of the divisions keypad: `round(360 / q)` = `N`, or null when `N` is not set. */
    fun divisionsValue(): Int? = frame.value?.let(Divisions::sheetValue)

    /** Sets `N` = [divisions] and turns on the divisions mode. Gives false for a value less than 1. */
    fun onDivisionsSet(divisions: Int): Boolean {
        val current = latestFrame() ?: return false
        if (divisions < 1) return false
        apply(Divisions.set(current, divisions), null)
        return true
    }

    // ---------------------------------------------------------------------------------------
    // The axis details

    /**
     * The feed rate of [axis] in µm/min from the input history. `now` is the time of the
     * latest sample of the session, so the rate stops at the last sample. Gives 0 with no session.
     */
    suspend fun feedRate(axis: AxisId): Double {
        val current = session.value ?: return 0.0
        val now = lastShown.channels.values.maxOfOrNull { channel -> channel.timeMs } ?: return 0.0
        return current.readHistory { history -> FeedValues.feedRate(history, axis, now) }
    }

    // ---------------------------------------------------------------------------------------
    // The side panel and the "+" button

    /** A tap on the side panel tab [mode]: opens the panel, or closes it when it shows already. */
    fun onSidePanelTab(mode: SidePanelMode) {
        val shown = uiState.value.sidePanel.mode
        val next = if (shown == mode) SidePanelMode.Off else mode
        scope.launch { machines.updateProfile(profileId) { current -> current.copy(sidePanel = next) } }
    }

    /** Closes the side panel. */
    fun onSidePanelClose() {
        scope.launch { machines.updateProfile(profileId) { current -> current.copy(sidePanel = SidePanelMode.Off) } }
    }

    /**
     * A tap on "+". A second tap in [DOUBLE_TAP_MS] is a double tap: the custom point sheet. Else
     * the single tap adds a sub-datum at the current position after [DOUBLE_TAP_MS].
     */
    fun onAddTap() {
        val pending = pendingTap
        if (pending != null && pending.isActive) {
            pending.cancel()
            pendingTap = null
            request(AddPointKind.Double)
            return
        }
        pendingTap = scope.launch {
            delay(DOUBLE_TAP_MS)
            pendingTap = null
            request(AddPointKind.Single)
            showHint(Hint.AddPointLongPress, ADD_POINT_TAG)
        }
    }

    /** A long press on "+": the shape menu. */
    fun onAddLongPress() {
        pendingTap?.cancel()
        pendingTap = null
        request(AddPointKind.Long)
    }

    /** Opens or closes the bottom sheet of the function strip (the compact layout). */
    fun onFunctionSheet(open: Boolean) {
        local.update { state -> state.copy(functionSheetOpen = open) }
    }

    /** The screen did the "+" action [id]. */
    fun onAddRequestHandled(id: Long) {
        local.update { state -> if (state.addPoint?.id == id) state.copy(addPoint = null) else state }
    }

    /** Updates the horizontal split fraction of the readout column in the expanded layout. */
    fun onReadoutPaneFractionChange(fraction: Float) {
        val clamped = fraction.coerceIn(READOUT_PANE_MIN_FRACTION, READOUT_PANE_MAX_FRACTION)
        appSettings.update { settings -> settings.copy(readoutPaneFraction = clamped) }
        scope.launch {
            machines.updateProfile(profileId) { current ->
                current.copy(display = current.display.copy(readoutPaneFraction = clamped))
            }
        }
    }

    /** Updates the vertical height weight of the graphical view pane in the portrait layout. */
    fun onGraphicalPaneWeightChange(weight: Float) {
        val clamped = weight.coerceIn(GRAPHICAL_PANE_MIN_WEIGHT, GRAPHICAL_PANE_MAX_WEIGHT)
        appSettings.update { settings -> settings.copy(graphicalPaneWeight = clamped) }
        scope.launch {
            machines.updateProfile(profileId) { current ->
                current.copy(display = current.display.copy(graphicalPaneWeight = clamped))
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Undo

    /** The user tapped "Undo" on the undo [id]: the old offsets of the action come back. */
    fun onUndo(id: Long) {
        val record = local.value.undoRecord?.takeIf { record -> record.id == id } ?: return
        undoTimer?.cancel()
        local.update { state -> if (state.undoRecord?.id == id) state.copy(undo = null, undoRecord = null) else state }
        scope.launch { machines.writeWorkspace(record.workspaceId) { current -> record.delta.applyTo(current) } }
    }

    /** The snackbar of the undo [id] closed with no "Undo". */
    fun onUndoDismissed(id: Long) {
        local.update { state -> if (state.undoRecord?.id == id) state.copy(undo = null, undoRecord = null) else state }
    }

    // ---------------------------------------------------------------------------------------
    // Internal

    private fun commit(
        current: ReadoutFrame,
        before: Workspace,
        after: Workspace,
        label: UiText?,
        writeAll: Boolean,
        transform: ((Workspace) -> Workspace)? = null,
    ) {
        val (forward, backward) = WorkspaceDelta.between(before, after)
        if (forward.isEmpty && !writeAll) return
        val workspaceId = current.workspace.id
        scope.launch {
            machines.writeWorkspace(workspaceId) { stored ->
                if (writeAll && transform != null) transform(stored) else forward.applyTo(stored)
            }
        }
        if (label != null && !backward.isEmpty && WorkspaceDelta.changesOffsets(before, after)) showUndo(workspaceId, backward, label)
    }

    private fun showUndo(workspaceId: String, delta: WorkspaceDelta, label: UiText) {
        val id = nextId()
        local.update { state -> state.copy(undo = UndoUiState(id, label), undoRecord = UndoRecord(id, workspaceId, delta)) }
        undoTimer?.cancel()
        undoTimer = scope.launch {
            delay(UNDO_MS)
            local.update { state -> if (state.undoRecord?.id == id) state.copy(undo = null, undoRecord = null) else state }
        }
    }

    private fun showHint(hint: Hint, anchorTag: String? = null) {
        val settings = appSettings.settings.value
        if (!settings.showHints || hint.id in settings.shownHints) return
        appSettings.markHintShown(hint.id)
        val text = hint.text()
        local.update { state -> state.copy(hint = text, anchorTag = anchorTag) }
        hintTimer?.cancel()
        hintTimer = scope.launch {
            delay(HINT_MS)
            local.update { state ->
                if (state.hint == text) {
                    state.copy(hint = null, anchorTag = if (state.message == null) null else state.anchorTag)
                } else {
                    state
                }
            }
        }
    }

    private fun request(kind: AddPointKind) {
        val id = nextId()
        local.update { state -> state.copy(addPoint = AddPointRequest(id, kind)) }
    }

    private fun requirePosition(anchorTag: String? = null): Boolean {
        val refusal = positionUnavailable() ?: return true
        sounds.error(deviceId)
        showMessage(refusal, anchorTag)
        return false
    }

    /** The tachometer in the modes "preset" and "direct entry" needs no live position (no input exists). */
    private fun needsPosition(current: ReadoutFrame, axis: AxisId): Boolean =
        !(axis == AxisId.Tachometer && current.profile.tachometer.mode != TachometerMode.Sensor)

    private fun nextId(): Long = ids.incrementAndFetch()

    /** Gives the key of [inputs] that the tab shows for [state]: the last online inputs while not online. */
    private fun shownInputs(state: SessionState?, inputs: InputSnapshot): LiveInputs {
        val shown = if (state?.isOnline == true) inputs.also { lastOnline = it } else lastOnline
        lastShown = shown
        return LiveInputs.of(state, shown)
    }

    /** The live path: the frame, the value of each axis, and the RPM text of [base] with [inputs]. */
    private fun onLive(base: ReadoutFrame?, inputs: LiveInputs) {
        if (base == null) {
            _frame.value = null
            axisValues.forEach { (axis, flow) -> flow.value = AxisValueUiState(axis) }
            _rpmText.value = null
            return
        }
        val current = buildFrame(base, inputs)
        _frame.value = current
        axisValues.forEach { (axis, flow) -> flow.value = axisValueOf(current, axis, inputs) }
        _rpmText.value = if (current.profile.tachometer.enabled) ReadoutPatterns.RPM.format(current.reading(AxisId.Tachometer).shown) else null
    }

    /** Gives the frame of the stored frame [base] with the sums of [inputs]. */
    private fun buildFrame(base: ReadoutFrame, inputs: LiveInputs): ReadoutFrame {
        val sums = if (base.profile.tachometer.mode != TachometerMode.Sensor) inputs.sums + (AxisId.Tachometer to 0.0) else inputs.sums
        return base.withInputs(sums, inputs.projected)
    }

    private fun axisValueOf(current: ReadoutFrame, axis: AxisId, inputs: LiveInputs): AxisValueUiState {
        val reading = current.reading(axis)
        val state = inputs.state
        val status = inputs.statuses[axis]
        val manualTachometer = axis == AxisId.Tachometer && current.profile.tachometer.mode != TachometerMode.Sensor
        val online = state?.isOnline == true
        val live = state is SessionState.Connected && reading.hasValue
        val valueState = when {
            manualTachometer -> ValueState.Normal
            status == AxisValueStatus.Error -> ValueState.Error
            status == AxisValueStatus.NoInput -> ValueState.NoInput
            reading.held != null -> ValueState.Held
            !online -> ValueState.Offline
            !live -> ValueState.Pending
            reading.modified -> ValueState.Modified
            else -> ValueState.Normal
        }
        val negative = reading.display.isFinite() && reading.display < 0.0 && reading.text.startsWith("-")
        val linear = axis == AxisId.X || axis == AxisId.Y || axis == AxisId.Z
        val errorInput = if (valueState == ValueState.Error) errorInputOf(current.profile, axis, inputs) else null
        return AxisValueUiState(
            axis = axis,
            digits = if (negative) reading.text.substring(1) else reading.text,
            negative = negative,
            unit = reading.unit,
            state = valueState,
            errorInput = errorInput,
            positionUm = if (linear) sliderPosition(current, axis, reading.shown) else 0.0,
            toolSizeUm = if (linear) PositionSlider.toolSizeUm(axis, current.profile.machineType, current.workspace.toolXUm) else 0.0,
            toolOffsetUm = if (linear) reading.toolOffset else 0.0,
            incremental = reading.incremental,
            metric = if (linear) current.metric(axis) else current.profile.metricDefault,
            divisions = axis == AxisId.Angular && reading.unit == org.superdro.core.readout.DisplayUnit.Divisions,
            surfaceSpeed = axis == AxisId.Tachometer && current.state(axis).surfaceSpeedMode,
        )
    }

    /**
     * Gives [shown] rounded to the last digit of the display of [axis], so that a change below the
     * resolution of the display does not give a new slider value.
     */
    private fun sliderPosition(current: ReadoutFrame, axis: AxisId, shown: Double): Double {
        if (!shown.isFinite()) return shown
        val step = (if (current.metric(axis)) UM_PER_MM else UM_PER_INCH) / 10.0.pow(current.pattern(axis).decimals)
        return floor(shown / step + 0.5) * step
    }

    private fun errorInputOf(profile: MachineProfile, axis: AxisId, inputs: LiveInputs): Char? {
        val letters = SetupReminder.inputs(profile, axis).map { input -> input.channel.lowercaseChar() }
        val bad = letters.firstOrNull { letter ->
            val status = inputs.channelStatuses[letter]
            status == ChannelStatus.NoProfile || status == ChannelStatus.InvalidResolution
        }
        return bad ?: letters.firstOrNull() ?: SetupReminder.primaryInput(profile, axis)
    }

    private fun uiStateOf(
        current: ReadoutFrame,
        state: SessionState?,
        adapterProfile: AdapterProfile?,
        tools: List<Tool>,
        showReminder: Boolean,
        placement: org.superdro.core.model.GraphicalViewPlacement,
        readoutPaneFraction: Float,
        graphicalPaneWeight: Float,
        ui: LocalState,
    ): ReadoutUiState {
        val profile = current.profile
        val workspace = current.workspace
        val toolsEnabled = tools.isNotEmpty()
        val panel = if (profile.sidePanel == SidePanelMode.Tools && !toolsEnabled) SidePanelMode.Off else profile.sidePanel
        val units = GlobalButtons.unitsState(current)
        val mode = GlobalButtons.modeState(current)
        val toolName = workspace.activeToolId?.let { id -> tools.firstOrNull { tool -> tool.id == id }?.name?.ifBlank { null } ?: id.take(8) }
        val subDatum = current.activeSubDatum
        val expandedRows = ReadoutRows.rows(profile, collapsed = false)
        val collapsedRows = ReadoutRows.rows(profile, collapsed = true)
        return ReadoutUiState(
            deviceId = deviceId,
            loaded = true,
            machineType = profile.machineType,
            readoutFormat = profile.display.readoutFormat,
            readoutFont = profile.display.readoutFont,
            status = StatusStripUiState(
                units = units,
                mode = mode,
                toolName = toolName,
                subDatumName = subDatum?.let { datum -> datum.name.ifBlank { datum.id.take(8) } },
                link = linkStatusOf(state),
                measurementMode = current.measurementActive,
                offline = offlineLabelOf(state),
            ),
            rows = expandedRows.mapIndexed { index, row -> row.copy(collapsedButtons = collapsedRows[index].buttons) },
            showSliders = profile.display.showSliders,
            globalButtons = GlobalButtonsUiState(mode = mode, units = units),
            functions = FunctionStripRules.buttons(profile, workspace),
            sidePanel = SidePanelUiState(
                mode = panel,
                toolsEnabled = toolsEnabled,
                addVisible = panel == SidePanelMode.Off || panel == SidePanelMode.SubDatums,
            ),
            graphicalViewPlacement = placement,
            readoutPaneFraction = readoutPaneFraction,
            graphicalPaneWeight = graphicalPaneWeight,
            setupReminder = if (showReminder) SetupReminder.axisNeedingSetup(profile, adapterProfile)?.let(::SetupReminderUiState) else null,
            hint = ui.hint,
            hintAnchorTag = ui.anchorTag,
            undo = ui.undo,
            message = ui.message,
            keepScreenOn = state is SessionState.Connected,
            addPointRequest = ui.addPoint,
            functionSheetOpen = ui.functionSheetOpen,
        )
    }

    private data class UndoRecord(val id: Long, val workspaceId: String, val delta: WorkspaceDelta)

    /**
     * The part of the session inputs that the readout shows: the session state, the sum, the
     * status, and the projection of each axis, and the status of each channel. It has no time,
     * so a sample with the same values gives an equal key.
     */
    private data class LiveInputs(
        val state: SessionState?,
        val sums: Map<AxisId, Double?>,
        val statuses: Map<AxisId, AxisValueStatus>,
        val projected: Set<AxisId>,
        val channelStatuses: Map<Char, ChannelStatus>,
    ) {
        companion object {
            fun of(state: SessionState?, snapshot: InputSnapshot): LiveInputs {
                val sums = LinkedHashMap<AxisId, Double?>()
                val statuses = LinkedHashMap<AxisId, AxisValueStatus>()
                val projected = LinkedHashSet<AxisId>()
                snapshot.axes.values.forEach { value ->
                    sums[value.axis] = value.value
                    statuses[value.axis] = value.status
                    if (value.projected) projected.add(value.axis)
                }
                val channels = LinkedHashMap<Char, ChannelStatus>()
                snapshot.channels.values.forEach { value -> channels[value.input] = value.status }
                return LiveInputs(state, sums, statuses, projected, channels)
            }
        }
    }

    private data class LocalState(
        val undo: UndoUiState? = null,
        val undoRecord: UndoRecord? = null,
        val message: MessageUiState? = null,
        val hint: UiText? = null,
        val anchorTag: String? = null,
        val addPoint: AddPointRequest? = null,
        val functionSheetOpen: Boolean = false,
    )

    /** The failure of the keypad evaluator, with the error of `core:readout`. */
    private class ExpressionFailure(val error: ExpressionError) : Exception(error.message)

    /** The one-time hints. [id] goes into `AppSettings.shownHints`. */
    enum class Hint(val id: String) {
        ZeroLongPress("hint_zero_long_press"),
        ClearLongPress("hint_mode_long_press"),
        HoldLongPress("hint_hold_long_press"),
        AddPointLongPress("hint_add_point_long_press");

        /** The text of the hint. */
        fun text(): UiText = when (this) {
            ZeroLongPress -> UiText.of(Res.string.readout_hint_zero)
            ClearLongPress -> UiText.of(Res.string.readout_hint_clear)
            HoldLongPress -> UiText.of(Res.string.readout_hint_hold)
            AddPointLongPress -> UiText.of(Res.string.readout_hint_add_point)
        }
    }

    companion object {
        /** The undo of an offset change shows for 10 s. */
        const val UNDO_MS: Long = 10_000L

        /** A second tap on "+" in this time is a double tap. */
        const val DOUBLE_TAP_MS: Long = 300L

        /** A hint shows for this time. */
        const val HINT_MS: Long = 6_000L

        /** A message shows for this time. */
        const val MESSAGE_MS: Long = 4_000L

        private const val UM_PER_MM: Double = 1_000.0
        private const val UM_PER_INCH: Double = 25_400.0

        /** Gives the label of [axis] of [profile], for example "X". */
        fun labelOf(profile: MachineProfile, axis: AxisId): String = when (axis) {
            AxisId.X -> profile.x.label
            AxisId.Y -> profile.y.label
            AxisId.Z -> profile.z.label
            AxisId.Angular -> profile.angular.label
            AxisId.Tachometer -> profile.tachometer.label
            AxisId.Caliper -> axis.name
        }

        /** Gives the workspace [workspace] with the state of each axis of [frame]. */
        internal fun complete(frame: ReadoutFrame, workspace: Workspace): Workspace = workspace.copy(
            axisStates = AxisId.entries.associateWith { axis -> workspace.axisStates[axis] ?: frame.state(axis) },
        )

        /** Gives the link status of [state]. */
        fun linkStatusOf(state: SessionState?): LinkStatus = when (state) {
            null, SessionState.Disconnected -> LinkStatus.Disconnected
            is SessionState.Connected -> LinkStatus.Connected
            is SessionState.Connecting -> LinkStatus.Connecting(state.attempt)
            SessionState.AwaitingData -> LinkStatus.AwaitingData
            SessionState.NoData -> LinkStatus.NoData
            is SessionState.Reconnecting -> LinkStatus.Reconnecting(state.attempt)
            is SessionState.WaitingForDevice -> LinkStatus.WaitingForDevice(state.cause)
            is SessionState.Failed -> LinkStatus.Failed(state.cause)
        }

        /** "Reconnecting" while the session connects again, "Offline" in each other state that is not online. */
        fun offlineLabelOf(state: SessionState?): OfflineLabel? = when {
            state?.isOnline == true -> null
            state is SessionState.Connecting || state is SessionState.Reconnecting -> OfflineLabel.Reconnecting
            else -> OfflineLabel.Offline
        }
    }
}
