package org.superdro.core.ui.functions.patterns

import kotlin.math.atan2
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.superdro.core.functions.arc.AngleReference
import org.superdro.core.functions.arc.ArcCut
import org.superdro.core.functions.arc.ArcCutInput
import org.superdro.core.functions.arc.CutType
import org.superdro.core.functions.graphics.PlaneAxes
import org.superdro.core.functions.patterns.ArbitraryHoleCircle
import org.superdro.core.functions.patterns.AxisLetter
import org.superdro.core.functions.patterns.AxisPoint
import org.superdro.core.functions.patterns.AxisRange
import org.superdro.core.functions.patterns.AxisSwitches
import org.superdro.core.functions.patterns.EquidistantArray
import org.superdro.core.functions.patterns.HoleCircle
import org.superdro.core.functions.patterns.HoleCircleInput
import org.superdro.core.functions.patterns.HoleGrid
import org.superdro.core.functions.patterns.HoleGridInput
import org.superdro.core.functions.patterns.PatternBatch
import org.superdro.core.functions.patterns.PatternCommon
import org.superdro.core.functions.patterns.PatternField
import org.superdro.core.functions.patterns.PatternGroup
import org.superdro.core.functions.patterns.PatternPlanes
import org.superdro.core.functions.patterns.PatternPrefix
import org.superdro.core.functions.patterns.PatternResult
import org.superdro.core.functions.patterns.PatternTexts
import org.superdro.core.functions.patterns.PositionReference
import org.superdro.core.functions.patterns.PositionSource
import org.superdro.core.functions.patterns.StepAndRepeat
import org.superdro.core.model.AxisId
import org.superdro.core.model.Plane
import org.superdro.core.model.SubDatum
import org.superdro.core.model.Units
import org.superdro.core.readout.ReadoutFrame
import org.superdro.core.resources.Res
import org.superdro.core.resources.fn_value_not_number
import org.superdro.core.resources.fnp_no_points
import org.superdro.core.resources.fnp_position_center
import org.superdro.core.resources.fnp_position_first_hole
import org.superdro.core.resources.fnp_position_start_of_cut
import org.superdro.core.resources.fnp_reference_center
import org.superdro.core.resources.fnp_reference_first_hole
import org.superdro.core.resources.fnp_reference_radius_center
import org.superdro.core.resources.fnp_reference_start_of_cut
import org.superdro.core.store.StoreResult
import org.superdro.core.ui.functions.common.ErrorBlock
import org.superdro.core.ui.functions.common.FunctionServices
import org.superdro.core.ui.functions.common.LengthFieldState
import org.superdro.core.ui.functions.common.LengthTexts
import org.superdro.core.ui.functions.common.NumberFieldState
import org.superdro.core.ui.functions.common.PreviewPoint
import org.superdro.core.ui.functions.common.SubDatumBlocker
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.readout.TabReadout
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.toUiText
import org.superdro.core.ui.graphics.view.OperatorFrame

/**
 * The presenter of one function screen with a point preview.
 *
 * The math is in `core:functions` (`patterns`, `arc`). The presenter keeps the field values
 * (the full value of each field), makes the preview of the current inputs at each change, and at
 * "Create" writes the points of one call in one transaction with
 * `MachineRepository.addSubDatums` (names `"<group name> - <n>"`, the last ordinal; one tag).
 * The entry owns the presenter, so the screen keeps its values while the tab changes.
 *
 * The display frame: with a part rotation or an axis
 * alignment on, the screen works in the frame of the display. The readouts stay in machine
 * coordinates, so the tool shows at its absolute position and "Current" is that position. Each
 * new point goes through the inverse display transform ([OperatorFrame.toStored]) before the
 * save, so that the graphical view shows it where the user made it.
 *
 * @param stepSourceIds The sub-datums of the step and repeat. An empty list repeats all
 *   sub-datums of the workspace.
 */
class PatternPresenter(
    val kind: PatternKind,
    private val readout: TabReadout,
    private val services: FunctionServices,
    private val stepSourceIds: List<String> = emptyList(),
) : ScopedPresenter(services.context) {

    private val inputs = MutableStateFlow<PatternInputs?>(null)
    private val local = MutableStateFlow(Local())
    private val state = MutableStateFlow(PatternUiState(kind))

    /**
     * The state of the screen. An event changes it at once (not on a later dispatch), so that a
     * text field always shows the text that the user typed.
     */
    val uiState: StateFlow<PatternUiState> = state.asStateFlow()

    init {
        readout.frame.value?.let(::initialize)
        refresh()
        scope.launch {
            readout.frame.collect { frame ->
                if (frame != null) initialize(frame)
                refresh()
            }
        }
    }

    /** Makes the state of the screen from the frame, the inputs, and the local state. */
    private fun refresh() {
        val frame = readout.frame.value
        val current = inputs.value
        state.value = if (frame == null || current == null) PatternUiState(kind) else stateOf(frame, current, local.value)
    }

    private fun updateLocal(change: (Local) -> Local) {
        local.update(change)
        refresh()
    }

    // ---------------------------------------------------------------------------------------
    // Events

    /** The typed prefix. An empty prefix gives the default ("Circle", "Grid", "Arc"). */
    fun onPrefixChange(text: String) = edit { copy(prefix = text) }

    /** Selects the plane (a mill only; a lathe always uses `ZX`). The axis switches change with the plane. */
    fun onPlaneChange(plane: Plane) {
        val frame = readout.frame.value ?: return
        if (frame.profile.machineType.isLathe || !kind.selectablePlane) return
        val effective = PatternPlanes.effectivePlane(frame.profile.machineType, plane)
        edit { copy(plane = effective, switches = PatternPlanes.defaultSwitches(effective, frame.profile.use3dSubDatums)) }
    }

    /**
     * Selects the position source. "Custom" fills each enabled field with the current position.
     */
    fun onSourceChange(source: PositionSource) {
        val frame = readout.frame.value ?: return
        edit {
            if (source != PositionSource.Custom) {
                copy(source = source)
            } else {
                val current = currentDisplay(frame)
                var lengths = lengths
                AxisLetter.entries.forEach { axis ->
                    val value = current.of(axis) ?: return@forEach
                    val input = PatternInput.custom(axis)
                    lengths = lengths + (input to lengths.getValue(input).withValue(value * scaleOf(frame, axis)))
                }
                copy(source = source, lengths = lengths)
            }
        }
    }

    /** Turns the axis [axis] on or off: an axis that is off gives no coordinate (a 2D point when Z is off). */
    fun onAxisSwitch(axis: AxisLetter, on: Boolean) = edit {
        copy(
            switches = when (axis) {
                AxisLetter.X -> switches.copy(x = on)
                AxisLetter.Y -> switches.copy(y = on)
                AxisLetter.Z -> switches.copy(z = on)
            },
        )
    }

    /** Selects the position reference: the center, or the first point. */
    fun onReferenceChange(reference: PositionReference) = edit { copy(reference = reference) }

    /** Copies the stored point [subDatumId] into the custom fields and selects "Custom". */
    fun onSubDatumSelected(subDatumId: String?) {
        val frame = readout.frame.value ?: return
        val subDatum = subDatumId?.let { id -> frame.workspace.subDatums.firstOrNull { point -> point.id == id } }
        if (subDatum == null) {
            edit { copy(subDatumId = null) }
            return
        }
        val shown = frame.shownSubDatumPoint(subDatum)
        val values = mapOf(AxisLetter.X to shown.x, AxisLetter.Y to shown.y, AxisLetter.Z to subDatum.z)
        edit {
            var lengths = lengths
            values.forEach { (axis, value) ->
                if (value != null && switchOf(switches, axis)) {
                    val input = PatternInput.custom(axis)
                    lengths = lengths + (input to lengths.getValue(input).withValue(value * scaleOf(frame, axis)))
                }
            }
            copy(source = PositionSource.Custom, subDatumId = subDatum.id, lengths = lengths)
        }
    }

    /**
     * The typed text of the field [input]. A position field takes `++x` and `--x` with the current
     * position of its axis as the base.
     */
    fun onText(input: PatternInput, text: String) {
        val frame = readout.frame.value ?: return
        edit {
            if (input.isLength) {
                val base = baseOf(frame, input)
                copy(lengths = lengths + (input to lengths.getValue(input).withText(text, base)))
            } else {
                copy(numbers = numbers + (input to (numbers[input] ?: NumberFieldState()).withText(text)))
            }
        }
    }

    /** Changes the unit of the length field [input]: the field shows the same length in the new unit. */
    fun onUnit(input: PatternInput, metric: Boolean) = edit {
        copy(lengths = lengths + (input to lengths.getValue(input).withUnit(metric)))
    }

    /**
     * Adds the angle of the entry field to the list of the arbitrary hole circle: the angle
     * is normalized and rounded to 4 decimals; the list ignores a duplicate angle.
     */
    fun onAddAngle() = edit {
        val entry = numbers[PatternInput.AngleEntry] ?: NumberFieldState()
        val value = entry.value
        if (value != null && value.isNaN()) {
            copy(angleError = UiText.of(Res.string.fn_value_not_number, PatternField.HoleAngle.label.message().toUiText()))
        } else {
            val result = angles.addField(entry.arcSec)
            if (result.error != null) {
                copy(angleError = result.error!!.message.toUiText())
            } else {
                copy(angles = result.list, angleError = null, numbers = numbers + (PatternInput.AngleEntry to NumberFieldState()))
            }
        }
    }

    /** Removes the angle at [index] from the list. */
    fun onRemoveAngle(index: Int) = edit { copy(angles = angles.removeAt(index)) }

    /** Selects the cut type of the arc: internal `R − D/2` or external `R + D/2`. */
    fun onCutType(cutType: CutType) = edit { copy(cutType = cutType) }

    /** Selects the angle reference of the arc: the center or the tangent. */
    fun onAngleReference(reference: AngleReference) = edit { copy(angleReference = reference) }

    /**
     * "Create": the checks, then one transaction with the new points. The blocker
     * ([SubDatumBlocker]) and the live check of the position ("Current") come first. An error shows
     * in the error box and the screen stays open.
     */
    fun onCreate() {
        val frame = readout.frame.value ?: return
        val current = inputs.value ?: return
        SubDatumBlocker.message(frame.workspace)?.let { message ->
            updateLocal { state -> state.copy(blocked = message, closeAfterBlocked = false) }
            return
        }
        if (kind.hasPosition && current.source == PositionSource.Current) {
            readout.positionUnavailable()?.let { refusal ->
                services.sounds.error(readout.deviceId)
                updateLocal { state -> state.copy(errors = ErrorBlock(header(), listOf(refusal))) }
                return
            }
        }
        val computation = compute(frame, current)
        if (computation.errors.isNotEmpty()) {
            services.sounds.error(readout.deviceId)
            updateLocal { state -> state.copy(errors = ErrorBlock(header(), computation.errors)) }
            return
        }
        val group = computation.group ?: return
        if (group.points.isEmpty()) {
            updateLocal { state -> state.copy(errors = ErrorBlock(null, listOf(UiText.of(Res.string.fnp_no_points)))) }
            return
        }
        val groupId = services.wallClock.now().toEpochMilliseconds()
        val batch = PatternBatch.create(frame.workspace, group, groupId)
        val points = OperatorFrame.toStored(frame, batch.subDatums)
        scope.launch {
            when (val result = services.machines.addSubDatums(frame.workspace.id, points, namePrefix = group.groupName)) {
                is StoreResult.Ok -> updateLocal { state -> state.copy(created = result.value.size, errors = null) }
                is StoreResult.Refused -> updateLocal { state -> state.copy(errors = ErrorBlock(null, listOf(result.refusal.toUiText()))) }
            }
        }
    }

    /** The user closed the blocker dialog. */
    fun onBlockedDismissed() {
        updateLocal { state -> state.copy(blocked = null) }
    }

    // ---------------------------------------------------------------------------------------
    // The model

    private data class Local(
        val errors: ErrorBlock? = null,
        val blocked: UiText? = null,
        val closeAfterBlocked: Boolean = false,
        val created: Int? = null,
    )

    private class Computation(val result: PatternResult?, val errors: List<UiText>, val group: PatternGroup?)

    private fun edit(change: PatternInputs.() -> PatternInputs) {
        inputs.update { current -> current?.change() }
        updateLocal { state -> if (state.errors == null) state else state.copy(errors = null) }
    }

    private fun initialize(frame: ReadoutFrame) {
        if (inputs.value != null) return
        val profile = frame.profile
        val machineType = profile.machineType
        val metric = profile.metricDefault
        val plane = when {
            kind == PatternKind.StepAndRepeat || kind == PatternKind.EquidistantArray ->
                PatternPlanes.effectivePlane(machineType, PatternPlanes.defaultPlane(machineType))
            else -> PatternPlanes.effectivePlane(machineType, Plane.XY)
        }
        val lengths = LinkedHashMap<PatternInput, LengthFieldState>()
        val numbers = LinkedHashMap<PatternInput, NumberFieldState>()
        AxisLetter.entries.forEach { axis -> lengths[PatternInput.custom(axis)] = LengthFieldState(metric = metricOf(frame, axis)) }
        when (kind) {
            PatternKind.HoleCircle -> {
                lengths[PatternInput.Radius] = LengthFieldState(metric = metric)
                numbers[PatternInput.HoleCount] = NumberFieldState()
                numbers[PatternInput.StartAngle] = NumberFieldState.ofArcSec(HoleCircle.DEFAULT_START_ARCSEC)
                numbers[PatternInput.SweepAngle] = NumberFieldState.ofArcSec(HoleCircle.DEFAULT_SWEEP_ARCSEC)
            }
            PatternKind.ArbitraryHoleCircle -> {
                lengths[PatternInput.Radius] = LengthFieldState(metric = metric)
                numbers[PatternInput.AngleEntry] = NumberFieldState()
            }
            PatternKind.HoleGrid, PatternKind.StepAndRepeat -> {
                numbers[PatternInput.Repeat1] = NumberFieldState.ofCount(HoleGrid.DEFAULT_REPEAT)
                lengths[PatternInput.Step1] = LengthFieldState(metric = metric)
                numbers[PatternInput.Repeat2] = NumberFieldState.ofCount(HoleGrid.DEFAULT_REPEAT)
                lengths[PatternInput.Step2] = LengthFieldState(metric = metric)
                numbers[PatternInput.GridAngle] = NumberFieldState.ofArcSec(0.0)
            }
            PatternKind.EquidistantArray -> {
                numbers[PatternInput.Count] = NumberFieldState()
                val current = currentDisplay(frame)
                AxisLetter.entries.forEach { axis ->
                    val axisMetric = metricOf(frame, axis)
                    val scale = scaleOf(frame, axis)
                    lengths[PatternInput.start(axis)] = LengthFieldState.of(0.0, axisMetric)
                    lengths[PatternInput.end(axis)] = LengthFieldState.of(current.of(axis)?.let { value -> value * scale }, axisMetric)
                }
            }
            PatternKind.ArcCut -> {
                lengths[PatternInput.Radius] = LengthFieldState(metric = metric)
                lengths[PatternInput.CutterDiameter] = LengthFieldState.of(
                    ArcCut.defaultCutterDiameterUm(machineType, frame.workspace.toolXUm),
                    metric,
                )
                lengths[PatternInput.Stepover] = LengthFieldState(metric = metric)
                numbers[PatternInput.StartAngle] = NumberFieldState()
                numbers[PatternInput.SweepAngle] = NumberFieldState.ofArcSec(ArcCut.DEFAULT_SWEEP_ARCSEC)
            }
        }
        inputs.value = PatternInputs(
            plane = plane,
            switches = PatternPlanes.defaultSwitches(plane, profile.use3dSubDatums),
            lengths = lengths,
            numbers = numbers,
        )
        SubDatumBlocker.message(frame.workspace)?.let { message ->
            updateLocal { state -> state.copy(blocked = message, closeAfterBlocked = true) }
        }
    }

    private fun stateOf(frame: ReadoutFrame, current: PatternInputs, ui: Local): PatternUiState {
        val profile = frame.profile
        val machineType = profile.machineType
        val lathe = machineType.isLathe
        val computation = compute(frame, current)
        val group = computation.group
        val plane = previewPlane(frame, current)
        val horizontal = PlaneAxes.horizontal(plane)
        val vertical = PlaneAxes.vertical(plane)
        val sign = PlaneAxes.verticalSign(plane)
        val points = group?.points.orEmpty()
        val names = if (group != null && points.isNotEmpty()) {
            PatternBatch.create(frame.workspace, group, 0L).subDatums.map { point -> point.name }
        } else {
            emptyList()
        }
        val preview = points.mapIndexed { index, point ->
            PreviewPoint((point.ofAxis(horizontal) ?: 0.0), sign * (point.ofAxis(vertical) ?: 0.0), (index + 1).toString())
        }
        val origin = if (kind.hasPosition) {
            val o = origin(frame, current)
            PreviewPoint(o.ofAxis(horizontal) ?: 0.0, sign * (o.ofAxis(vertical) ?: 0.0))
        } else {
            null
        }
        val format = profile.display.readoutFormat
        fun text(axis: AxisLetter, value: Double?): String =
            if (value == null) "" else LengthTexts.format(value * scaleOf(frame, axis), metricOf(frame, axis), format)
        val rows = points.take(MAX_POINT_ROWS).mapIndexed { index, point ->
            PointRowUiState(names.getOrElse(index) { "" }, text(AxisLetter.X, point.x), text(AxisLetter.Y, point.y), text(AxisLetter.Z, point.z))
        }
        val showPosition = kind.hasPosition
        val currentPoint = currentDisplay(frame)
        val positionRows = if (!showPosition) {
            emptyList()
        } else {
            AxisLetter.entries.filter { axis -> !(lathe && axis == AxisLetter.Y) }.map { axis ->
                val machineEnabled = frame.enabled(axis.axisId)
                val stored = current.lengths.getValue(PatternInput.custom(axis))
                val field = when (current.source) {
                    PositionSource.Custom -> stored
                    PositionSource.Zero -> LengthFieldState.of(0.0, stored.metric)
                    PositionSource.Current -> LengthFieldState.of(currentPoint.of(axis)?.let { value -> value * scaleOf(frame, axis) }, stored.metric)
                }
                PositionRowUiState(
                    axis = axis,
                    label = labelOf(frame, axis),
                    on = machineEnabled && switchOf(current.switches, axis),
                    machineEnabled = machineEnabled,
                    diameter = scaleOf(frame, axis) != 1.0,
                    editable = current.source == PositionSource.Custom,
                    field = field,
                )
            }
        }
        return PatternUiState(
            kind = kind,
            loaded = true,
            title = titleOf(frame),
            machineType = machineType,
            prefix = current.prefix,
            prefixDefault = defaultPrefix(),
            plane = current.plane,
            planes = if (lathe) listOf(Plane.ZX) else listOf(Plane.XY, Plane.XZ, Plane.YZ),
            planeEnabled = !lathe && kind.selectablePlane,
            source = current.source,
            positionTitle = if (showPosition) positionTitleOf(current.reference) else null,
            positionRows = positionRows,
            sourceError = if (showPosition && current.source == PositionSource.Current) readout.positionUnavailable() else null,
            reference = current.reference,
            referenceLabels = referenceLabels(),
            subDatums = if (showPosition) frame.workspace.subDatums.map { point -> SubDatumChoice(point.id, point.name) } else emptyList(),
            subDatumId = current.subDatumId,
            fields = fieldsOf(frame, current),
            angles = current.angles.anglesArcSec.map { arcSec -> arcSec / Units.ARCSEC_PER_DEGREE },
            angleError = current.angleError,
            cutType = current.cutType,
            angleReference = current.angleReference,
            preview = preview,
            previewOrigin = origin,
            hAxis = labelOf(frame, horizontal.letter()),
            vAxis = labelOf(frame, vertical.letter()),
            pointRows = rows,
            pointCount = points.size,
            errors = ui.errors,
            blocked = ui.blocked,
            closeAfterBlocked = ui.closeAfterBlocked,
            created = ui.created,
            sourceCount = if (kind == PatternKind.StepAndRepeat) sources(frame).size else null,
        )
    }

    /** The checks and the points of the current inputs. */
    private fun compute(frame: ReadoutFrame, current: PatternInputs): Computation {
        val profile = frame.profile
        val machineType = profile.machineType
        val errors = ArrayList<UiText>()
        val invalid = HashSet<PatternInput>()
        fun length(input: PatternInput): Double? {
            val value = current.lengths[input]?.valueUm
            if (value != null && value.isNaN()) invalid += input
            return value
        }
        fun number(input: PatternInput): NumberFieldState {
            val field = current.numbers[input] ?: NumberFieldState()
            if (field.value?.isNaN() == true) invalid += input
            return field
        }
        // The custom position fields (the fields of an axis that is on are required).
        if (kind.hasPosition && current.source == PositionSource.Custom) {
            val switches = effectiveSwitches(frame, current)
            AxisLetter.entries.forEach { axis ->
                if (!switchOf(switches, axis)) return@forEach
                val value = current.lengths.getValue(PatternInput.custom(axis)).valueUm
                val label = UiText.Plain(labelOf(frame, axis))
                when {
                    value == null -> errors += PatternTexts.VALUE_REQUIRED.format(labelOf(frame, axis)).toUiText()
                    value.isNaN() -> errors += UiText.of(Res.string.fn_value_not_number, label)
                }
            }
        }
        val common = PatternCommon(
            prefix = current.prefix,
            plane = current.plane,
            origin = origin(frame, current),
            reference = current.reference,
            machineType = machineType,
            convention = profile.patternAngleConvention,
        )
        val result: PatternResult = when (kind) {
            PatternKind.HoleCircle -> HoleCircle.create(
                common,
                HoleCircleInput(
                    radiusUm = length(PatternInput.Radius),
                    holeCount = number(PatternInput.HoleCount).count,
                    startArcSec = number(PatternInput.StartAngle).arcSec,
                    sweepArcSec = number(PatternInput.SweepAngle).arcSec,
                ),
            )
            PatternKind.ArbitraryHoleCircle -> ArbitraryHoleCircle.create(common, length(PatternInput.Radius), current.angles)
            PatternKind.HoleGrid -> HoleGrid.create(common, gridInput(::length, ::number))
            PatternKind.StepAndRepeat -> StepAndRepeat.create(common, gridInput(::length, ::number), sources(frame), sourceRotationArcSec(frame))
            PatternKind.EquidistantArray -> {
                val axes = EquidistantArray.includedAxes(
                    machineType,
                    frame.enabled(AxisId.X),
                    frame.enabled(AxisId.Y),
                    frame.enabled(AxisId.Z),
                    profile.use3dSubDatums,
                )
                fun range(axis: AxisLetter): AxisRange {
                    val scale = scaleOf(frame, axis)
                    return AxisRange(
                        current.lengths[PatternInput.start(axis)]?.valueUm?.div(scale),
                        current.lengths[PatternInput.end(axis)]?.valueUm?.div(scale),
                    )
                }
                // The count is a plain integer, as `Integer.parseInt`.
                val count = current.numbers[PatternInput.Count]?.text?.trim()?.toIntOrNull()
                EquidistantArray.create(count, axes, range(AxisLetter.X), range(AxisLetter.Y), range(AxisLetter.Z))
            }
            PatternKind.ArcCut -> ArcCut.create(
                common,
                ArcCutInput(
                    radiusUm = length(PatternInput.Radius),
                    cutterDiameterUm = length(PatternInput.CutterDiameter),
                    stepoverUm = length(PatternInput.Stepover),
                    startArcSec = number(PatternInput.StartAngle).arcSec,
                    sweepArcSec = number(PatternInput.SweepAngle).arcSec,
                    cutType = current.cutType,
                    angleReference = current.angleReference,
                ),
            )
        }
        // A field that the evaluator refused gives "<label> value could not be converted to a
        // number". The error of the same field from `core:functions` does not show again.
        val labels = fieldsOf(frame, current).associate { field -> field.input to field.label }
        invalid.sortedBy { input -> input.ordinal }.forEach { input ->
            labels[input]?.let { label -> errors += UiText.of(Res.string.fn_value_not_number, label) }
        }
        when (result) {
            is PatternResult.Failure -> result.errors
                .filterNot { error -> error.field?.inputs()?.any { input -> input in invalid } == true }
                .forEach { error -> errors += error.message.toUiText() }
            is PatternResult.Success -> Unit
        }
        val group = (result as? PatternResult.Success)?.group
        return Computation(result, errors, if (errors.isEmpty()) group else null)
    }

    private fun gridInput(length: (PatternInput) -> Double?, number: (PatternInput) -> NumberFieldState): HoleGridInput =
        HoleGridInput(
            repeat1 = number(PatternInput.Repeat1).count,
            step1Um = length(PatternInput.Step1),
            repeat2 = number(PatternInput.Repeat2).count,
            step2Um = length(PatternInput.Step2),
            angleArcSec = number(PatternInput.GridAngle).arcSec,
        )

    /** The fields of the screen in the order of the screen, with their labels. */
    private fun fieldsOf(frame: ReadoutFrame, current: PatternInputs): List<InputUiState> {
        fun field(input: PatternInput, label: PatternField): InputUiState =
            InputUiState(input, label.label.message().toUiText(), current.lengths[input], current.numbers[input])
        return when (kind) {
            PatternKind.HoleCircle -> listOf(
                field(PatternInput.Radius, PatternField.CircleRadius),
                field(PatternInput.HoleCount, PatternField.HoleCount),
                field(PatternInput.StartAngle, PatternField.StartAngle),
                field(PatternInput.SweepAngle, PatternField.SweepAngle),
            )
            PatternKind.ArbitraryHoleCircle -> listOf(
                field(PatternInput.Radius, PatternField.CircleRadius),
                field(PatternInput.AngleEntry, PatternField.HoleAngle),
            )
            PatternKind.HoleGrid, PatternKind.StepAndRepeat -> {
                val labels = HoleGrid.fields(current.plane)
                listOf(
                    field(PatternInput.Repeat1, labels.repeat1),
                    field(PatternInput.Step1, labels.step1),
                    field(PatternInput.Repeat2, labels.repeat2),
                    field(PatternInput.Step2, labels.step2),
                    field(PatternInput.GridAngle, PatternField.GridAngle),
                )
            }
            PatternKind.EquidistantArray -> {
                val axes = EquidistantArray.includedAxes(
                    frame.profile.machineType,
                    frame.enabled(AxisId.X),
                    frame.enabled(AxisId.Y),
                    frame.enabled(AxisId.Z),
                    frame.profile.use3dSubDatums,
                )
                val starts = listOf(PatternField.StartX, PatternField.StartY, PatternField.StartZ)
                val ends = listOf(PatternField.EndX, PatternField.EndY, PatternField.EndZ)
                buildList {
                    add(field(PatternInput.Count, PatternField.NumberOfHoles))
                    AxisLetter.entries.forEachIndexed { index, axis ->
                        if (switchOf(axes, axis)) {
                            add(field(PatternInput.start(axis), starts[index]))
                            add(field(PatternInput.end(axis), ends[index]))
                        }
                    }
                }
            }
            PatternKind.ArcCut -> listOf(
                field(PatternInput.Radius, PatternField.PartRadius),
                field(PatternInput.CutterDiameter, PatternField.CutterDiameter),
                field(PatternInput.Stepover, PatternField.Stepover),
                field(PatternInput.StartAngle, PatternField.StartAngle),
                field(PatternInput.SweepAngle, PatternField.SweepAngle),
            )
        }
    }

    private fun titleOf(frame: ReadoutFrame): UiText {
        val machineType = frame.profile.machineType
        return when (kind) {
            PatternKind.HoleCircle -> HoleCircle.title(machineType)
            PatternKind.ArbitraryHoleCircle -> ArbitraryHoleCircle.title()
            PatternKind.HoleGrid -> HoleGrid.title(machineType)
            PatternKind.StepAndRepeat -> StepAndRepeat.title(if (stepSourceIds.isEmpty()) null else sources(frame).size)
            PatternKind.EquidistantArray -> EquidistantArray.title(machineType)
            PatternKind.ArcCut -> ArcCut.title()
        }.toUiText()
    }

    private fun defaultPrefix(): String = when (kind) {
        PatternKind.HoleCircle, PatternKind.ArbitraryHoleCircle -> PatternPrefix.DEFAULT_CIRCLE
        PatternKind.HoleGrid, PatternKind.StepAndRepeat, PatternKind.EquidistantArray -> PatternPrefix.DEFAULT_GRID
        PatternKind.ArcCut -> PatternPrefix.DEFAULT_ARC
    }

    private fun positionTitleOf(reference: PositionReference): UiText = when (kind) {
        PatternKind.HoleCircle, PatternKind.ArbitraryHoleCircle ->
            if (reference == PositionReference.Center) UiText.of(Res.string.fnp_position_center) else UiText.of(Res.string.fnp_position_first_hole)
        PatternKind.ArcCut ->
            if (reference == PositionReference.Center) UiText.of(Res.string.fnp_position_center) else UiText.of(Res.string.fnp_position_start_of_cut)
        else -> UiText.of(Res.string.fnp_position_first_hole)
    }

    private fun referenceLabels(): Pair<UiText, UiText>? = when (kind) {
        PatternKind.HoleCircle, PatternKind.ArbitraryHoleCircle ->
            UiText.of(Res.string.fnp_reference_center) to UiText.of(Res.string.fnp_reference_first_hole)
        PatternKind.ArcCut -> UiText.of(Res.string.fnp_reference_radius_center) to UiText.of(Res.string.fnp_reference_start_of_cut)
        else -> null
    }

    private fun header(): UiText = PatternTexts.ERROR_HEADER.message().toUiText()

    /** The source sub-datums of the step and repeat, in the list order of the workspace. */
    private fun sources(frame: ReadoutFrame): List<SubDatum> {
        val all = frame.workspace.subDatums
        if (stepSourceIds.isEmpty()) return all
        val wanted = stepSourceIds.toHashSet()
        return all.filter { point -> point.id in wanted }
    }

    /** The plane of the preview: the plane of the pattern, or the default plane of the equidistant array. */
    private fun previewPlane(frame: ReadoutFrame, current: PatternInputs): Plane =
        if (frame.profile.machineType.isLathe) Plane.ZX else current.plane

    /** The axis switches with the axes of the machine: a disabled axis and the Y of a lathe give no coordinate. */
    private fun effectiveSwitches(frame: ReadoutFrame, current: PatternInputs): AxisSwitches = AxisSwitches(
        x = current.switches.x && frame.enabled(AxisId.X),
        y = current.switches.y && frame.enabled(AxisId.Y) && !frame.profile.machineType.isLathe,
        z = current.switches.z && frame.enabled(AxisId.Z),
    )

    /** The origin of the pattern from the position source, in the frame of the display. */
    private fun origin(frame: ReadoutFrame, current: PatternInputs): AxisPoint {
        val switches = effectiveSwitches(frame, current)
        return when (current.source) {
            PositionSource.Current -> PositionSource.Current.origin(switches, currentDisplay(frame))
            PositionSource.Zero -> PositionSource.Zero.origin(switches, AxisPoint())
            PositionSource.Custom -> {
                fun custom(axis: AxisLetter): Double? {
                    val value = current.lengths.getValue(PatternInput.custom(axis)).valueUm ?: return null
                    // A field that the evaluator refused gives 0 for the preview; "Create" refuses it.
                    return if (value.isNaN()) 0.0 else value / scaleOf(frame, axis)
                }
                PositionSource.Custom.origin(switches, AxisPoint(), AxisPoint(custom(AxisLetter.X), custom(AxisLetter.Y), custom(AxisLetter.Z)))
            }
        }
    }

    /**
     * The current absolute position of each enabled axis with the tool offset. It is also
     * the position in the frame of the display: a part rotation turns the part in the
     * view, not the tool, and the absolute X and Y already have the axis alignment. A disabled
     * axis or an axis with no value gives null.
     */
    private fun currentDisplay(frame: ReadoutFrame): AxisPoint {
        fun value(axis: AxisId): Double? {
            if (!frame.enabled(axis)) return null
            val reading = frame.reading(axis)
            return reading.absolute.takeIf { reading.hasValue && it.isFinite() }
        }
        return AxisPoint(value(AxisId.X), value(AxisId.Y), value(AxisId.Z))
    }

    /**
     * The turn of the source vector of the step and repeat on a mill: the part rotation,
     * then the axis alignment, with no pivot. Counter-clockwise is
     * positive. 0 when no rotation is active.
     */
    private fun sourceRotationArcSec(frame: ReadoutFrame): Double {
        if (frame.profile.machineType.isLathe) return 0.0
        val rotation = frame.partRotation
        val alignment = frame.alignment
        if (rotation == null && alignment == null) return 0.0
        fun shown(x: Double, y: Double): Pair<Double, Double> {
            val turned = rotation?.toDisplay(x, y)
            val tx = turned?.x ?: x
            val ty = turned?.y ?: y
            val aligned = alignment?.forward(tx, ty)
            return (aligned?.x ?: tx) to (aligned?.y ?: ty)
        }
        val (ox, oy) = shown(0.0, 0.0)
        val (ux, uy) = shown(1_000.0, 0.0)
        val degrees = atan2(uy - oy, ux - ox) * 180.0 / kotlin.math.PI
        return degrees * Units.ARCSEC_PER_DEGREE
    }

    /**
     * The base of `++x` and `--x` of the field [input] in µm of the field: the current position of
     * the axis of a position field, else 0.
     */
    private fun baseOf(frame: ReadoutFrame, input: PatternInput): Double {
        val axis = when (input) {
            PatternInput.CustomX, PatternInput.StartX, PatternInput.EndX -> AxisLetter.X
            PatternInput.CustomY, PatternInput.StartY, PatternInput.EndY -> AxisLetter.Y
            PatternInput.CustomZ, PatternInput.StartZ, PatternInput.EndZ -> AxisLetter.Z
            else -> return 0.0
        }
        val value = currentDisplay(frame).of(axis) ?: return 0.0
        return value * scaleOf(frame, axis)
    }

    /** The unit of the position field of [axis]: the unit of that readout axis. */
    private fun metricOf(frame: ReadoutFrame, axis: AxisLetter): Boolean = frame.metric(axis.axisId)

    /**
     * The factor of the position fields of [axis]: 2 for a lathe X in the diameter mode (the field
     * shows and takes a diameter, as the sub-datum edit), else 1. The stored X is a radius.
     */
    private fun scaleOf(frame: ReadoutFrame, axis: AxisLetter): Double =
        if (axis == AxisLetter.X && frame.profile.machineType.isLathe && frame.state(AxisId.X).diameterMode) 2.0 else 1.0

    private fun labelOf(frame: ReadoutFrame, axis: AxisLetter): String = when (axis) {
        AxisLetter.X -> frame.profile.x.label
        AxisLetter.Y -> frame.profile.y.label
        AxisLetter.Z -> frame.profile.z.label
    }.ifBlank { axis.text }

    companion object {
        /** The point list shows this many rows at most. */
        const val MAX_POINT_ROWS: Int = 200
    }
}

/** The axis of `core:model` of a pattern axis. */
internal val AxisLetter.axisId: AxisId
    get() = when (this) {
        AxisLetter.X -> AxisId.X
        AxisLetter.Y -> AxisId.Y
        AxisLetter.Z -> AxisId.Z
    }

private fun AxisId.letter(): AxisLetter = when (this) {
    AxisId.Y -> AxisLetter.Y
    AxisId.Z -> AxisLetter.Z
    else -> AxisLetter.X
}

private fun AxisPoint.of(axis: AxisLetter): Double? = when (axis) {
    AxisLetter.X -> x
    AxisLetter.Y -> y
    AxisLetter.Z -> z
}

private fun AxisPoint.ofAxis(axis: AxisId): Double? = of(axis.letter())

internal fun switchOf(switches: AxisSwitches, axis: AxisLetter): Boolean = when (axis) {
    AxisLetter.X -> switches.x
    AxisLetter.Y -> switches.y
    AxisLetter.Z -> switches.z
}
