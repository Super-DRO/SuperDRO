package org.superdro.core.functions.thread

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import org.superdro.core.model.ThreadCutter
import org.superdro.core.model.ThreadSpec

/** The retract state of the thread cut panel. */
enum class RetractState {
    /** The tool is out of the crests by the clearance or more. */
    Clear,

    /** The tool is in the thread. */
    Engaged,

    /**
     * The tool is not clear and Z is past the retract limit: the end of the relief W₁ (the
     * shoulder or the bottom) at `Zmin − W₁`, or `Zmin` with no W₁.
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
 *   reference. Set X to zero on the spindle axis before you cut the thread." No screen shows
 *   the warning now.
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

/** The state of the pass recorder: the record and the stroke. */
data class ThreadCutState(
    val record: ThreadPassRecord = ThreadPassRecord(),
    val stroke: ThreadStroke = ThreadStroke(),
)

/**
 * The live values of the thread cut panel, in µm.
 *
 * @property lastPassUm "Last pass": the X readout value at the deepest recorded pass, or null ("—").
 * @property infeedUm "Infeed": the radial depth of the tool beyond that pass, or null ("—").
 * @property cutLoadRatio "Cut load ≈ %1$s×" with one decimal, or null ("—").
 * @property clearDiameterUm The X diameter at which the tool is clear of the crests (the clearance
 *   limit): `MinorDiameter − clr` (internal) or `MajorDiameter + clr` (external).
 * @property retractLimitZUm The Z retract limit: `Zmin − W₁`.
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
)

/**
 * The thread cut panel math. The panel has no pass schedule.
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

    /** Gives the Z retract limit `Zmin − W₁` (W₁ = 0 with no relief). */
    fun retractLimitZUm(spec: ThreadSpec): Double = min(spec.startZUm, spec.stopZUm) - (spec.leftReliefUm ?: 0.0)

    /**
     * Gives the retract state. Internal: clear when `2|x| ≤ MinorDiameter − clr`. External: clear
     * when `2|x| ≥ MajorDiameter + clr`. Else past the limit when [zUm] is below the Z retract
     * limit, else engaged.
     */
    fun retractState(spec: ThreadSpec, xUm: Double, zUm: Double?): RetractState {
        val diameter = 2.0 * abs(xUm)
        val clear = if (spec.internal) diameter <= clearDiameterUm(spec) else diameter >= clearDiameterUm(spec)
        return when {
            clear -> RetractState.Clear
            zUm != null && zUm.isFinite() && zUm < retractLimitZUm(spec) -> RetractState.PastLimit
            else -> RetractState.Engaged
        }
    }

    /** Safe when clear; past the final depth when `depth(root) − depth(2|x|) < 0`; else cutting. */
    fun phase(spec: ThreadSpec, xUm: Double, retract: RetractState): DepthPhase = when {
        retract == RetractState.Clear -> DepthPhase.Safe
        depthUm(spec, spec.cutToDiameterUm) - depthUm(spec, 2.0 * abs(xUm)) < 0.0 -> DepthPhase.PastFinal
        else -> DepthPhase.Cutting
    }

    /**
     * The chip area `A(h) = tan 30° · h² + w · h` for h > 0, else 0.
     * `h = (depth / 2) / f` is the radial depth in the thread unit, and `w = P / 4` for a Full
     * Form cutter, else 0.
     */
    fun chipArea(spec: ThreadSpec, depthUm: Double): Double {
        val f = spec.form.unit.umPerUnit
        val h = depthUm / 2.0 / f
        val w = if (spec.cutter == ThreadCutter.FullForm) spec.pitchUm / f / 4.0 else 0.0
        return if (h > 0.0) TAN_30 * h * h + w * h else 0.0
    }

    /**
     * Gives the live values.
     *
     * @param xUm The absolute X value (the radius from the spindle axis).
     * @param readoutXUm The X readout value before the diameter factor (`c`).
     * @param zUm The Z value, or null.
     * @param hasActiveCorner False when no sub-datum is active.
     * @param xReferenced False when the X axis has no reference.
     */
    fun values(
        spec: ThreadSpec,
        record: ThreadPassRecord,
        xUm: Double,
        readoutXUm: Double,
        zUm: Double?,
        hasActiveCorner: Boolean = true,
        xReferenced: Boolean = true,
    ): ThreadCutValues {
        val warnings = if (xReferenced) emptySet() else setOf(ThreadCutWarning.XNotReferenced)
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
            )
        }
        val d = record.deepestUm?.takeIf { it.isFinite() }
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
        )
    }

    /**
     * Arms a thread: for a new thread id, the pass record
     * starts again. The stroke always starts again.
     */
    fun arm(state: ThreadCutState, threadId: String): ThreadCutState {
        val record = if (state.record.threadId != null && state.record.threadId != threadId) ThreadPassRecord() else state.record
        return ThreadCutState(record = record, stroke = ThreadStroke())
    }

    /**
     * Updates the pass record with one position. A Z outside
     * [Zmin, Zmax] or not finite resets the stroke. A pass counts when Z moves inside the thread
     * length over the band 25 % to 75 %. The record changes only for a larger depth, so a spring
     * pass at the same depth does not change it.
     */
    fun update(spec: ThreadSpec, state: ThreadCutState, xUm: Double, zUm: Double?): ThreadCutState {
        val zMin = min(spec.startZUm, spec.stopZUm)
        val zMax = max(spec.startZUm, spec.stopZUm)
        val length = zMax - zMin
        if (zUm == null || !zUm.isFinite() || zUm < zMin || zUm > zMax) {
            return state.copy(stroke = ThreadStroke())
        }
        var stroke = state.stroke.copy(minZUm = min(state.stroke.minZUm, zUm), maxZUm = max(state.stroke.maxZUm, zUm))
        var record = state.record
        if (!stroke.latched && stroke.minZUm <= zMin + 0.25 * length && stroke.maxZUm >= zMin + 0.75 * length && xUm.isFinite()) {
            stroke = stroke.copy(latched = true)
            val diameter = 2.0 * abs(xUm)
            val deepest = record.deepestUm?.takeIf { it.isFinite() }
            if (deepest == null || depthUm(spec, diameter) > depthUm(spec, deepest)) {
                record = ThreadPassRecord(deepestUm = diameter, previousDeepestUm = deepest, threadId = spec.id)
            }
        }
        return ThreadCutState(record, stroke)
    }
}
