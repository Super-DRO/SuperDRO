package org.superdro.core.functions.measurement

import kotlin.math.abs

/** One sample of the position history of an axis: the time in ms and the position in µm. */
data class HistorySample(val timeMs: Long, val positionUm: Double)

/**
 * The approach sign of one axis at a probe trigger.
 *
 * The rule:
 *
 * 1. The reference is the last sample at or before the trigger time `t`. With no such sample,
 *    the sign is 0.
 * 2. If `t` minus the time of the reference is more than [MAX_GAP_MS], the sign is 0 (the axis
 *    did not move).
 * 3. The function examines the earlier samples from new to old while their time is at or after
 *    `t − `[WINDOW_MS].
 * 4. A gap of more than [MAX_GAP_MS] between two neighbor samples gives 0.
 * 5. The first sample with `|reference − sample| ≥ `[THRESHOLD_UM] gives the sign of
 *    `reference − sample`: +1 or −1.
 * 6. If no sample agrees with step 5, the sign is 0.
 */
object ApproachSign {

    /** The motion that gives a sign, in µm. */
    const val THRESHOLD_UM: Double = 5.0

    /** The time window before the trigger, in ms. */
    const val WINDOW_MS: Long = 1000L

    /** The largest permitted gap between two samples, in ms. */
    const val MAX_GAP_MS: Long = 500L

    /**
     * Gives the approach sign −1, 0, or +1 of an axis at [triggerTimeMs]. [history] holds the
     * samples of the axis in time order.
     */
    fun of(history: List<HistorySample>, triggerTimeMs: Long): Int {
        val referenceIndex = history.indexOfLast { sample -> sample.timeMs <= triggerTimeMs }
        if (referenceIndex < 0) return 0
        val reference = history[referenceIndex]
        if (!reference.positionUm.isFinite()) return 0
        if (triggerTimeMs - reference.timeMs > MAX_GAP_MS) return 0
        var newerTimeMs = reference.timeMs
        for (index in referenceIndex - 1 downTo 0) {
            val sample = history[index]
            if (sample.timeMs < triggerTimeMs - WINDOW_MS) break
            if (newerTimeMs - sample.timeMs > MAX_GAP_MS) return 0
            val difference = reference.positionUm - sample.positionUm
            if (abs(difference) >= THRESHOLD_UM) return if (difference > 0.0) 1 else -1
            newerTimeMs = sample.timeMs
        }
        return 0
    }
}
