package org.superdro.core.functions.thread

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan
import org.superdro.core.model.ThreadCutter
import org.superdro.core.model.ThreadForm
import org.superdro.core.model.ThreadInfeedMode
import org.superdro.core.model.ThreadSpec

/** The kind of a scheduled threading pass. */
enum class PassKind {
    Roughing,
    Finish,
    Spring,
}

/**
 * One pass of a single-point thread cutting schedule. All lengths are µm.
 *
 * @property number The 1-based pass number in the schedule.
 * @property kind The pass kind: roughing, finish, or spring.
 * @property stepRadialUm The incremental radial depth of this pass.
 * @property cumulativeRadialUm The cumulative radial depth from the pre-op surface.
 * @property targetDiameterUm The target X diameter at the end of this pass.
 * @property compoundStepUm The incremental advance along the compound slide angle.
 * @property compoundCumulativeUm The cumulative advance along the compound slide angle.
 * @property compoundZShiftUm The axial Z shift caused by the incremental compound advance.
 */
data class ScheduledPass(
    val number: Int,
    val kind: PassKind,
    val stepRadialUm: Double,
    val cumulativeRadialUm: Double,
    val targetDiameterUm: Double,
    val compoundStepUm: Double,
    val compoundCumulativeUm: Double,
    val compoundZShiftUm: Double,
)

/**
 * The complete pass schedule of a thread. All lengths are µm.
 *
 * @property infeedMode The selected compound slide infeed mode.
 * @property infeedAngleDeg The compound slide angle from the cross-slide axis, in degrees.
 * @property totalRadialDepthUm The total radial depth from the pre-op diameter to the cut-to diameter.
 * @property roughingPassCount The number of roughing passes in [passes].
 * @property passes The ordered list of roughing, finish, and spring passes.
 */
data class PassSchedule(
    val infeedMode: ThreadInfeedMode,
    val infeedAngleDeg: Double,
    val totalRadialDepthUm: Double,
    val roughingPassCount: Int,
    val passes: List<ScheduledPass>,
)

/**
 * Calculates the constant chip-area pass schedule and compound slide advances for single-point
 * thread cutting.
 */
object ThreadPassSchedule {

    /** The default radial depth of the finish pass, in µm. */
    const val DEFAULT_FINISH_DEPTH_UM: Double = 35.0

    /** The default number of zero-infeed spring passes. */
    const val DEFAULT_SPRING_PASSES: Int = 1

    /** The smallest radial step of a roughing pass, in µm. */
    const val MIN_STEP_RADIAL_UM: Double = 25.0

    /** The radial tolerance used to match the recorded pass depth against a scheduled pass, in µm. */
    const val PASS_MATCH_TOLERANCE_UM: Double = 5.0

    /** The maximum number of roughing passes. */
    const val MAX_ROUGHING_PASSES: Int = 60

    /**
     * Gives the default number of roughing passes for [pitchUm] and [totalRadialDepthUm].
     */
    fun defaultRoughingPasses(pitchUm: Double, totalRadialDepthUm: Double): Int {
        val byPitch = when {
            pitchUm <= 800.0 -> 5
            pitchUm <= 1500.0 -> 8
            pitchUm <= 2500.0 -> 12
            pitchUm <= 4000.0 -> 16
            else -> 22
        }
        val byDepth = if (totalRadialDepthUm.isFinite() && totalRadialDepthUm > 0.0) {
            ceil(totalRadialDepthUm / 180.0).toInt()
        } else {
            1
        }
        return max(byPitch, byDepth).coerceIn(1, MAX_ROUGHING_PASSES)
    }

    /**
     * Gives the compound slide infeed angle in degrees for [form] and [mode].
     */
    fun infeedAngleDeg(form: ThreadForm, mode: ThreadInfeedMode): Double = when (mode) {
        ThreadInfeedMode.Radial -> 0.0
        ThreadInfeedMode.Flank -> form.includedAngleDeg / 2.0
        ThreadInfeedMode.ModifiedFlank -> (form.includedAngleDeg / 2.0) - 0.5
    }

    /**
     * Gives the cutter tip root flat width `w` in µm for the chip-area model.
     */
    fun rootFlatWidthUm(form: ThreadForm, cutter: ThreadCutter, pitchUm: Double): Double {
        if (!pitchUm.isFinite() || pitchUm <= 0.0) return 0.0
        return when (form) {
            ThreadForm.Acme -> {
                val c = if (pitchUm >= 2540.0) 254.0 else 127.0
                max(0.0, 0.3706912 * pitchUm - 0.5172352 * c)
            }
            ThreadForm.IsoTrapezoidal -> {
                val ac = when {
                    pitchUm <= 1500.0 -> 150.0
                    pitchUm <= 5000.0 -> 250.0
                    else -> 500.0
                }
                max(0.0, 0.3660254 * pitchUm - 0.5358984 * ac)
            }
            else -> if (cutter == ThreadCutter.FullForm) pitchUm / 4.0 else 0.0
        }
    }

    /**
     * Generates the pass schedule for [spec].
     */
    fun generate(
        spec: ThreadSpec,
        finishDepthUm: Double = DEFAULT_FINISH_DEPTH_UM,
        springPasses: Int = DEFAULT_SPRING_PASSES,
    ): PassSchedule = generate(
        form = spec.form,
        cutter = spec.cutter,
        internal = spec.internal,
        pitchUm = spec.pitchUm,
        preOpDiameterUm = spec.preOpDiameterUm,
        cutToDiameterUm = spec.cutToDiameterUm,
        infeedMode = spec.infeedMode,
        roughingPasses = spec.roughingPasses,
        finishDepthUm = finishDepthUm,
        springPasses = springPasses,
    )

    /**
     * Generates the constant chip-area pass schedule.
     */
    fun generate(
        form: ThreadForm,
        cutter: ThreadCutter,
        internal: Boolean,
        pitchUm: Double,
        preOpDiameterUm: Double,
        cutToDiameterUm: Double,
        infeedMode: ThreadInfeedMode = ThreadInfeedMode.ModifiedFlank,
        roughingPasses: Int? = null,
        finishDepthUm: Double = DEFAULT_FINISH_DEPTH_UM,
        springPasses: Int = DEFAULT_SPRING_PASSES,
    ): PassSchedule {
        val angleDeg = infeedAngleDeg(form, infeedMode)
        val totalRadialDepthUm = if (preOpDiameterUm.isFinite() && cutToDiameterUm.isFinite()) {
            abs(cutToDiameterUm - preOpDiameterUm) / 2.0
        } else {
            0.0
        }
        if (totalRadialDepthUm <= 0.0 || !pitchUm.isFinite() || pitchUm <= 0.0) {
            return PassSchedule(
                infeedMode = infeedMode,
                infeedAngleDeg = angleDeg,
                totalRadialDepthUm = 0.0,
                roughingPassCount = 0,
                passes = emptyList(),
            )
        }
        val requestedRough = (roughingPasses ?: defaultRoughingPasses(pitchUm, totalRadialDepthUm))
            .coerceIn(1, MAX_ROUGHING_PASSES)
        val reservedFinishUm = if (totalRadialDepthUm > 50.0) {
            min(max(0.0, finishDepthUm), totalRadialDepthUm * 0.25)
        } else {
            0.0
        }
        val roughTotalUm = max(0.0, totalRadialDepthUm - reservedFinishUm)
        val tanHalfAlpha = tan((form.includedAngleDeg / 2.0) * PI / 180.0)
        val w = rootFlatWidthUm(form, cutter, pitchUm)
        val totalRoughArea = tanHalfAlpha * roughTotalUm * roughTotalUm + w * roughTotalUm

        val betaRad = angleDeg * PI / 180.0
        val cosBeta = cos(betaRad)
        val tanBeta = tan(betaRad)

        val passes = mutableListOf<ScheduledPass>()

        fun makePass(number: Int, kind: PassKind, stepRadial: Double, cumulativeRadial: Double): ScheduledPass {
            val targetDiameter = if (internal) {
                preOpDiameterUm + 2.0 * cumulativeRadial
            } else {
                preOpDiameterUm - 2.0 * cumulativeRadial
            }
            return ScheduledPass(
                number = number,
                kind = kind,
                stepRadialUm = stepRadial,
                cumulativeRadialUm = cumulativeRadial,
                targetDiameterUm = targetDiameter,
                compoundStepUm = stepRadial / cosBeta,
                compoundCumulativeUm = cumulativeRadial / cosBeta,
                compoundZShiftUm = stepRadial * tanBeta,
            )
        }

        var prevCum = 0.0
        if (roughTotalUm > 0.0) {
            for (k in 1..requestedRough) {
                val targetArea = (k.toDouble() / requestedRough.toDouble()) * totalRoughArea
                val idealCum = (-w + sqrt(w * w + 4.0 * tanHalfAlpha * targetArea)) / (2.0 * tanHalfAlpha)
                val idealStep = idealCum - prevCum
                var step = max(MIN_STEP_RADIAL_UM, idealStep)
                val remainingAfterStep = roughTotalUm - (prevCum + step)
                if (k == requestedRough || prevCum + step >= roughTotalUm || (idealStep < MIN_STEP_RADIAL_UM && remainingAfterStep < MIN_STEP_RADIAL_UM)) {
                    step = roughTotalUm - prevCum
                }
                val nextCum = prevCum + step
                passes += makePass(passes.size + 1, PassKind.Roughing, step, nextCum)
                prevCum = nextCum
                if (prevCum >= roughTotalUm - 1e-6) break
            }
        }
        val roughingCount = passes.size

        if (totalRadialDepthUm - prevCum > 1e-6) {
            val finishStep = totalRadialDepthUm - prevCum
            passes += makePass(passes.size + 1, PassKind.Finish, finishStep, totalRadialDepthUm)
            prevCum = totalRadialDepthUm
        }

        val springCount = springPasses.coerceAtLeast(0)
        repeat(springCount) {
            passes += makePass(passes.size + 1, PassKind.Spring, 0.0, totalRadialDepthUm)
        }

        return PassSchedule(
            infeedMode = infeedMode,
            infeedAngleDeg = angleDeg,
            totalRadialDepthUm = totalRadialDepthUm,
            roughingPassCount = roughingCount,
            passes = passes,
        )
    }

    /**
     * Gives the next scheduled pass after [deepestRecordedDiameterUm]. Returns the first roughing
     * or finish pass whose cumulative radial depth exceeds the latched radial depth by more than
     * [PASS_MATCH_TOLERANCE_UM], or the first spring pass when the final depth is reached.
     */
    fun nextPass(
        schedule: PassSchedule,
        deepestRecordedDiameterUm: Double?,
        preOpDiameterUm: Double,
        internal: Boolean,
    ): ScheduledPass? {
        if (schedule.passes.isEmpty()) return null
        if (deepestRecordedDiameterUm == null || !deepestRecordedDiameterUm.isFinite()) {
            return schedule.passes.first()
        }
        val diametralDepth = if (internal) {
            deepestRecordedDiameterUm - preOpDiameterUm
        } else {
            preOpDiameterUm - deepestRecordedDiameterUm
        }
        val latchedRadialUm = max(0.0, diametralDepth / 2.0)
        val nextCuttingPass = schedule.passes.firstOrNull { pass ->
            pass.kind != PassKind.Spring && pass.cumulativeRadialUm > latchedRadialUm + PASS_MATCH_TOLERANCE_UM
        }
        return nextCuttingPass
            ?: schedule.passes.firstOrNull { pass -> pass.kind == PassKind.Spring }
            ?: schedule.passes.lastOrNull()
    }
}
