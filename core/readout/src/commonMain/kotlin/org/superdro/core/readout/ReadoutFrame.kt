package org.superdro.core.readout

import kotlin.math.PI
import kotlin.math.abs
import org.superdro.core.model.AxisAlignmentSource
import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.MachineMode
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.Plane
import org.superdro.core.model.ProbeProfile
import org.superdro.core.model.ProbeType
import org.superdro.core.model.SubDatum
import org.superdro.core.model.Workspace
import org.superdro.core.readout.input.InputSnapshot

/**
 * The readout values of one axis.
 *
 * @property r The sum of the inputs `R` (µm, arc seconds, or RPM). NaN when the axis has no
 *   value.
 * @property instrumentOffset The instrument offset `H` in use, or 0.
 * @property toolOffset The tool offset `T` in use, or 0. The measurement mode suspends it on X,
 *   Y, and Z.
 * @property machinePosition The machine position: `R` without the absolute offset, the tool
 *   offset, and the instrument offset.
 * @property absolute `abs = R + H + T + A`, after the axis alignment rotation of X and Y.
 * @property value `v`: `abs`, plus the incremental offset or the sub-datum offset in the
 *   incremental mode.
 * @property shown `post.h(v)`.
 * @property held The held shown value, or null when the hold is off.
 * @property display The value in the display unit: `convert(hold ? held : shown)`.
 * @property text The formatted [display].
 * @property incremental True when the axis reads incremental. The angular lock makes
 *   the angular axis read absolute.
 * @property locked True when a rotation uses the angular encoder: the angular axis then reads
 *   absolute.
 * @property subDatumOffset The sub-datum offset `S` in use, or null.
 * @property modified True when an offset or a projection changes the axis: the warning state.
 */
data class AxisReading(
    val axis: AxisId,
    val enabled: Boolean,
    val r: Double,
    val instrumentOffset: Double,
    val toolOffset: Double,
    val machinePosition: Double,
    val absolute: Double,
    val value: Double,
    val shown: Double,
    val held: Double?,
    val display: Double,
    val text: String,
    val unit: DisplayUnit,
    val incremental: Boolean,
    val locked: Boolean,
    val subDatumOffset: Double?,
    val modified: Boolean,
) {
    /** True when `R` is a finite number. */
    val hasValue: Boolean get() = r.isFinite()
}

/**
 * The readout model of one machine profile at one time. It combines the stored workspace state with
 * the sums `R` of the input stage.
 *
 * ```text
 * abs   = R + (H if in use) + (T if in use) + A          (H: X and Y in the measurement mode)
 * abs   = the X/Y alignment rotation of abs              (measurement mode)
 * v     = abs
 * if rel: v = v + (I if no S) + (S if S is active)
 * shown = post.h(v)
 * text  = format(convert(hold ? heldValue : shown))
 * ```
 *
 * The incremental offset is in use only when the axis reads incremental and no sub-datum offset
 * is active. The tachometer uses `abs = R + A` and no `T`. The incremental offsets are in the
 * display frame. The absolute offsets are in the machine frame.
 *
 * @property inputs The sum `R` of each axis (µm, arc seconds, or RPM). Null or no entry means
 *   "no value".
 * @property instrument The selected reference instrument of the measurement mode, or null.
 * @property hold The held shown value of each axis with the hold on.
 * @property projectedAxes The axes that a projection changes.
 */
class ReadoutFrame(
    val profile: MachineProfile,
    val workspace: Workspace,
    val inputs: Map<AxisId, Double?> = emptyMap(),
    val instrument: ProbeProfile? = null,
    val hold: Map<AxisId, Double> = emptyMap(),
    val projectedAxes: Set<AxisId> = emptySet(),
) {

    /** True when the measurement mode applies: the mode is "measurement" and the machine is not a lathe. */
    val measurementActive: Boolean = profile.machineMode == MachineMode.Measurement && !profile.machineType.isLathe

    /** The reference instrument in use: a valid profile in the measurement mode, or null. */
    val instrumentInUse: ProbeProfile? = instrument?.takeIf { measurementActive && validInstrument(it) }

    private val alignmentOn: Boolean =
        measurementActive && workspace.axisAlignment.active && workspace.plane == Plane.XY

    /**
     * True when the angular axis reads absolute: the X/Y alignment or the part rotation is
     * active and uses the angular encoder.
     */
    val angularLocked: Boolean =
        (alignmentOn && workspace.axisAlignment.source == AxisAlignmentSource.AngularEncoder) ||
            (workspace.partRotation.active && workspace.partRotation.linkedToAngularEncoder)

    /** Gives the stored state of [axis]. */
    fun state(axis: AxisId): AxisState = workspace.axisStates[axis] ?: AxisState.forAxis(axis, profile.machineType)

    /** Gives `R` of [axis], or NaN. */
    fun r(axis: AxisId): Double = inputs[axis] ?: Double.NaN

    /** True when [axis] is enabled in the machine profile. */
    fun enabled(axis: AxisId): Boolean = when (axis) {
        AxisId.X -> profile.x.enabled
        AxisId.Y -> profile.y.enabled
        AxisId.Z -> profile.z.enabled
        AxisId.Angular -> profile.angular.enabled
        AxisId.Caliper -> profile.caliper.enabled
        AxisId.Tachometer -> profile.tachometer.enabled
    }

    /** Gives the post-processor of [axis]. */
    fun postProcessor(axis: AxisId): PostProcessor = PostProcessor.of(axis, state(axis), profile.machineType)

    /** True when [axis] shows mm. Only for X, Y, Z, and the caliper. */
    fun metric(axis: AxisId): Boolean = state(axis).metric ?: profile.metricDefault

    /** Gives the unit converter of [axis]. */
    fun converter(axis: AxisId): UnitConverter = when (axis) {
        AxisId.Angular -> {
            val state = state(axis)
            val divisions = state.divisionsPerRev
            if (state.divisionUnits && divisions != null && divisions >= 1) UnitConverter.Divisions(divisions) else UnitConverter.Degrees
        }
        AxisId.Tachometer -> UnitConverter.Rpm
        else -> UnitConverter.linear(metric(axis))
    }

    /** Gives the number pattern of [axis]. */
    fun pattern(axis: AxisId): NumberPattern = when (axis) {
        AxisId.Angular -> ReadoutPatterns.ANGULAR
        AxisId.Tachometer -> if (state(axis).surfaceSpeedMode) ReadoutPatterns.SURFACE_SPEED else ReadoutPatterns.RPM
        else -> ReadoutPatterns.linear(profile.display.readoutFormat, metric(axis))
    }

    /** True when the tool offset of [axis] is in use: active and not suspended. */
    fun toolOffsetInUse(axis: AxisId): Boolean {
        if (axis == AxisId.Tachometer) return false
        val state = state(axis)
        if (!state.toolOffsetActive) return false
        return !(measurementActive && (axis == AxisId.X || axis == AxisId.Y || axis == AxisId.Z))
    }

    /** Gives the instrument offset `H` of [axis]: the X or Y offset of the instrument, or 0. */
    fun instrumentOffset(axis: AxisId): Double {
        val profile = instrumentInUse ?: return 0.0
        return when (axis) {
            AxisId.X -> profile.xOffsetUm
            AxisId.Y -> profile.yOffsetUm
            else -> 0.0
        }
    }

    /** Gives `R + H + T` of [axis] for the sum [r]. */
    fun machineOffsets(axis: AxisId, r: Double = r(axis)): Double {
        val tool = if (toolOffsetInUse(axis)) state(axis).toolOffset else 0.0
        return r + instrumentOffset(axis) + tool
    }

    /** Gives `abs0 = R + H + T + A` of [axis] for the sum [r], before the rotation. */
    fun unrotatedAbsolute(axis: AxisId, r: Double = r(axis)): Double = machineOffsets(axis, r) + state(axis).absoluteOffset

    private val angularAbsoluteArcSec: Double by lazy {
        PostProcessor.AngularWrap.toShown(unrotatedAbsolute(AxisId.Angular))
    }

    /**
     * The X/Y alignment rotation in use, or null. The angle is the stored angle, or
     * `−(A − offset)` for the source "angular encoder".
     */
    val alignment: PlaneRotation? by lazy {
        if (!alignmentOn) {
            null
        } else {
            val alignment = workspace.axisAlignment
            val angle = if (alignment.source == AxisAlignmentSource.AngularEncoder) {
                -(angularAbsoluteArcSec - alignment.encoderOffsetArcSec)
            } else {
                alignment.angleArcSec
            }
            PlaneRotation(angle)
        }
    }

    /** The rotate part transform in use, or null. */
    val partRotation: PartRotationTransform? by lazy {
        PartRotationTransform.of(workspace.partRotation, angularAbsoluteArcSec)
    }

    /** The active sub-datum, or null. */
    val activeSubDatum: SubDatum? by lazy {
        workspace.activeSubDatumId?.let { id -> workspace.subDatums.firstOrNull { subDatum -> subDatum.id == id } }
    }

    /**
     * The sub-datum offset `S = −c` of each axis that the active sub-datum has: X, Y, and Angular,
     * and Z on a lathe or with 3D sub-datums on. The part
     * rotation and the X/Y alignment move the X and Y coordinates first.
     */
    val subDatumOffsets: Map<AxisId, Double> by lazy {
        val subDatum = activeSubDatum ?: return@lazy emptyMap()
        val offsets = LinkedHashMap<AxisId, Double>()
        val point = shownSubDatumPoint(subDatum)
        point.x?.let { x -> offsets[AxisId.X] = -x }
        point.y?.let { y -> offsets[AxisId.Y] = -y }
        if (profile.machineType.isLathe || profile.use3dSubDatums) subDatum.z?.let { z -> offsets[AxisId.Z] = -z }
        subDatum.a?.let { a -> offsets[AxisId.Angular] = -a }
        offsets
    }

    /** Gives the X and Y of [subDatum] in the display frame: the part rotation, then the alignment. */
    fun shownSubDatumPoint(subDatum: SubDatum): NullablePlanePoint {
        val x = subDatum.x
        val y = subDatum.y
        if (x == null || y == null) return NullablePlanePoint(x, y)
        var point = PlanePoint(x, y)
        partRotation?.let { rotation -> point = rotation.toDisplay(point.x, point.y) }
        alignment?.let { rotation -> point = rotation.forward(point.x, point.y) }
        return NullablePlanePoint(point.x, point.y)
    }

    /**
     * Gives the absolute position `abs` of each axis for the sums [rValues], with the current
     * offsets and the X/Y alignment. The probe functions use it with the latched sums of a
     * [org.superdro.core.readout.input.ProbeEvent].
     */
    fun absoluteFor(rValues: Map<AxisId, Double>): Map<AxisId, Double> {
        val result = LinkedHashMap<AxisId, Double>()
        rValues.forEach { (axis, r) ->
            result[axis] = if (axis == AxisId.Tachometer) r + state(axis).absoluteOffset else unrotatedAbsolute(axis, r)
        }
        val rotation = alignment
        val x = result[AxisId.X]
        val y = result[AxisId.Y]
        if (rotation != null && x != null && y != null) {
            val turned = rotation.forward(x, y)
            result[AxisId.X] = turned.x
            result[AxisId.Y] = turned.y
        }
        return result
    }

    /** The reading of each axis. */
    val readings: Map<AxisId, AxisReading> by lazy { computeReadings() }

    /** Gives the reading of [axis]. */
    fun reading(axis: AxisId): AxisReading = readings.getValue(axis)

    /** Gives a frame with [workspace] and [hold], and the same inputs. */
    fun with(workspace: Workspace = this.workspace, hold: Map<AxisId, Double> = this.hold): ReadoutFrame =
        ReadoutFrame(profile, workspace, inputs, instrument, hold, projectedAxes)

    private fun computeReadings(): Map<AxisId, AxisReading> {
        val absolutes = LinkedHashMap<AxisId, Double>()
        AxisId.entries.forEach { axis ->
            absolutes[axis] = if (axis == AxisId.Tachometer) r(axis) + state(axis).absoluteOffset else unrotatedAbsolute(axis)
        }
        alignment?.let { rotation ->
            val turned = rotation.forward(absolutes.getValue(AxisId.X), absolutes.getValue(AxisId.Y))
            absolutes[AxisId.X] = turned.x
            absolutes[AxisId.Y] = turned.y
        }
        val readings = LinkedHashMap<AxisId, AxisReading>()
        // The surface speed of the tachometer on a lathe needs the X absolute position.
        AxisId.entries.filter { axis -> axis != AxisId.Tachometer }.forEach { axis ->
            readings[axis] = reading(axis, absolutes.getValue(axis), xAbsolute = null)
        }
        readings[AxisId.Tachometer] = reading(AxisId.Tachometer, absolutes.getValue(AxisId.Tachometer), absolutes.getValue(AxisId.X))
        return readings
    }

    private fun reading(axis: AxisId, absolute: Double, xAbsolute: Double?): AxisReading {
        val state = state(axis)
        val locked = axis == AxisId.Angular && angularLocked
        val incremental = state.relative && !locked
        val subDatum = if (incremental) subDatumOffsets[axis] else null
        val value = when {
            !incremental -> absolute
            subDatum != null -> absolute + subDatum
            else -> absolute + state.incrementalOffset
        }
        val post = postProcessor(axis)
        val shown = post.toShown(value)
        val held = hold[axis]
        val base = held ?: shown
        val r = r(axis)
        val tool = if (toolOffsetInUse(axis)) state.toolOffset else 0.0
        val instrumentOffset = instrumentOffset(axis)
        val surfaceSpeed = axis == AxisId.Tachometer && state.surfaceSpeedMode
        val display: Double
        val unit: DisplayUnit
        if (surfaceSpeed) {
            val metric = profile.metricDefault
            display = SurfaceSpeed.speed(base, SurfaceSpeed.diameterUm(profile, workspace, xAbsolute ?: Double.NaN), metric)
            unit = if (metric) DisplayUnit.MetersPerMinute else DisplayUnit.SurfaceFeetPerMinute
        } else {
            val converter = converter(axis)
            display = converter.toDisplay(base)
            unit = converter.unit
        }
        val modified = tool != 0.0 || instrumentOffset != 0.0 || axis in projectedAxes ||
            ((axis == AxisId.X || axis == AxisId.Y) && alignment != null)
        return AxisReading(
            axis = axis,
            enabled = enabled(axis),
            r = r,
            instrumentOffset = instrumentOffset,
            toolOffset = tool,
            machinePosition = r,
            absolute = absolute,
            value = value,
            shown = shown,
            held = held,
            display = display,
            text = pattern(axis).format(display),
            unit = unit,
            incremental = incremental,
            locked = locked,
            subDatumOffset = subDatum,
            modified = modified,
        )
    }

    companion object {
        /**
         * Gives the frame of [profile] with the active workspace (or [workspace]) and the sums of
         * [snapshot].
         */
        fun of(
            profile: MachineProfile,
            snapshot: InputSnapshot,
            workspace: Workspace = profile.activeWorkspace ?: Workspace.forMachine(profile.machineType),
            instrument: ProbeProfile? = null,
            hold: Map<AxisId, Double> = emptyMap(),
        ): ReadoutFrame = ReadoutFrame(
            profile = profile,
            workspace = workspace,
            inputs = snapshot.axisValues(),
            instrument = instrument,
            hold = hold,
            projectedAxes = snapshot.axes.values.filter { value -> value.projected }.mapTo(LinkedHashSet()) { value -> value.axis },
        )

        /**
         * True when [profile] can be the reference instrument: not a tool height setter, and a
         * camera or a profile with a trigger port.
         */
        fun validInstrument(profile: ProbeProfile): Boolean =
            profile.type != ProbeType.ToolHeightSetter && (profile.type == ProbeType.Camera || profile.port != null)
    }
}

/** A point of the X-Y plane in µm with coordinates that can be absent. */
data class NullablePlanePoint(val x: Double?, val y: Double?)

/**
 * The surface speed of the tachometer:
 * `speed = |RPM × D × π| / U`, `U` = 1000000 in metric mode (m/min) and 304800 in inch mode
 * (ft/min, SFM). Format `###0.00`.
 */
object SurfaceSpeed {

    /** `U` in metric mode: µm in one meter. */
    const val UM_PER_METER: Double = 1_000_000.0

    /** `U` in inch mode: µm in one foot. */
    const val UM_PER_FOOT: Double = 304_800.0

    /** Gives the surface speed of [rpm] at the diameter [diameterUm]. */
    fun speed(rpm: Double, diameterUm: Double, metric: Boolean): Double =
        abs(rpm * diameterUm * PI) / if (metric) UM_PER_METER else UM_PER_FOOT

    /**
     * Gives the diameter `D` (µm). A mill uses the diameter of the active tool. A lathe uses
     * `D = 2 × |X abs|`, because the X scale measures the radius.
     *
     * @param xAbsoluteUm The X absolute position before the diameter post-processor.
     */
    fun diameterUm(profile: MachineProfile, workspace: Workspace, xAbsoluteUm: Double): Double =
        if (profile.machineType.isLathe) 2.0 * abs(xAbsoluteUm) else workspace.toolXUm

    /** Gives the text of a surface speed. */
    fun text(speed: Double): String = ReadoutPatterns.SURFACE_SPEED.format(speed)
}
