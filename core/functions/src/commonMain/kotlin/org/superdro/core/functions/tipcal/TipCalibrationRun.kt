package org.superdro.core.functions.tipcal

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The action of "Retry" in the error dialog. The dialog
 * also has "Cancel".
 */
enum class RetryAction {
    /** Ask for the ring gauge size again. */
    AskGaugeAgain,

    /** Take the same clock position again. */
    SamePositionAgain,

    /** Clear all positions, keep the gauge, and start again at 9 o'clock. */
    ClearPositionsKeepGauge,
}

/** The error kinds of the tip calibration and the action of "Retry". */
enum class TipCalibrationError(val retry: RetryAction) {
    /** The ring gauge is not usable ("Ring Gauge Too Small", [TipCalibrationSetup.isGaugeUsable]). */
    GaugeUnusable(RetryAction.AskGaugeAgain),

    /** The touches of a position disagree ("Touches Disagree", [ContinueResult.Scattered]). */
    PositionScattered(RetryAction.SamePositionAgain),

    /**
     * The circle fit fails. Title "Cannot Fit a Circle". Text "These four touches do not describe
     * a circle. Take them again, one at each clock position."
     */
    FitFailed(RetryAction.ClearPositionsKeepGauge),

    /** The result check refuses the result ([ResultCheckKind.TipHasNoSize] or [ResultCheckKind.UnlikelyTipSize]). */
    ResultRefused(RetryAction.ClearPositionsKeepGauge),
}

/**
 * The note for a probe with different X and Y diameters.
 * The text is "Across X: … Across Y: … Common on inexpensive probes — the trip force differs by
 * direction. The average is saved, so a pickup can read up to [halfDifferenceUm] out on one axis."
 */
data class AsymmetryNote(val diameterXUm: Double, val diameterYUm: Double) {
    /** `|diameterX − diameterY| / 2`. */
    val halfDifferenceUm: Double get() = abs(diameterXUm - diameterYUm) / 2.0
}

/**
 * The result of the tip calibration. All lengths
 * are µm.
 *
 * @property gaugeUm The ring gauge diameter `G`.
 * @property radiusUm The radius `R` of the circle of the tip centers.
 * @property residualUm The RMS residual of the circle fit.
 * @property repeatabilityUm `sqrt(Σ (sigmaX² + sigmaY²) / 4)` over the four positions.
 * @property diameterXUm The tip diameter across X from the ellipse fit, or null.
 * @property diameterYUm The tip diameter across Y from the ellipse fit, or null.
 * @property touchCount The sum of the touches of the four positions.
 */
data class TipCalibrationResult(
    val gaugeUm: Double,
    val centerXUm: Double,
    val centerYUm: Double,
    val radiusUm: Double,
    val residualUm: Double,
    val repeatabilityUm: Double,
    val diameterXUm: Double?,
    val diameterYUm: Double?,
    val touchCount: Int,
) {
    /** The effective tip diameter `d = G − 2R`. */
    val effectiveDiameterUm: Double get() = gaugeUm - 2.0 * radiusUm

    /** The effective tip radius `G / 2 − R`. */
    val effectiveRadiusUm: Double get() = gaugeUm / 2.0 - radiusUm

    /**
     * The asymmetry note, when both per-axis diameters exist and
     * `|diameterX − diameterY| > max(10 µm, 3 × repeatability)`. Else null.
     */
    val asymmetry: AsymmetryNote?
        get() {
            val dx = diameterXUm ?: return null
            val dy = diameterYUm ?: return null
            val limit = max(ASYMMETRY_FLOOR_UM, ASYMMETRY_FACTOR * repeatabilityUm)
            return if (abs(dx - dy) > limit) AsymmetryNote(dx, dy) else null
        }

    companion object {
        /** The lowest limit of the asymmetry note: 10 µm. */
        const val ASYMMETRY_FLOOR_UM: Double = 10.0

        /** The factor of the repeatability in the asymmetry limit: 3. */
        const val ASYMMETRY_FACTOR: Double = 3.0
    }
}

/** The kind of the result dialog. */
enum class ResultCheckKind(val canApply: Boolean) {
    /**
     * `G/2 − R <= 0`. Title "Tip Has No Size". Text "The touches describe `2R`, no smaller than
     * the `G` gauge — that leaves the tip no size…"
     */
    TipHasNoSize(canApply = false),

    /**
     * `d < 100 µm` or `d > 25400 µm`. Title "Unlikely Tip Size". Text "That works out to a `d`
     * tip, which is not a stylus size. Check the ring gauge diameter."
     */
    UnlikelyTipSize(canApply = false),

    /**
     * The nominal diameter is more than 0 and `|d − nominal| / nominal > 0.25`. Title
     * "Calibration Result" (a warning). Text "Measured `d`, but this probe is entered as
     * `nominal`…"
     */
    NominalMismatch(canApply = true),

    /**
     * All other cases. Title "Calibration Result" (a question). Text "Tip diameter: `d`\n
     * Repeatability: ±`rep`\n\nFrom `n` touches in a `G` ring gauge."
     */
    Normal(canApply = true),
}

/** The result checks of the tip calibration. */
object TipResultChecks {

    /** The smallest tip diameter that the check accepts: 100 µm. */
    const val MIN_TIP_UM: Double = 100.0

    /** The largest tip diameter that the check accepts: 25400 µm. */
    const val MAX_TIP_UM: Double = 25400.0

    /** The largest relative difference from the nominal diameter with no warning: 0.25. */
    const val NOMINAL_TOLERANCE: Double = 0.25

    /** Checks [result] in the order of [ResultCheckKind]. [nominalUm] is the nominal tip diameter. */
    fun check(result: TipCalibrationResult, nominalUm: Double): ResultCheckKind {
        val d = result.effectiveDiameterUm
        return when {
            !(result.effectiveRadiusUm > 0.0) -> ResultCheckKind.TipHasNoSize
            d < MIN_TIP_UM || d > MAX_TIP_UM -> ResultCheckKind.UnlikelyTipSize
            nominalUm > 0.0 && abs(d - nominalUm) / nominalUm > NOMINAL_TOLERANCE -> ResultCheckKind.NominalMismatch
            else -> ResultCheckKind.Normal
        }
    }
}

/** The result of a step of [TipCalibrationRun]. */
sealed interface TipCalibrationStep {
    /** The step is accepted. [run] is the new state. */
    data class Accepted(val run: TipCalibrationRun) : TipCalibrationStep

    /** The step fails. The UI shows the error dialog of [error]. */
    data class Failed(val error: TipCalibrationError) : TipCalibrationStep
}

/** The result of [TipCalibrationRun.evaluate]. */
sealed interface TipCalibrationOutcome {
    /** The run does not have the four positions yet. */
    data object NotComplete : TipCalibrationOutcome

    /** The circle fit fails ([TipCalibrationError.FitFailed]). */
    data object FitFailed : TipCalibrationOutcome

    /** The check refuses the result ([TipCalibrationError.ResultRefused]). "Apply" is not available. */
    data class Refused(val check: ResultCheckKind, val result: TipCalibrationResult) : TipCalibrationOutcome

    /** The result can be applied. [check] is [ResultCheckKind.NominalMismatch] or [ResultCheckKind.Normal]. */
    data class Ready(val check: ResultCheckKind, val result: TipCalibrationResult) : TipCalibrationOutcome
}

/**
 * The state of one tip calibration run. The type
 * is immutable. Each step gives a new state.
 *
 * @property profileId The id of the probe profile.
 * @property started The start time, `yyyy-MM-dd HH:mm:ss` (the caller makes the text).
 * @property gaugeUm The ring gauge diameter in µm, or null before the gauge entry.
 * @property positions The captured positions, at most one for each clock position.
 */
data class TipCalibrationRun(
    val profileId: String,
    val started: String,
    val gaugeUm: Double? = null,
    val positions: List<CapturedPosition> = emptyList(),
) {

    /** The next clock position: the first position of the procedure order with no data, or null. */
    val nextPosition: ClockPosition?
        get() = ClockPosition.procedureOrder.firstOrNull { clock -> positionAt(clock) == null }

    /** True when all four positions have data. */
    val hasAllPositions: Boolean get() = nextPosition == null

    /** True when the "Back to the Middle" step comes now: only before 12 o'clock. */
    val needsRecenter: Boolean get() = nextPosition == ClockPosition.Twelve

    /** The recenter marker from 9 o'clock and 3 o'clock, or null when one of them has no data. */
    val recenterMarker: RecenterMarker?
        get() {
            val nine = positionAt(ClockPosition.Nine) ?: return null
            val three = positionAt(ClockPosition.Three) ?: return null
            return RecenterMarker.of(nine.xUm, three.xUm)
        }

    /** The instruction text of the touch dialog: the first dialog has its own text. */
    val prompt: TouchPrompt get() = if (positions.isEmpty()) TouchPrompt.FirstPosition else TouchPrompt.NextPosition

    /** The sum of the touches of all captured positions. */
    val touchCount: Int get() = positions.sumOf { position -> position.touches.size }

    /** Gives the captured data of [clock], or null. */
    fun positionAt(clock: ClockPosition): CapturedPosition? = positions.firstOrNull { position -> position.clock == clock }

    /**
     * Sets the ring gauge. [gaugeUm] must be usable for the nominal tip diameter [nominalTipUm].
     * Else the step fails with [TipCalibrationError.GaugeUnusable].
     */
    fun withGauge(gaugeUm: Double, nominalTipUm: Double): TipCalibrationStep =
        if (TipCalibrationSetup.isGaugeUsable(gaugeUm, nominalTipUm)) {
            TipCalibrationStep.Accepted(copy(gaugeUm = gaugeUm))
        } else {
            TipCalibrationStep.Failed(TipCalibrationError.GaugeUnusable)
        }

    /**
     * Applies "Continue" to the touches of one position. A scattered position fails with
     * [TipCalibrationError.PositionScattered]. An accepted position replaces the old data of
     * the same clock position. Fewer than 3 touches give no change.
     */
    fun continueWith(collection: TouchCollection, spreadLimitUm: Double): TipCalibrationStep =
        when (val result = TouchStatistics.capture(collection, spreadLimitUm)) {
            ContinueResult.NotEnoughTouches -> TipCalibrationStep.Accepted(this)
            is ContinueResult.Scattered -> TipCalibrationStep.Failed(TipCalibrationError.PositionScattered)
            is ContinueResult.Captured -> TipCalibrationStep.Accepted(withPosition(result.position))
        }

    /** Gives a copy with [position] in place of the old data of its clock position. */
    fun withPosition(position: CapturedPosition): TipCalibrationRun =
        copy(positions = positions.filter { old -> old.clock != position.clock } + position)

    /** Gives the state after "Retry" of [error]. */
    fun retry(error: TipCalibrationError): TipCalibrationRun = when (error.retry) {
        RetryAction.AskGaugeAgain -> copy(gaugeUm = null)
        RetryAction.SamePositionAgain -> this
        RetryAction.ClearPositionsKeepGauge -> copy(positions = emptyList())
    }

    /**
     * Fits the circle and the ellipse to the mean points of the four positions and checks the
     * result. [nominalTipUm] is the nominal tip diameter of the profile.
     */
    fun evaluate(nominalTipUm: Double): TipCalibrationOutcome {
        val gauge = gaugeUm
        if (gauge == null || !hasAllPositions) return TipCalibrationOutcome.NotComplete
        val ordered = ClockPosition.procedureOrder.mapNotNull { clock -> positionAt(clock) }
        val xs = ordered.map { position -> position.xUm }
        val ys = ordered.map { position -> position.yUm }
        val circle = TipFits.fitCircle(xs, ys) ?: return TipCalibrationOutcome.FitFailed
        val ellipse = TipFits.fitEllipse(xs, ys)
        val diameterX = ellipse?.let { fit -> gauge - 2.0 * fit.semiAxisXUm }
        val diameterY = ellipse?.let { fit -> gauge - 2.0 * fit.semiAxisYUm }
        val perAxis = diameterX != null && diameterY != null && diameterX > 0.0 && diameterY > 0.0
        val result = TipCalibrationResult(
            gaugeUm = gauge,
            centerXUm = circle.centerXUm,
            centerYUm = circle.centerYUm,
            radiusUm = circle.radiusUm,
            residualUm = circle.residualRmsUm,
            repeatabilityUm = repeatabilityUm(ordered),
            diameterXUm = if (perAxis) diameterX else null,
            diameterYUm = if (perAxis) diameterY else null,
            touchCount = ordered.sumOf { position -> position.touches.size },
        )
        val check = TipResultChecks.check(result, nominalTipUm)
        return if (check.canApply) {
            TipCalibrationOutcome.Ready(check, result)
        } else {
            TipCalibrationOutcome.Refused(check, result)
        }
    }

    companion object {
        /**
         * Gives the repeatability `sqrt(Σ (sigmaX² + sigmaY²) / 4)`. The sum is over the
         * positions. A complete run has four positions, thus the divisor is the number of
         * positions.
         */
        fun repeatabilityUm(positions: List<CapturedPosition>): Double {
            if (positions.isEmpty()) return 0.0
            val sum = positions.sumOf { position ->
                position.sigmaXUm * position.sigmaXUm + position.sigmaYUm * position.sigmaYUm
            }
            return sqrt(sum / positions.size)
        }
    }
}
