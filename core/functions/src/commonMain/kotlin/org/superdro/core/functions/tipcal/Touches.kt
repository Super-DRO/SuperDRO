package org.superdro.core.functions.tipcal

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * One touch of the wall of the ring gauge.
 *
 * @property xUm The absolute X position at the trigger time stamp.
 * @property yUm The absolute Y position at the trigger time stamp.
 * @property vxUmPerS The X velocity over 1000 ms before the trigger, in µm/s. NaN when not known.
 * @property vyUmPerS The Y velocity over 1000 ms before the trigger, in µm/s. NaN when not known.
 * @property timeMs The time stamp of the trigger record, in ms.
 */
data class Touch(
    val xUm: Double,
    val yUm: Double,
    val vxUmPerS: Double,
    val vyUmPerS: Double,
    val timeMs: Long,
)

/** The sound of an accepted touch. */
enum class TouchBeep {
    /** The third touch: the position has enough touches. */
    LongBeep,

    /** Each other touch. */
    ShortBeep,
}

/** The instruction text of the touch dialog. */
enum class TouchPrompt {
    /**
     * The first dialog (no position has data yet). Text "Lower the stylus into the ring gauge,
     * roughly centered — by eye is fine.\n\nThen touch the wall, backing off between touches."
     */
    FirstPosition,

    /** The other dialogs. Text "Touch the wall, backing off between touches." */
    NextPosition,
}

/** The result of a trigger record at one clock position. */
sealed interface TouchResult {
    /**
     * The touch is refused because X or Y has no position at the trigger time. The app plays the
     * error sound. Text "Could not record that touch — no position at the moment of contact. Try
     * again."
     */
    data object NoPosition : TouchResult

    /** The touch is added. [touches] is the new list. */
    data class Added(val touches: TouchCollection, val beep: TouchBeep) : TouchResult
}

/**
 * The touches of one clock position.
 *
 * The progress text is "%1$d touch positions recorded — %2$d minimum, more is better." with
 * [count] and [MINIMUM_TOUCHES]. The progress bar has the maximum [PROGRESS_MAXIMUM].
 */
data class TouchCollection(val clock: ClockPosition, val touches: List<Touch> = emptyList()) {

    /** The number of touches. */
    val count: Int get() = touches.size

    /** "Continue" is enabled from [MINIMUM_TOUCHES] touches. */
    val canContinue: Boolean get() = count >= MINIMUM_TOUCHES

    /**
     * Adds the touch of one trigger record. A NaN (or not finite) X or Y gives
     * [TouchResult.NoPosition]. The third touch gives a long beep. Each other touch gives a
     * short beep.
     */
    fun add(touch: Touch): TouchResult {
        if (!touch.xUm.isFinite() || !touch.yUm.isFinite()) return TouchResult.NoPosition
        val next = copy(touches = touches + touch)
        val beep = if (next.count == MINIMUM_TOUCHES) TouchBeep.LongBeep else TouchBeep.ShortBeep
        return TouchResult.Added(next, beep)
    }

    companion object {
        /** The minimum number of touches for each clock position. */
        const val MINIMUM_TOUCHES: Int = 3

        /** The maximum of the progress bar. */
        const val PROGRESS_MAXIMUM: Int = 10
    }
}

/**
 * The statistics of one clock position after "Continue".
 *
 * @property xUm The mean X of the touches.
 * @property yUm The mean Y of the touches.
 * @property sigmaXUm The population standard deviation of X.
 * @property sigmaYUm The population standard deviation of Y.
 * @property spreadUm The largest distance between two touches.
 */
data class CapturedPosition(
    val clock: ClockPosition,
    val touches: List<Touch>,
    val xUm: Double,
    val yUm: Double,
    val sigmaXUm: Double,
    val sigmaYUm: Double,
    val spreadUm: Double,
)

/** The result of "Continue" at one clock position. */
sealed interface ContinueResult {
    /** The position has fewer than [TouchCollection.MINIMUM_TOUCHES] touches. "Continue" is not enabled. */
    data object NotEnoughTouches : ContinueResult

    /**
     * The spread is more than the spread limit. Title "Touches Disagree" (a warning). The error
     * kind is [TipCalibrationError.PositionScattered]: "Retry" takes the same position again.
     */
    data class Scattered(val spreadUm: Double, val limitUm: Double) : ContinueResult

    /** The position is accepted. */
    data class Captured(val position: CapturedPosition) : ContinueResult
}

/** The statistics of the touches of one clock position. */
object TouchStatistics {

    /**
     * Gives the spread of [touches]: the largest distance between two touches.
     *
     * The spread is max − min. A position has X and Y, so SuperDRO uses the 2D form of
     * max − min: the largest distance between two touches of the position. A list with fewer than
     * 2 touches gives 0.
     */
    fun spreadUm(touches: List<Touch>): Double {
        var largest = 0.0
        for (i in touches.indices) {
            for (j in i + 1 until touches.size) {
                largest = max(largest, hypot(touches[j].xUm - touches[i].xUm, touches[j].yUm - touches[i].yUm))
            }
        }
        return largest
    }

    /** Gives the population standard deviation of [values]: `sqrt(Σ(v − mean)² / n)`. */
    fun populationSigma(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val mean = values.sum() / values.size
        return sqrt(values.sumOf { value -> (value - mean) * (value - mean) } / values.size)
    }

    /**
     * Calculates the statistics of [collection] when the user taps "Continue".
     *
     * 1. Fewer than 3 touches give [ContinueResult.NotEnoughTouches].
     * 2. A spread ([spreadUm]) that is more than [spreadLimitUm] gives [ContinueResult.Scattered].
     * 3. Else the result has the mean X and Y and the population sigma of X and Y.
     */
    fun capture(collection: TouchCollection, spreadLimitUm: Double): ContinueResult {
        if (!collection.canContinue) return ContinueResult.NotEnoughTouches
        val touches = collection.touches
        val spread = spreadUm(touches)
        if (spread > spreadLimitUm) return ContinueResult.Scattered(spread, spreadLimitUm)
        val xs = touches.map { touch -> touch.xUm }
        val ys = touches.map { touch -> touch.yUm }
        return ContinueResult.Captured(
            CapturedPosition(
                clock = collection.clock,
                touches = touches,
                xUm = xs.sum() / xs.size,
                yUm = ys.sum() / ys.size,
                sigmaXUm = populationSigma(xs),
                sigmaYUm = populationSigma(ys),
                spreadUm = spread,
            ),
        )
    }
}
