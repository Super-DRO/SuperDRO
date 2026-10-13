package org.superdro.core.readout.encoder.legacy

import kotlin.math.abs
import org.superdro.core.readout.NumberPattern

/**
 * The 5-sample digital filter of the legacy input. This class is a reference for the comparison.
 * The input stage uses the spike filter.
 *
 * ```text
 * filter(x):
 *   index = (index + 1) mod 5
 *   buf[index] = x
 *   a = buf[(index + 3) mod 5]            (2 steps back)
 *   b = buf[(index + 4) mod 5]            (1 step back)
 *   dev = |x - a|
 *   if b < a - dev or b > x + dev: buf[(index + 4) mod 5] = round((a + x) / 2.0)
 *   oldAvg = avg
 *   avg = trunc(sum(buf) / 5)
 *   delta = oldAvg - x
 *   meanDev = round(sum(|avg - buf[i]|) / 5.0)
 *   if delta > 100: return oldAvg
 *   if delta <= meanDev or delta >= 3 * meanDev: return avg
 *   repeat 3 times: index = (index + 1) mod 5; buf[index] = x
 *   return x
 * ```
 *
 * The ring starts with the first count in all 5 places. [zeroStart] = true starts the ring
 * with zeros. Only a test uses this start.
 */
class LegacyFiveSampleFilter(private val zeroStart: Boolean = false) {

    private val buffer = LongArray(SIZE)
    private var index = 0
    private var average = 0L
    private var started = zeroStart

    /** Gives the filter output of the count [x]. */
    fun filter(x: Long): Long {
        if (!started) {
            started = true
            buffer.fill(x)
            average = x
        }
        index = (index + 1) % SIZE
        buffer[index] = x
        val a = buffer[(index + 3) % SIZE]
        val b = buffer[(index + 4) % SIZE]
        val deviation = abs(x - a)
        if (b < a - deviation || b > x + deviation) {
            buffer[(index + 4) % SIZE] = NumberPattern.roundHalfUp((a + x) / 2.0)
        }
        val oldAverage = average
        average = buffer.sum() / SIZE
        val delta = oldAverage - x
        val meanDeviation = NumberPattern.roundHalfUp(buffer.sumOf { value -> abs(average - value) } / 5.0)
        if (delta > 100L) return oldAverage
        if (delta <= meanDeviation || delta >= 3L * meanDeviation) return average
        repeat(3) {
            index = (index + 1) % SIZE
            buffer[index] = x
        }
        return x
    }

    private companion object {
        const val SIZE = 5
    }
}

/**
 * The legacy input: the scale, the hysteresis `H`, and the optional 5-sample filter. This class
 * is a reference for the comparison. The input stage uses the encoder profile pipeline.
 *
 * ```text
 * d = previous - count
 * previous = count
 * if d < 0 (count went up):   value = (filter(count) + H) * k
 * if d > 0 (count went down): value = filter(count) * k
 * if d == 0:                  keep the last direction; value = (filter(count) + (H if up else 0)) * k
 * ```
 *
 * `previous` starts at 0. A SuperDRO change: a repeated count also goes through the filter, so
 * the output comes to the count at rest. An adapter can send a count only when it changes. Then
 * [onTick] gives the last count to the filter again each [REPEAT_INTERVAL_MS] ms, until the output
 * equals the count.
 *
 * @property k The scale factor (µm or arc seconds for each count).
 * @property hysteresis `H` in counts.
 */
class LegacyInput(
    val k: Double,
    val hysteresis: Long,
    filterEnabled: Boolean,
) {

    private val filter: LegacyFiveSampleFilter? = if (filterEnabled) LegacyFiveSampleFilter() else null
    private var previous = 0L
    private var up = false
    private var lastCount: Long? = null
    private var lastFiltered = 0L
    private var lastTimeMs = 0L

    /** The last value (µm or arc seconds). */
    var value: Double = 0.0
        private set

    /** Processes the count [count] at the time [timeMs] and gives the value. */
    fun process(count: Long, timeMs: Long = lastTimeMs): Double {
        val d = previous - count
        previous = count
        if (d < 0L) up = true
        if (d > 0L) up = false
        lastCount = count
        lastTimeMs = timeMs
        return compute(count)
    }

    /**
     * Gives the last count to the filter again when [REPEAT_INTERVAL_MS] ms passed since the
     * last sample and the filter output is not equal to the count. Gives true when the value
     * changed.
     */
    fun onTick(nowMs: Long): Boolean {
        val count = lastCount ?: return false
        if (filter == null || lastFiltered == count) return false
        if (nowMs - lastTimeMs < REPEAT_INTERVAL_MS) return false
        lastTimeMs = nowMs
        val old = value
        compute(count)
        return value != old
    }

    /** True when the filter output is not equal to the last count. */
    val needsRepeat: Boolean get() = filter != null && lastCount != null && lastFiltered != lastCount

    private fun compute(count: Long): Double {
        lastFiltered = filter?.filter(count) ?: count
        value = (lastFiltered + if (up) hysteresis else 0L) * k
        return value
    }

    companion object {
        /** The repeat time of the last count at rest. */
        const val REPEAT_INTERVAL_MS: Long = 100L
    }
}
