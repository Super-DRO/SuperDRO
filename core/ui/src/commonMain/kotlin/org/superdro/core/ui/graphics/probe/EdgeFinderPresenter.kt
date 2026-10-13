package org.superdro.core.ui.graphics.probe

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.DeviceSession
import org.superdro.core.connection.SessionState
import org.superdro.core.functions.FunctionSound
import org.superdro.core.functions.probe.CoordinateMode
import org.superdro.core.functions.probe.Edge
import org.superdro.core.functions.probe.EdgeFinder
import org.superdro.core.functions.probe.EdgeFinderSettings
import org.superdro.core.functions.probe.EdgeFinderState
import org.superdro.core.functions.probe.EdgeFinderStep
import org.superdro.core.functions.probe.EdgeFinderTexts
import org.superdro.core.functions.probe.EdgeSide
import org.superdro.core.functions.probe.EdgeStatus
import org.superdro.core.functions.probe.FeatureType
import org.superdro.core.functions.probe.OriginOption
import org.superdro.core.functions.probe.ProbeTechnique
import org.superdro.core.functions.probe.SamplerTarget
import org.superdro.core.model.AxisId
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.ProbeProfile
import org.superdro.core.model.ProbeType
import org.superdro.core.model.SubDatum
import org.superdro.core.readout.ReadoutFrame
import org.superdro.core.readout.ReadoutPatterns
import org.superdro.core.readout.input.ProbeEvent
import org.superdro.core.readout.operations.AxisOperations
import org.superdro.core.readout.operations.SubDatumOperations
import org.superdro.core.resources.Res
import org.superdro.core.resources.edgefinder_not_connected
import org.superdro.core.resources.edgefinder_sub_datum_added
import org.superdro.core.resources.edgefinder_undo_origin
import org.superdro.core.store.StoreResult
import org.superdro.core.ui.graphics.GraphicsDependencies
import org.superdro.core.ui.graphics.common.LengthInput
import org.superdro.core.ui.graphics.common.LengthTexts
import org.superdro.core.ui.graphics.common.evaluateLength
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.readout.TabReadout
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.toUiText
import org.superdro.core.ui.adapter.probe.ProbeFunction
import org.superdro.core.ui.adapter.probe.ProbeSelectorDelegate
import org.superdro.core.ui.adapter.probe.ProbeSelectorUiState
import org.superdro.core.ui.adapter.probe.ProbeSounds

/** One edge button of the edge finder. [valueText] is the absolute value, or null. */
@Immutable
data class EdgeButtonUiState(val side: EdgeSide, val status: EdgeStatus = EdgeStatus.Unknown, val valueText: String? = null)

/**
 * The UI state of the edge finder screen "Indicate Workpiece".
 *
 * @property loaded False until the profile and the workspace load.
 * @property edges The four edges in the order Left, Right, Near, Far.
 * @property cameraCenterVisible True for a camera profile: the button "Use the point under the
 *   crosshairs" shows in place of AUTO.
 * @property cameraCenterSet True when a center origin exists: the next tap clears it.
 * @property originText The origin text `x: <v> <unit> y: <v> <unit>`. An absent value shows the
 *   zero format with each `0` as `_`.
 * @property unitSymbol The unit symbol of the values: `mm` or `in.` (a technical name).
 * @property message The message line, or null.
 */
@Immutable
data class EdgeFinderUiState(
    val loaded: Boolean = false,
    val edges: List<EdgeButtonUiState> = EdgeSide.entries.map { side -> EdgeButtonUiState(side) },
    val autoVisible: Boolean = false,
    val autoOn: Boolean = false,
    val cameraCenterVisible: Boolean = false,
    val cameraCenterSet: Boolean = false,
    val xFeature: FeatureType = FeatureType.External,
    val yFeature: FeatureType = FeatureType.External,
    val featureEnabled: Boolean = true,
    val xOption: OriginOption? = null,
    val yOption: OriginOption? = null,
    val xOptions: Set<OriginOption> = emptySet(),
    val yOptions: Set<OriginOption> = emptySet(),
    val originText: String = "",
    val zRowVisible: Boolean = false,
    val zArmed: Boolean = false,
    val zText: String = "",
    val zError: UiText? = null,
    val mode: CoordinateMode = CoordinateMode.Absolute,
    val setOriginEnabled: Boolean = false,
    val addSubDatumEnabled: Boolean = false,
    val connected: Boolean = false,
    val message: UiText? = null,
    val selector: ProbeSelectorUiState = ProbeSelectorUiState(ProbeFunction.EdgeFinder),
    val metric: Boolean = true,
    val unitSymbol: String = EdgeFinderTexts.UNIT_MM,
) {
    /** The selected probe profile of the edge finder, or null. */
    val selectedProbeId: String? get() = selector.selectedId

    /** Gives the state of the edge [side]. */
    fun edge(side: EdgeSide): EdgeButtonUiState = edges.first { edge -> edge.side == side }
}

/**
 * The presenter of the edge finder screen "Indicate Workpiece". The math is [EdgeFinder] of
 * `core:functions`. This presenter gives it the live positions, the probe closures of the session,
 * and the input history, and it writes the results with the readout of the tab.
 *
 * - The screen works on a vertical mill and on a horizontal mill.
 * - The probe profile of the function is `ProbeRoles.edgeFinderProfileId`. The probe
 *   selector of the adapter area ([ProbeSelectorDelegate]) writes it: a new profile from "New
 *   profile…" becomes the selection. The settings
 *   come from [EdgeFinderSettings.from], with the instrument offset `H` when the measurement mode
 *   uses an instrument.
 * - Each probe closure of the port of the profile goes to [EdgeFinder.onTrigger]. The latch is the
 *   position at the trigger time; the AUTO direction comes from the input history.
 * - A disconnection stops AUTO and the samplers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EdgeFinderPresenter(
    private val deviceId: DeviceId,
    private val readout: TabReadout,
    private val deps: GraphicsDependencies,
) : ScopedPresenter(deps.context) {

    private val profileId: String = deviceId.value
    private val math = MutableStateFlow(EdgeFinderState())
    private val local = MutableStateFlow(Local())
    private val probeSounds = ProbeSounds(deps.sounds, deviceId)
    private val selector = ProbeSelectorDelegate(deps.machines, deps.probes, deps.wallClock, deviceId, ProbeFunction.EdgeFinder, scope)

    private val session: StateFlow<DeviceSession?> = deps.connections.sessions
        .map { sessions -> sessions[deviceId] }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, deps.connections.sessions.value[deviceId])

    private val library: StateFlow<List<ProbeProfile>> = deps.probes.library(profileId)
        .map { list -> list.filter { probe -> ProbeTechnique.edgeFinderAccepts(probe.type) } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** The UI state of the screen. */
    val uiState: StateFlow<EdgeFinderUiState> = combine(
        readout.frame,
        readout.sessionState,
        library,
        math,
        combine(local, selector.state, ::Pair),
    ) { frame, state, probes, edgeState, (ui, probeSelector) ->
        uiStateOf(frame, state, probes, edgeState, ui, probeSelector)
    }.stateIn(scope, SharingStarted.Eagerly, EdgeFinderUiState())

    init {
        // The settings follow the profile, the selected probe, and the instrument offsets.
        scope.launch {
            combine(readout.frame, library) { frame, probes -> frame?.let { current -> settingsOf(current, selectedProbe(current.profile, probes)) } }
                .distinctUntilChanged()
                .collect { settings -> if (settings != null) math.update { state -> state.copy(settings = settings) } }
        }
        // The probe closures of the port of the profile.
        scope.launch {
            session.flatMapLatest { current -> current?.probeEvents ?: emptyFlow() }.collect { event -> onProbeEvent(event) }
        }
        // A disconnection stops AUTO and the samplers.
        scope.launch {
            readout.sessionState.collect { state ->
                if (state !is SessionState.Connected) {
                    math.update { current -> if (current.autoOn || current.sampler != null) EdgeFinder.cancel(current).state else current }
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Events

    /** A tap on the edge [side]. */
    fun onEdgeTap(side: EdgeSide) {
        val current = readout.frame.value ?: return
        // The probe sampler needs a connection; the message line tells it.
        if (!isConnected() && math.value.edge(side).status == EdgeStatus.Unknown) return
        apply(EdgeFinder.tapEdge(math.value, side, currentAbsolute(current, side.axis), probeClosed()))
    }

    /** A long press on the edge [side]: capture on `UNKNOWN`, clear on `LOCATED`. */
    fun onEdgeLongPress(side: EdgeSide) {
        val current = readout.frame.value ?: return
        apply(EdgeFinder.longPressEdge(math.value, side, currentAbsolute(current, side.axis)))
    }

    /** Starts or stops AUTO. */
    fun onAutoToggle() {
        if (!math.value.autoOn && !isConnected()) return
        apply(EdgeFinder.toggleAuto(math.value, probeClosed()))
    }

    /** Arms or disarms the Z capture. It needs a connection to arm. */
    fun onZArm() {
        val armed = math.value.sampler?.target == SamplerTarget.ZTarget
        if (!armed && !isConnected()) return
        apply(EdgeFinder.toggleZCapture(math.value, probeClosed()))
    }

    /** The text of the Z field changed. */
    fun onZText(text: String) {
        val metric = readout.frame.value?.metric(AxisId.Z) ?: true
        when (val input = evaluateLength(text, metric)) {
            LengthInput.Empty -> {
                math.update { state -> state.copy(zValueUm = null) }
                local.update { ui -> ui.copy(zText = text, zError = null) }
            }
            is LengthInput.Value -> {
                math.update { state -> state.copy(zValueUm = input.um) }
                local.update { ui -> ui.copy(zText = text, zError = null) }
            }
            is LengthInput.Invalid -> {
                math.update { state -> state.copy(zValueUm = null) }
                local.update { ui -> ui.copy(zText = text, zError = input.error) }
            }
        }
    }

    /** The sync button of the Z field: the current absolute Z. */
    fun onZSync() {
        val current = readout.frame.value ?: return
        val z = currentAbsolute(current, AxisId.Z) ?: return
        math.update { state -> state.copy(zValueUm = z) }
        local.update { ui -> ui.copy(zText = format(z, current), zError = null) }
    }

    /** Changes the feature type of [axis] (not while AUTO runs). */
    fun onFeature(axis: AxisId, feature: FeatureType) {
        math.update { state -> EdgeFinder.setFeature(state, axis, feature) }
    }

    /** Selects the origin option of [axis]. */
    fun onOption(axis: AxisId, option: OriginOption) {
        math.update { state -> EdgeFinder.selectOption(state, axis, option) }
    }

    /** Selects the coordinate system of "Set Origin". */
    fun onMode(mode: CoordinateMode) {
        math.update { state -> state.copy(mode = mode) }
    }

    /** Selects the probe profile [id] of the edge finder. Null clears the selection. */
    fun onSelectProbe(id: String?) = selector.select(id)

    /**
     * "New profile…" of the probe selector: the caller opens the new probe profile screen. The
     * next new accepted profile becomes the selection of the edge finder.
     */
    fun onNewProbe() = selector.requestNewProfile()

    /**
     * The camera center button of a camera profile.
     * With no center value, the tap sets the center origin of X and Y to the current position plus
     * the profile offset (minus `H`). A second tap clears both. The tap needs a connection.
     */
    fun onCameraCenter() {
        val current = readout.frame.value ?: return
        val state = math.value
        if (cameraCenterSet(state)) {
            math.value = state.copy(
                edges = EdgeSide.entries.associateWith { Edge() },
                xOption = null,
                yOption = null,
                message = null,
            )
            return
        }
        if (!isConnected()) return
        val x = currentAbsolute(current, AxisId.X)?.plus(state.settings.sampleOffsetUm(AxisId.X))
        val y = currentAbsolute(current, AxisId.Y)?.plus(state.settings.sampleOffsetUm(AxisId.Y))
        var edges = state.edges
        if (x != null) edges = edges + (EdgeSide.Left to Edge(EdgeStatus.Located, x)) + (EdgeSide.Right to Edge(EdgeStatus.Located, x))
        if (y != null) edges = edges + (EdgeSide.Near to Edge(EdgeStatus.Located, y)) + (EdgeSide.Far to Edge(EdgeStatus.Located, y))
        math.value = state.copy(
            edges = edges,
            xOption = if (x != null) OriginOption.Center else state.xOption,
            yOption = if (y != null) OriginOption.Center else state.yOption,
            auto = null,
            sampler = null,
            message = null,
        )
    }

    /**
     * "Set Origin": absolute `h(P − O)` and `rel = false`, or incremental `i(P − O)`, for
     * each axis with an origin, in one write with undo. Then the sampler and AUTO stop. Gives true
     * when the screen closes.
     */
    fun onSetOrigin(): Boolean {
        val current = readout.frame.value ?: return false
        if (!isConnected() || !math.value.canApply) return false
        val positions = AxisId.entries.mapNotNull { axis -> currentAbsolute(current, axis)?.let { value -> axis to value } }.toMap()
        val result = EdgeFinder.setOrigin(math.value, positions)
        math.value = result.state
        if (result.commands.isNotEmpty()) {
            readout.applyWorkspace(UiText.of(Res.string.edgefinder_undo_origin)) { base ->
                var frame = current.with(base)
                var workspace = base
                result.commands.forEach { command ->
                    val operation = when (command.mode) {
                        CoordinateMode.Absolute -> AxisOperations.setAxis(frame, command.axis, command.valueUm)
                        CoordinateMode.Incremental -> AxisOperations.setIncremental(frame, command.axis, command.valueUm)
                    }
                    workspace = operation.workspace
                    frame = frame.with(workspace)
                }
                workspace
            }
        }
        return result.close
    }

    /**
     * "Add Sub-Datum": a tap clears the edges, a long press ([keepEdges]) keeps them. The
     * screen stays open. The new sub-datum becomes active when `activate_added_subdatum` is on.
     */
    fun onAddSubDatum(keepEdges: Boolean) {
        val current = readout.frame.value ?: return
        if (!math.value.canApply) return
        val profile = current.profile
        val positions = AxisId.entries.mapNotNull { axis -> currentAbsolute(current, axis)?.let { value -> axis to value } }.toMap()
        val result = EdgeFinder.addSubDatum(
            math.value,
            positions,
            zEnabled = profile.z.enabled,
            use3dSubDatums = profile.use3dSubDatums,
            keepEdges = keepEdges,
        )
        math.value = result.state
        val workspaceId = current.workspace.id
        scope.launch {
            val stored = deps.machines.addSubDatum(workspaceId, SubDatum(x = result.point.x, y = result.point.y, z = result.point.z))
            if (stored is StoreResult.Ok) {
                local.update { ui -> ui.copy(notice = UiText.of(Res.string.edgefinder_sub_datum_added, stored.value.name)) }
                if (profile.display.activateNewSubDatum) {
                    val frame = readout.frame.value ?: return@launch
                    val withPoint = frame.with(frame.workspace.copy(subDatums = frame.workspace.subDatums + stored.value))
                    readout.apply(SubDatumOperations.activate(withPoint, stored.value.id), null)
                }
            }
        }
    }

    /** "Cancel": stops all probe logic. Gives true: the screen closes. */
    fun onCancel(): Boolean {
        val step = EdgeFinder.cancel(math.value)
        math.value = step.state
        return step.close
    }

    // ---------------------------------------------------------------------------------------
    // Private parts

    private suspend fun onProbeEvent(event: ProbeEvent) {
        val frame = readout.frame.value ?: return
        val port = probePort(frame.profile)
        if (event.port != port) return
        val state = math.value
        if (state.auto == null && state.sampler == null) return
        val current = session.value ?: return
        val trigger = ProbeTriggers.trigger(current, frame, event)
        val before = math.value
        val step = EdgeFinder.onTrigger(before, trigger)
        apply(step)
        val z = step.state.zValueUm
        if (z != null && z != before.zValueUm) local.update { ui -> ui.copy(zText = format(z, frame), zError = null) }
    }

    private fun apply(step: EdgeFinderStep) {
        math.value = step.state
        local.update { ui -> ui.copy(notice = null) }
        step.sounds.forEach { sound -> play(sound) }
    }

    private fun play(sound: FunctionSound) = probeSounds.play(sound)

    private fun isConnected(): Boolean = readout.positionUnavailable() == null

    private fun probeClosed(): Boolean {
        val frame = readout.frame.value ?: return false
        return session.value?.inputs?.value?.probes?.get(probePort(frame.profile)) == true
    }

    private fun probePort(profile: MachineProfile): Char =
        selectedProbe(profile, library.value)?.port ?: profile.probe.channel

    private fun currentAbsolute(frame: ReadoutFrame, axis: AxisId): Double? {
        if (!isConnected()) return null
        if (!frame.enabled(axis)) return null
        val reading = frame.reading(axis)
        return if (reading.hasValue && reading.absolute.isFinite()) reading.absolute else null
    }

    private fun selectedProbe(profile: MachineProfile, probes: List<ProbeProfile>): ProbeProfile? =
        profile.probeRoles.edgeFinderProfileId?.let { id -> probes.firstOrNull { probe -> probe.id == id } }

    private fun settingsOf(frame: ReadoutFrame, probe: ProbeProfile?): EdgeFinderSettings {
        val profile = frame.profile
        val instrument = frame.instrumentInUse != null
        return EdgeFinderSettings.from(
            probe = profile.probe,
            profile = probe,
            zEnabled = profile.z.enabled,
            metricDisplay = frame.metric(AxisId.X),
            readoutFormat = profile.display.readoutFormat,
            instrumentOffsetXUm = if (instrument) frame.instrumentOffset(AxisId.X) else null,
            instrumentOffsetYUm = if (instrument) frame.instrumentOffset(AxisId.Y) else null,
        )
    }

    private fun format(um: Double, frame: ReadoutFrame): String =
        LengthTexts.format(um, frame.metric(AxisId.X), frame.profile.display.readoutFormat)

    private fun cameraCenterSet(state: EdgeFinderState): Boolean =
        (state.xOption == OriginOption.Center && state.origin(AxisId.X) != null) ||
            (state.yOption == OriginOption.Center && state.origin(AxisId.Y) != null)

    private fun uiStateOf(
        frame: ReadoutFrame?,
        session: SessionState?,
        probes: List<ProbeProfile>,
        state: EdgeFinderState,
        ui: Local,
        probeSelector: ProbeSelectorUiState,
    ): EdgeFinderUiState {
        if (frame == null) return EdgeFinderUiState()
        val profile = frame.profile
        val connected = session is SessionState.Connected
        val selected = selectedProbe(profile, probes)
        val metric = frame.metric(AxisId.X)
        val camera = selected?.type == ProbeType.Camera
        val message = state.message?.toUiText() ?: ui.notice ?: if (!connected) UiText.of(Res.string.edgefinder_not_connected) else null
        return EdgeFinderUiState(
            loaded = true,
            edges = EdgeSide.entries.map { side ->
                val edge = state.edge(side)
                EdgeButtonUiState(side, edge.status, edge.valueUm?.let { value -> format(value, frame) })
            },
            autoVisible = state.settings.autoVisible && !camera,
            autoOn = state.autoOn,
            cameraCenterVisible = camera,
            cameraCenterSet = cameraCenterSet(state),
            xFeature = state.xFeature,
            yFeature = state.yFeature,
            featureEnabled = !state.autoOn,
            xOption = state.xOption,
            yOption = state.yOption,
            xOptions = state.enabledOptions(AxisId.X),
            yOptions = state.enabledOptions(AxisId.Y),
            originText = originText(state, frame),
            zRowVisible = state.settings.zCapture,
            zArmed = state.sampler?.target == SamplerTarget.ZTarget,
            zText = ui.zText,
            zError = ui.zError,
            mode = state.mode,
            setOriginEnabled = connected && state.canApply,
            addSubDatumEnabled = state.canApply,
            connected = connected,
            message = message,
            selector = probeSelector,
            metric = metric,
            unitSymbol = if (metric) EdgeFinderTexts.UNIT_MM else EdgeFinderTexts.UNIT_INCH,
        )
    }

    private fun originText(state: EdgeFinderState, frame: ReadoutFrame): String {
        val metric = frame.metric(AxisId.X)
        val unit = if (metric) EdgeFinderTexts.UNIT_MM else EdgeFinderTexts.UNIT_INCH
        val absent = ReadoutPatterns.linear(frame.profile.display.readoutFormat, metric).format(0.0).replace('0', '_')
        val x = state.origin(AxisId.X)?.let { value -> format(value, frame) } ?: absent
        val y = state.origin(AxisId.Y)?.let { value -> format(value, frame) } ?: absent
        return "x: $x $unit y: $y $unit"
    }

    /** The state of the screen that is not in [EdgeFinderState]. */
    private data class Local(
        val zText: String = "",
        val zError: UiText? = null,
        val notice: UiText? = null,
    )
}
