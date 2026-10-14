package org.superdro.core.ui.graphics.measure

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.DeviceSession
import org.superdro.core.functions.graphics.PlaneAxes
import org.superdro.core.functions.measurement.AddAction
import org.superdro.core.functions.measurement.CaptureStatus
import org.superdro.core.functions.measurement.FittedCircle
import org.superdro.core.functions.measurement.FittedLine
import org.superdro.core.functions.measurement.FrameAngles
import org.superdro.core.functions.measurement.MeasurementAddActions
import org.superdro.core.functions.measurement.MeasurementFormat
import org.superdro.core.functions.measurement.MeasurementLog
import org.superdro.core.functions.measurement.MeasurementSession
import org.superdro.core.functions.measurement.MeasurementTolerance
import org.superdro.core.functions.measurement.MeasurementVerb
import org.superdro.core.functions.measurement.PointCapture
import org.superdro.core.functions.measurement.ProbeCapture
import org.superdro.core.functions.measurement.ResultKey
import org.superdro.core.functions.measurement.ResultValue
import org.superdro.core.functions.measurement.SlotState
import org.superdro.core.model.AdapterProfile
import org.superdro.core.model.AxisId
import org.superdro.core.model.CollectedPoint
import org.superdro.core.model.Drawing
import org.superdro.core.model.LogDisplayFormat
import org.superdro.core.model.MachineMode
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.Plane
import org.superdro.core.model.ProbeProfile
import org.superdro.core.model.ProbeType
import org.superdro.core.readout.ReadoutFrame
import org.superdro.core.readout.input.ProbeEvent
import org.superdro.core.resources.Res
import org.superdro.core.resources.measure_add_blocked
import org.superdro.core.resources.measure_added_circle
import org.superdro.core.resources.measure_added_sub_datum
import org.superdro.core.resources.measure_log_empty
import org.superdro.core.resources.measure_mode_lathe
import org.superdro.core.store.valueOrNull
import org.superdro.core.ui.graphics.GraphicsDependencies
import org.superdro.core.ui.navigation.MeasurementLogRoute
import org.superdro.core.ui.navigation.TabEntryScope
import org.superdro.core.ui.navigation.TabRoute
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.readout.TabReadout
import org.superdro.core.ui.readout.structure
import org.superdro.core.ui.sound.UiSound
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.adapter.probe.ProbeSounds

/** The name of the [MeasurementController] in the presenters of a tab. */
const val MEASUREMENT_CONTROLLER: String = "graphics-measure"

/** Gives the measurement controller of the tab of this scope. The tab owns it. */
fun TabEntryScope.measurementController(deps: GraphicsDependencies): MeasurementController =
    tabPresenter(MEASUREMENT_CONTROLLER) { MeasurementController(deviceId, readout, deps) }

/** One slot button of the measurement panel. */
@Immutable
data class SlotUiState(val index: Int, val state: SlotState, val group: Int)

/**
 * One row of the results table.
 *
 * @property valueText The value text in the current display unit, or null for "—".
 * @property addEnabled True when the add button is enabled.
 */
@Immutable
data class ResultRowUiState(
    val key: ResultKey,
    val valueText: String?,
    val addAction: AddAction?,
    val addEnabled: Boolean,
)

/**
 * One collected point of the measurement points layer of the graphical view.
 *
 * @property xUm The value of the horizontal axis of the plane (µm), with no vertical sign.
 * @property yUm The value of the vertical axis of the plane (µm), with no vertical sign. The view
 *   gives the position with `PlaneAxes.toView(plane, xUm, yUm)`.
 * @property suspect True when the point has a note of the severity "suspect".
 */
@Immutable
data class CollectedPointView(val slotIndex: Int, val xUm: Double, val yUm: Double, val suspect: Boolean)

/**
 * The state of the measurement mode and of the measurement panel of one tab.
 *
 * @property available True when the machine profile is loaded and is not a lathe.
 * @property modeOn True when the measurement mode applies.
 * @property instrumentName The name of the reference instrument in use, or null.
 * @property verb The open function, or null when the panel shows only the function strip.
 * @property message The message line, or null when it is hidden.
 * @property logVisible True when the note row and the log button show.
 * @property canLog True when the log button is enabled.
 * @property loggedSequence The log sequence of the set ("#N"), or null.
 * @property logCount The number of log entries of the workspace.
 * @property plane The plane of the points.
 * @property helpOpen True while the help text of [verb] shows in the pane.
 */
@Immutable
data class MeasurementUiState(
    val available: Boolean = false,
    val modeOn: Boolean = false,
    val instrumentName: String? = null,
    val verb: MeasurementVerb? = null,
    val slots: List<SlotUiState> = emptyList(),
    val canReset: Boolean = false,
    val message: UiText? = null,
    val note: String = "",
    val logVisible: Boolean = false,
    val canLog: Boolean = false,
    val loggedSequence: Int? = null,
    val results: List<ResultRowUiState> = emptyList(),
    val logCount: Long = 0L,
    val plane: Plane = Plane.XY,
    val points: List<CollectedPointView> = emptyList(),
    val circle: FittedCircle? = null,
    val lines: List<FittedLine> = emptyList(),
    val helpOpen: Boolean = false,
)

/**
 * The measurement mode of one tab. The tab owns it ([measurementController]), so the open function
 * and its points stay while
 * the user changes between the pane of the readout screen and the full-screen graphical view.
 * The points do not stay after a restart of the process.
 *
 * Rules:
 * - The mode switch writes `MachineProfile.machineMode`. A lathe has no measurement mode. A change
 *   to the measurement mode selects the instrument when no instrument is selected and exactly one
 *   valid instrument exists. The readout frame then suspends the tool
 *   offsets, adds the instrument offsets, and stops the proximity warning.
 * - A change of the mode, of the workspace, of the axis alignment, or of the part rotation closes
 *   the open function.
 * - A capture on the armed slot needs a live position. It uses the absolute position with
 *   the instrument offset and the axis alignment.
 * - The probe watcher runs while a function is open and the instrument has the port `p` or `q`.
 *  A latched axis that is "not complete" counts as unknown.
 *
 * @param deviceId The device of the tab: the ID of its machine profile.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeasurementController(
    val deviceId: DeviceId,
    private val readout: TabReadout,
    private val deps: GraphicsDependencies,
) : ScopedPresenter(deps.context) {

    private val profileId: String = deviceId.value
    private val session = MutableStateFlow<MeasurementSession?>(null)
    private val helpOpen = MutableStateFlow(false)
    private val noticeFlow = MutableSharedFlow<UiText>(extraBufferCapacity = 16)
    private var lastProbeSequence = 0L
    private var lastContext: Any? = null

    /** The texts that the host shows in its snackbar or message line. */
    val notices: SharedFlow<UiText> = noticeFlow.asSharedFlow()

    private val profile: StateFlow<MachineProfile?> = deps.machines.profile(profileId).stateIn(scope, SharingStarted.Eagerly, null)
    private val probeSounds = ProbeSounds(deps.sounds, deviceId)

    private val library: StateFlow<List<ProbeProfile>> =
        deps.probes.library(profileId).stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val adapter: StateFlow<AdapterProfile?> = profile
        .map { current -> current?.adapterProfileId }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(null) else deps.adapters.adapterProfile(id) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    private val logCount: StateFlow<Long> = readout.frame
        .map { frame -> frame?.workspace?.id }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(0L) else deps.measurementLog.count(id) }
        .stateIn(scope, SharingStarted.Eagerly, 0L)

    /**
     * The state of the mode and of the panel. It uses no live position: only a change of the
     * structure of the readout or of the set makes it again.
     */
    val uiState: StateFlow<MeasurementUiState> = combine(readout.frame.structure(), session, logCount, helpOpen) { frame, open, count, help ->
        stateOf(frame, open, count, help)
    }.stateIn(scope, SharingStarted.Eagerly, MeasurementUiState())

    init {
        // A change of the mode, the workspace, the alignment, or the part rotation closes the set.
        scope.launch {
            readout.frame.structure().collect { frame ->
                val context = frame?.let { current ->
                    listOf(current.measurementActive, current.workspace.id, current.workspace.axisAlignment, current.workspace.partRotation, current.workspace.plane)
                }
                if (lastContext != null && context != lastContext) session.value = null
                lastContext = context
            }
        }
        scope.launch {
            deps.connections.sessions
                .map { sessions -> sessions[deviceId] }
                .distinctUntilChanged()
                .flatMapLatest { current -> current?.probeEvents?.map { event -> current to event } ?: emptyFlow() }
                .collect { (current, event) -> onProbeEvent(current, event) }
        }
    }

    // ---------------------------------------------------------------------------------------
    // The mode

    /**
     * Switches the measurement mode on or off. Gives false on a lathe: the measurement
     * mode is not available there, and the host shows the notice.
     */
    fun setMeasurementMode(on: Boolean): Boolean {
        val current = profile.value ?: return false
        if (current.machineType.isLathe) {
            noticeFlow.tryEmit(UiText.of(Res.string.measure_mode_lathe))
            return false
        }
        val mode = if (on) MachineMode.Measurement else MachineMode.Cutting
        session.value = null
        val valid = library.value.filter(::isValidInstrument)
        val selected = current.probeRoles.measurementInstrumentProfileId
        val autoSelect = if (on && valid.none { probe -> probe.id == selected } && valid.size == 1) valid.single().id else null
        scope.launch {
            deps.machines.updateProfile(profileId) { stored ->
                val roles = stored.probeRoles
                stored.copy(
                    machineMode = mode,
                    probeRoles = if (autoSelect != null) roles.copy(measurementInstrumentProfileId = autoSelect) else roles,
                )
            }
            autoSelect?.let { id -> deps.probes.markUsed(id) }
        }
        return true
    }

    /** Switches the measurement mode to the other value. */
    fun toggleMeasurementMode(): Boolean = setMeasurementMode(!uiState.value.modeOn)

    // ---------------------------------------------------------------------------------------
    // The function strip and the slots

    /** A tap on a function: an inactive function opens with empty slots; the active function closes. */
    fun onVerbTap(verb: MeasurementVerb) {
        val frame = readout.frame.value ?: return
        if (!frame.measurementActive) return
        val open = session.value
        session.value = if (open?.verb == verb) null else MeasurementSession.open(verb, toleranceUm(frame))
        helpOpen.value = false
    }

    /** A long press on the active function: it clears the points and the note, and stays open. */
    fun onVerbLongPress(verb: MeasurementVerb) {
        session.update { open -> if (open?.verb == verb) open.reset() else open }
    }

    /** A tap on the slot [index]: only the armed slot captures the current position. */
    fun onSlotTap(index: Int) {
        val open = session.value ?: return
        if (open.armedSlot != index) return
        val frame = readout.latestFrame() ?: return
        val refusal = readout.positionUnavailable()
        if (refusal != null) {
            deps.sounds.play(UiSound.Error, deviceId)
            noticeFlow.tryEmit(refusal)
            return
        }
        val point = latchPoint(frame) ?: return
        capture(point, frame)
    }

    /** A long press on the slot [index]: it clears a filled slot. */
    fun onSlotLongPress(index: Int) {
        session.update { open -> open?.clearSlot(index) }
    }

    /** "Start this measurement over": clears all points and the note. */
    fun onReset() {
        session.update { open -> open?.reset() }
    }

    /** The note of the user changes. */
    fun onNoteChange(text: String) {
        session.update { open -> open?.withNote(text) }
    }

    /** The help button of the open function. */
    fun onHelp(open: Boolean) {
        helpOpen.value = open
    }

    /**
     * A tap on a sub-datum in the graphical view. When a function is
     * open, the sub-datum position goes into the armed slot and the function gives true. Else the
     * function gives false, and the view selects the sub-datum as before.
     */
    fun onSubDatumTap(subDatumId: String): Boolean {
        val open = session.value ?: return false
        val frame = readout.frame.value ?: return false
        if (!frame.measurementActive) return false
        val subDatum = frame.workspace.subDatums.firstOrNull { point -> point.id == subDatumId } ?: return false
        val plane = frame.workspace.plane
        val position = if (plane == Plane.XY) {
            val shown = frame.shownSubDatumPoint(subDatum)
            val x = shown.x
            val y = shown.y
            if (x == null || y == null) null else x to y
        } else {
            PointCapture.planeCoordinates(subDatum, plane)?.let { point -> point.h to point.v }
        }
        if (position == null) {
            deps.sounds.play(UiSound.Error, deviceId)
            return true
        }
        if (open.armedSlot == null) return true
        capture(PointCapture.subDatum(subDatum.id, position.first, position.second), frame)
        return true
    }

    // ---------------------------------------------------------------------------------------
    // The log and the add actions

    /**
     * Logs the complete set. [title] is the title of the function in
     * the app language. The set becomes "logged" with its sequence number when the points did not
     * change during the write.
     */
    fun onLog(title: String) {
        val open = session.value ?: return
        val frame = readout.frame.value ?: return
        if (!open.canLog) return
        val workspace = frame.workspace
        scope.launch {
            val entry = MeasurementLog.buildEntry(
                session = open,
                title = title,
                workspaceId = workspace.id,
                workspaceName = workspace.name,
                sequence = 0,
                display = displayFormat(frame),
                axisAlignmentArcSec = frame.alignment?.angleArcSec ?: 0.0,
                createdOn = deps.wallClock.now(),
            ) ?: return@launch
            val stored = deps.measurementLog.append(workspace.id, entry).valueOrNull() ?: return@launch
            session.update { current ->
                if (current != null && current.slots == open.slots && current.note == open.note) current.markLogged(stored.sequence) else current
            }
        }
    }

    /**
     * The add button of the result [key]. [name] is the name of the new sub-datum in the
     * app language. The circle of "Circle from 3 points" goes on the drawing layer "Measured".
     */
    fun onAdd(key: ResultKey, name: String) {
        val open = session.value ?: return
        val frame = readout.frame.value ?: return
        val evaluation = open.evaluate()
        val row = evaluation.results.firstOrNull { result -> result.key == key } ?: return
        if (!row.addEnabled || row.value == null) return
        val workspace = frame.workspace
        if (workspace.armedThreadId != null) {
            deps.sounds.play(UiSound.Error, deviceId)
            noticeFlow.tryEmit(UiText.of(Res.string.measure_add_blocked))
            return
        }
        when (val action = row.addAction) {
            is AddAction.AddSubDatum -> {
                val position = row.value as? ResultValue.Position ?: return
                val subDatum = MeasurementAddActions.subDatum(name, position.xUm, position.yUm, workspace.plane)
                scope.launch {
                    val stored = deps.machines.addSubDatum(workspace.id, subDatum).valueOrNull() ?: return@launch
                    deps.sounds.play(UiSound.Short, deviceId)
                    noticeFlow.tryEmit(UiText.of(Res.string.measure_added_sub_datum, stored.name))
                }
            }
            AddAction.AddCircleToDrawing -> {
                val circle = evaluation.circle ?: return
                scope.launch {
                    val drawing = deps.drawings.loadDrawing(workspace.id) ?: Drawing(workspaceId = workspace.id)
                    deps.drawings.replaceDrawing(MeasurementAddActions.addCircle(drawing, circle)).valueOrNull() ?: return@launch
                    deps.sounds.play(UiSound.Short, deviceId)
                    noticeFlow.tryEmit(UiText.of(Res.string.measure_added_circle, MeasurementAddActions.MEASURED_LAYER_NAME))
                }
            }
            null -> Unit
        }
    }

    /**
     * The log button of the graphical view: gives the log screen when
     * the log of the workspace has entries. Else the notice tells that the log is empty.
     */
    fun onLogButton(): TabRoute? {
        if (logCount.value > 0L) return MeasurementLogRoute
        noticeFlow.tryEmit(UiText.of(Res.string.measure_log_empty))
        return null
    }

    // ---------------------------------------------------------------------------------------
    // The probe watcher

    private suspend fun onProbeEvent(current: DeviceSession, event: ProbeEvent) {
        if (event.sequence <= lastProbeSequence) return
        lastProbeSequence = event.sequence
        val open = session.value ?: return
        val frame = readout.frame.value ?: return
        if (!frame.measurementActive || open.armedSlot == null) return
        val instrument = frame.instrumentInUse ?: return
        val port = instrument.port?.lowercaseChar() ?: return
        if (port != 'p' && port != 'q' || event.port.lowercaseChar() != port) return
        val plane = frame.workspace.plane
        val (h, v, n) = planeAxesOf(plane)
        val rValues = event.axisPositions.mapValues { (axis, value) ->
            if (axis in event.incompleteAxes || !value.isFinite()) Double.NaN else value
        }
        val absolute = frame.absoluteFor(rValues)
        val approaches = current.readHistory { history ->
            ProbeApproach.signs(history, frame, listOfNotNull(h, v, n.takeIf { axis -> frame.enabled(axis) }), event.timeMs)
        }
        val normal = absolute[n]?.takeIf { frame.enabled(n) }
        val capture = PointCapture.probe(
            xUm = absolute[h] ?: Double.NaN,
            yUm = absolute[v] ?: Double.NaN,
            zUm = normal,
            approachX = approaches[h] ?: 0,
            approachY = approaches[v] ?: 0,
            approachZ = if (normal == null) null else approaches[n] ?: 0,
            tipRadiusUm = PointCapture.tipRadiusUm(instrument),
            instrumentId = instrument.id,
            instrumentXOffsetUm = frame.instrumentOffset(AxisId.X),
            instrumentYOffsetUm = frame.instrumentOffset(AxisId.Y),
        )
        when (capture) {
            is ProbeCapture.Refused -> {
                session.update { latest -> latest?.withProbeError(capture.reason) }
                probeSounds.refused()
            }
            is ProbeCapture.Collected -> capture(capture.point, frame)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Private parts

    private fun capture(point: CollectedPoint, frame: ReadoutFrame) {
        val open = session.value ?: return
        val result = open.capture(point, frameAngles(frame))
        session.value = result.session
        // The sounds of the probe functions: a point, the last point, a refused point.
        when (result.status) {
            CaptureStatus.Added -> probeSounds.sample(last = false)
            CaptureStatus.Completed -> probeSounds.sample(last = true)
            CaptureStatus.NotAdded -> probeSounds.refused()
        }
    }

    private fun latchPoint(frame: ReadoutFrame): CollectedPoint? {
        val (h, v, n) = planeAxesOf(frame.workspace.plane)
        val x = frame.reading(h).takeIf { reading -> reading.hasValue }?.absolute ?: Double.NaN
        val y = frame.reading(v).takeIf { reading -> reading.hasValue }?.absolute ?: Double.NaN
        val z = frame.reading(n).takeIf { reading -> frame.enabled(n) && reading.hasValue }?.absolute
        val instrument = frame.instrumentInUse
        val offsetX = frame.instrumentOffset(AxisId.X)
        val offsetY = frame.instrumentOffset(AxisId.Y)
        return if (instrument?.type == ProbeType.Camera) {
            PointCapture.camera(x, y, z, instrument.id, offsetX, offsetY)
        } else {
            PointCapture.latch(x, y, z, instrument?.id, offsetX, offsetY)
        }
    }

    private fun stateOf(frame: ReadoutFrame?, open: MeasurementSession?, count: Long, help: Boolean): MeasurementUiState {
        if (frame == null) return MeasurementUiState()
        val available = !frame.profile.machineType.isLathe
        val modeOn = frame.measurementActive
        val instrument = frame.instrumentInUse
        val base = MeasurementUiState(
            available = available,
            modeOn = modeOn,
            instrumentName = instrument?.name,
            logCount = count,
            plane = frame.workspace.plane,
        )
        if (!modeOn || open == null) return base
        val evaluation = open.evaluate()
        val format = displayFormat(frame)
        val blocked = frame.workspace.armedThreadId != null
        val slots = (0 until open.verb.slotCount).map { index -> SlotUiState(index, open.slotState(index), open.verb.slotGroups[index]) }
        val points = open.slots.mapIndexedNotNull { index, point ->
            point?.let { CollectedPointView(index, it.x, it.y, open.slotState(index) == SlotState.FilledSuspect) }
        }
        return base.copy(
            verb = open.verb,
            slots = slots,
            canReset = !open.isEmpty,
            message = open.message()?.let(MeasurementTexts::message),
            note = open.note,
            logVisible = open.isComplete && (open.canLog || open.loggedSequence != null),
            canLog = open.canLog,
            loggedSequence = open.loggedSequence,
            results = evaluation.results.map { result ->
                ResultRowUiState(
                    key = result.key,
                    valueText = result.value?.let { value -> MeasurementFormat.valueText(value, format) },
                    addAction = result.addAction,
                    addEnabled = result.addEnabled && result.value != null && !blocked,
                )
            },
            points = points,
            circle = evaluation.circle,
            lines = evaluation.lines,
            helpOpen = help,
        )
    }

    private fun frameAngles(frame: ReadoutFrame): FrameAngles =
        FrameAngles(alignmentArcSec = frame.alignment?.angleArcSec ?: 0.0, rotationArcSec = frame.partRotation?.phiArcSec ?: 0.0)

    /** The tolerance of the fits: `4 × res` of the plane axes. */
    private fun toleranceUm(frame: ReadoutFrame): Double {
        val (h, v, _) = planeAxesOf(frame.workspace.plane)
        val encoders = adapter.value?.encoders.orEmpty()
        val resolutions = listOf(h, v).map { axis ->
            val channel = primaryChannel(frame.profile, axis)
            channel?.let { letter -> encoders[letter.lowercaseChar()]?.resolution }
        }
        return MeasurementTolerance.toleranceUm(resolutions)
    }

    private fun primaryChannel(profile: MachineProfile, axis: AxisId): Char? = when (axis) {
        AxisId.X -> profile.x.primary.channel
        AxisId.Y -> profile.y.primary.channel
        AxisId.Z -> profile.z.primary.channel
        else -> null
    }

    companion object {
        /**
         * True when [probe] can be the reference instrument: not a tool
         * height setter, and a camera or a profile with a trigger port.
         */
        fun isValidInstrument(probe: ProbeProfile): Boolean = ReadoutFrame.validInstrument(probe)

        /** Gives the display format of the value texts: the unit and the decimals of the horizontal plane axis. */
        fun displayFormat(frame: ReadoutFrame): LogDisplayFormat {
            val metric = frame.metric(PlaneAxes.horizontal(frame.workspace.plane))
            val format = frame.profile.display.readoutFormat
            return LogDisplayFormat(metric = metric, precision = if (metric) format.mmDecimals else format.inchDecimals)
        }

        /** Gives the horizontal axis, the vertical axis, and the normal axis of [plane]. */
        fun planeAxesOf(plane: Plane): Triple<AxisId, AxisId, AxisId> {
            val h = PlaneAxes.horizontal(plane)
            val v = PlaneAxes.vertical(plane)
            val n = listOf(AxisId.X, AxisId.Y, AxisId.Z).first { axis -> axis != h && axis != v }
            return Triple(h, v, n)
        }
    }
}
