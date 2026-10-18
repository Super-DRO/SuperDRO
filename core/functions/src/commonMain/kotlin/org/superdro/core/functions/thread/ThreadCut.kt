package org.superdro.core.functions.thread

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan
import org.superdro.core.model.ThreadInfeedMode
import org.superdro.core.model.ThreadSpec

/** The retract state of the thread cut panel. */
enum class RetractState {
    /** The tool is out of the crests by the clearance or more. */
    Clear,

    /** The tool is in the thread. */
    Engaged,

    /**
     * The tool is not clear and Z is past the retract limit: `Zmin − W₁` for a right-hand thread,
     * or `Zmax + W₂` for a left-hand thread.
     */
    PastLimit,
}

/** The depth phase of the thread cut panel. */
enum class DepthPhase { Safe, Cutting, PastFinal }

/**
 * The text of the retract light.
 *
 * - [SelectFinishCorner]: "Select the finish corner" (no active sub-datum, no light).
 * - [ClearOfCrests]: "Clear of the crests" (light `thread_cut_light_clear`).
 * - [InTheCut]: "In the cut" (light `thread_cut_light_engaged`).
 * - [PastTheRoot]: "Past the root" (light `thread_cut_light_past_root`).
 * - [PastRetractLimit]: "Past the retract limit" (it shows in SuperDRO).
 */
enum class ThreadCutStatus { SelectFinishCorner, ClearOfCrests, InTheCut, PastTheRoot, PastRetractLimit }

/**
 * A warning of the thread cut panel.
 *
 * - [XNotReferenced]: the X axis has no reference. The math treats the X value
 *   as the radius from the spindle axis, so the values are not correct. The text: "X has no
 *   reference. Set X to zero on the spindle axis before you cut the thread."
 */
enum class ThreadCutWarning { XNotReferenced }

/**
 * The pass record of a thread, in µm.
 *
 * @property deepestUm `w_state_cut_deepest`: the deepest recorded X diameter, or null.
 * @property previousDeepestUm `w_state_cut_previous_deepest`: the record before it, or null.
 * @property threadId `w_state_cut_deepest_thread`: the thread of the two records, or null.
 */
data class ThreadPassRecord(
    val deepestUm: Double? = null,
    val previousDeepestUm: Double? = null,
    val threadId: String? = null,
)

/**
 * The stroke of the pass record: the smallest and the largest Z inside the thread length since the
 * last reset, and the latch flag.
 */
data class ThreadStroke(
    val minZUm: Double = Double.POSITIVE_INFINITY,
    val maxZUm: Double = Double.NEGATIVE_INFINITY,
    val latched: Boolean = false,
)

/** One position of the pass recorder in µm: the absolute X value and the Z value. */
data class ThreadPosition(val xUm: Double, val zUm: Double)

/**
 * The state of the pass recorder: the record, the stroke, the last position, and the active start
 * number.
 */
data class ThreadCutState(
    val record: ThreadPassRecord = ThreadPassRecord(),
    val stroke: ThreadStroke = ThreadStroke(),
    val last: ThreadPosition? = null,
    val activeStart: Int = 1,
)

/**
 * The live values of the thread cut panel, in µm.
 *
 * @property lastPassUm "Last pass": the X readout value at the deepest recorded pass, or null ("—").
 * @property infeedUm "Infeed": the radial depth of the tool beyond that pass, or null ("—").
 * @property cutLoadRatio "Cut load ≈ %1$s×" with one decimal, or null ("—").
 * @property clearDiameterUm The X diameter at which the tool is clear of the crests (the clearance
 *   limit): `MinorDiameter − clr` (internal) or `MajorDiameter + clr` (external).
 * @property retractLimitZUm The Z retract limit: `Zmin − W₁` (right-hand) or `Zmax + W₂` (left-hand).
 * @property schedule The generated pass schedule for the thread.
 * @property nextPass The next scheduled pass after the deepest recorded pass, or null.
 * @property activeStart The active 1-based start number (`1..totalStarts`).
 * @property totalStarts The total number of starts of the thread.
 */
data class ThreadCutValues(
    val lastPassUm: Double?,
    val infeedUm: Double?,
    val cutLoadRatio: Double?,
    val retract: RetractState,
    val phase: DepthPhase,
    val status: ThreadCutStatus,
    val clearDiameterUm: Double,
    val retractLimitZUm: Double,
    val warnings: Set<ThreadCutWarning>,
    val schedule: PassSchedule = PassSchedule(
        infeedMode = ThreadInfeedMode.ModifiedFlank,
        infeedAngleDeg = 29.5,
        totalRadialDepthUm = 0.0,
        roughingPassCount = 0,
        passes = emptyList(),
    ),
    val nextPass: ScheduledPass? = null,
    val activeStart: Int = 1,
    val totalStarts: Int = 1,
)

/**
 * The thread cut panel math.
 *
 * The inputs: `x` = the absolute X value in µm, which the math treats as the radius from the
 * spindle axis; `c` = the X readout value before the diameter factor; `d` = the deepest recorded
 * diameter; `e` = the record before it. Crest = the pre-op diameter, root = the cut-to diameter.
 */
object ThreadCut {

    /** The diametral clearance of a mm thread: 1000 µm (0.5 mm radial). */
    const val CLEARANCE_METRIC_UM: Double = 1000.0

    /** The diametral clearance of an inch thread: 1016 µm (0.020 in radial). */
    const val CLEARANCE_INCH_UM: Double = 1016.0

    /** tan 30°. */
    const val TAN_30: Double = 0.5773503

    /** Gives the diametral clearance `clr` of the thread unit. */
    fun clearanceUm(spec: ThreadSpec): Double =
        if (spec.form.unit == ThreadUnit.Inch) CLEARANCE_INCH_UM else CLEARANCE_METRIC_UM

    /** `depth(D) = internal ? D − crest : crest − D`, diametral µm. */
    fun depthUm(spec: ThreadSpec, diameterUm: Double): Double =
        if (spec.internal) diameterUm - spec.preOpDiameterUm else spec.preOpDiameterUm - diameterUm

    /** Gives the clearance limit of the X diameter (see [ThreadCutValues.clearDiameterUm]). */
    fun clearDiameterUm(spec: ThreadSpec): Double =
        if (spec.internal) spec.minorDiameterUm - clearanceUm(spec) else spec.majorDiameterUm + clearanceUm(spec)

    /** Gives the Z retract limit: `Zmin − W₁` for right-hand, or `Zmax + W₂` for left-hand. */
    fun retractLimitZUm(spec: ThreadSpec): Double = if (spec.leftHand) {
        max(spec.startZUm, spec.stopZUm) + (spec.rightReliefUm ?: 0.0)
    } else {
        min(spec.startZUm, spec.stopZUm) - (spec.leftReliefUm ?: 0.0)
    }

    /**
     * Gives the retract state. Internal: clear when `2|x| ≤ MinorDiameter − clr`. External: clear
     * when `2|x| ≥ MajorDiameter + clr`. Else past the limit when [zUm] is past the Z retract
     * limit (`zUm < limit` for right-hand, `zUm > limit` for left-hand), else engaged.
     */
    fun retractState(spec: ThreadSpec, xUm: Double, zUm: Double?): RetractState {
        val diameter = 2.0 * abs(xUm)
        val clear = if (spec.internal) diameter <= clearDiameterUm(spec) else diameter >= clearDiameterUm(spec)
        if (clear) return RetractState.Clear
        if (zUm != null && zUm.isFinite()) {
            val limitZ = retractLimitZUm(spec)
            val pastLimit = if (spec.leftHand) zUm > limitZ else zUm < limitZ
            if (pastLimit) return RetractState.PastLimit
        }
        return RetractState.Engaged
    }

    /** Safe when clear; past the final depth when `depth(root) − depth(2|x|) < 0`; else cutting. */
    fun phase(spec: ThreadSpec, xUm: Double, retract: RetractState): DepthPhase = when {
        retract == RetractState.Clear -> DepthPhase.Safe
        depthUm(spec, spec.cutToDiameterUm) - depthUm(spec, 2.0 * abs(xUm)) < 0.0 -> DepthPhase.PastFinal
        else -> DepthPhase.Cutting
    }

    /**
     * The chip area `A(h) = tan(α / 2) · h² + w · h` for h > 0, else 0.
     * `h = (depth / 2) / f` is the radial depth in the thread unit, and `w` is the cutter root flat
     * width in the thread unit.
     */
    fun chipArea(spec: ThreadSpec, depthUm: Double): Double {
        val f = spec.form.unit.umPerUnit
        val h = depthUm / 2.0 / f
        val w = ThreadPassSchedule.rootFlatWidthUm(spec.form, spec.cutter, spec.pitchUm) / f
        val tanHalfAngle = tan((spec.form.includedAngleDeg / 2.0) * PI / 180.0)
        return if (h > 0.0) tanHalfAngle * h * h + w * h else 0.0
    }

    /**
     * Gives the live values.
     *
     * @param xUm The absolute X value (the radius from the spindle axis).
     * @param readoutXUm The X readout value before the diameter factor (`c`).
     * @param zUm The Z value, or null.
     * @param hasActiveCorner False when no sub-datum is active.
     * @param xReferenced False when the X axis has no reference.
     * @param activeStart The active 1-based start number.
     */
    fun values(
        spec: ThreadSpec,
        record: ThreadPassRecord,
        xUm: Double,
        readoutXUm: Double,
        zUm: Double?,
        hasActiveCorner: Boolean = true,
        xReferenced: Boolean = true,
        activeStart: Int = 1,
    ): ThreadCutValues {
        val warnings = if (xReferenced) emptySet() else setOf(ThreadCutWarning.XNotReferenced)
        val schedule = ThreadPassSchedule.generate(spec)
        val d = record.deepestUm?.takeIf { it.isFinite() }
        val nextPass = ThreadPassSchedule.nextPass(schedule, d, spec.preOpDiameterUm, spec.internal)
        val totalStarts = spec.starts.coerceAtLeast(1)
        val clampedStart = activeStart.coerceIn(1, totalStarts)
        if (!xUm.isFinite()) {
            return ThreadCutValues(
                lastPassUm = null,
                infeedUm = null,
                cutLoadRatio = null,
                retract = RetractState.Engaged,
                phase = DepthPhase.Cutting,
                status = if (hasActiveCorner) ThreadCutStatus.InTheCut else ThreadCutStatus.SelectFinishCorner,
                clearDiameterUm = clearDiameterUm(spec),
                retractLimitZUm = retractLimitZUm(spec),
                warnings = warnings,
                schedule = schedule,
                nextPass = nextPass,
                activeStart = clampedStart,
                totalStarts = totalStarts,
            )
        }
        val e = record.previousDeepestUm?.takeIf { it.isFinite() }
        val latchDepth = d?.let { depthUm(spec, it) }
        val currentDepth = depthUm(spec, 2.0 * abs(xUm))
        val sign = if (xUm < 0.0) -1.0 else 1.0
        val lastPass = d?.let { readoutXUm - (xUm - sign * it / 2.0) }
        val infeed = latchDepth?.let { (currentDepth - it) / 2.0 }
        val ratio = latchDepth?.let { latch ->
            val lastArea = chipArea(spec, latch) - (e?.let { chipArea(spec, depthUm(spec, it)) } ?: 0.0)
            val nextArea = chipArea(spec, currentDepth) - chipArea(spec, latch)
            if (lastArea > 0.0 && nextArea > 0.0) nextArea / lastArea else null
        }
        val retract = retractState(spec, xUm, zUm)
        val phase = phase(spec, xUm, retract)
        val status = when {
            !hasActiveCorner -> ThreadCutStatus.SelectFinishCorner
            retract == RetractState.PastLimit -> ThreadCutStatus.PastRetractLimit
            phase == DepthPhase.Safe -> ThreadCutStatus.ClearOfCrests
            phase == DepthPhase.PastFinal -> ThreadCutStatus.PastTheRoot
            else -> ThreadCutStatus.InTheCut
        }
        return ThreadCutValues(
            lastPassUm = lastPass,
            infeedUm = infeed,
            cutLoadRatio = ratio,
            retract = retract,
            phase = phase,
            status = status,
            clearDiameterUm = clearDiameterUm(spec),
            retractLimitZUm = retractLimitZUm(spec),
            warnings = warnings,
            schedule = schedule,
            nextPass = nextPass,
            activeStart = clampedStart,
            totalStarts = totalStarts,
        )
    }

    /**
     * Arms a thread: for a new thread id, the pass record and active start reset. The stroke
     * always starts again.
     */
    fun arm(state: ThreadCutState, threadId: String): ThreadCutState {
        val sameThread = state.record.threadId == null || state.record.threadId == threadId
        val record = if (sameThread) state.record else ThreadPassRecord()
        val activeStart = if (sameThread) state.activeStart else 1
        return ThreadCutState(record = record, stroke = ThreadStroke(), activeStart = activeStart)
    }

    /**
     * Selects a new thread start (`1..starts`) and resets the pass record and stroke for that
     * start.
     */
    fun selectStart(state: ThreadCutState, startNumber: Int): ThreadCutState =
        ThreadCutState(
            record = ThreadPassRecord(),
            stroke = ThreadStroke(),
            last = null,
            activeStart = startNumber.coerceAtLeast(1),
        )

    /**
     * Resets the recorded passes and the stroke while keeping the active start.
     */
    fun resetPasses(state: ThreadCutState): ThreadCutState =
        state.copy(record = ThreadPassRecord(), stroke = ThreadStroke(), last = null)

    /**
     * Updates the pass record with one position. A Z outside
     * [Zmin, Zmax] or not finite resets the stroke. A pass counts when Z moves inside the thread
     * length over the band 25 % to 75 %. The record changes only for a larger depth, so a spring
     * pass at the same depth does not change it.
     *
     * The positions come at the display rate, not for each sample. Thus the motion from the last
     * position ([ThreadCutState.last]) to this position is a straight line: when it crosses an
     * end of the thread length, the part of the line inside the length also counts. A fast pass
     * over a short thread with no position inside the length still counts.
     */
    fun update(spec: ThreadSpec, state: ThreadCutState, xUm: Double, zUm: Double?): ThreadCutState {
        val zMin = min(spec.startZUm, spec.stopZUm)
        val zMax = max(spec.startZUm, spec.stopZUm)
        if (zUm == null || !zUm.isFinite()) return state.copy(stroke = ThreadStroke(), last = null)
        val here = ThreadPosition(xUm, zUm)
        val inside = zUm in zMin..zMax
        var stroke = state.stroke
        var record = state.record
        val previous = state.last
        if (previous != null) {
            val previousInside = previous.zUm in zMin..zMax
            val low = max(min(previous.zUm, zUm), zMin)
            val high = min(max(previous.zUm, zUm), zMax)
            if ((!previousInside || !inside) && low <= high) {
                // The line crossed an end of the length. The X of the cut is the X of the position
                // inside the length, or the X at the middle of the length for a line over all of it.
                val x = when {
                    previousInside -> previous.xUm
                    inside -> xUm
                    else -> previous.xUm + (xUm - previous.xUm) * ((zMin + zMax) / 2.0 - previous.zUm) / (zUm - previous.zUm)
                }
                val crossed = latch(spec, stroke.copy(minZUm = min(stroke.minZUm, low), maxZUm = max(stroke.maxZUm, high)), record, x, zMin, zMax)
                stroke = crossed.stroke
                record = crossed.record
            }
        }
        if (!inside) return ThreadCutState(record, ThreadStroke(), here, state.activeStart)
        val next = latch(spec, stroke.copy(minZUm = min(stroke.minZUm, zUm), maxZUm = max(stroke.maxZUm, zUm)), record, xUm, zMin, zMax)
        return ThreadCutState(next.record, next.stroke, here, state.activeStart)
    }

    /** Latches the pass of [stroke] with the X value [xUm] when the stroke covers the band 25 % to 75 %. */
    private fun latch(spec: ThreadSpec, stroke: ThreadStroke, record: ThreadPassRecord, xUm: Double, zMin: Double, zMax: Double): ThreadCutState {
        val length = zMax - zMin
        if (stroke.latched || stroke.minZUm > zMin + 0.25 * length || stroke.maxZUm < zMin + 0.75 * length || !xUm.isFinite()) {
            return ThreadCutState(record, stroke)
        }
        val diameter = 2.0 * abs(xUm)
        val deepest = record.deepestUm?.takeIf { it.isFinite() }
        val next = if (deepest == null || depthUm(spec, diameter) > depthUm(spec, deepest)) {
            ThreadPassRecord(deepestUm = diameter, previousDeepestUm = deepest, threadId = spec.id)
        } else {
            record
        }
        return ThreadCutState(next, stroke.copy(latched = true))
    }
}
