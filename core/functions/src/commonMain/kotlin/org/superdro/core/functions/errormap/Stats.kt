package org.superdro.core.functions.errormap

import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * The statistics of the map builder.
 *
 * - The median is the middle value of the sorted list. For an even count, it is the mean of the
 *   two middle values.
 * - `P95 − P5` uses the sorted list `s` of size `n`:
 *   `s[min(n − 1, (int)(n × 0.95))] − s[(int)(n × 0.05)]`.
 */
internal object Stats {

    /** Gives the median of [values], or null for an empty list. */
    fun median(values: Collection<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }

    /** Gives `P95 − P5` of [values] with the index rule above, or null for an empty list. */
    fun p95MinusP5(values: Collection<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val n = sorted.size
        val high = sorted[minOf(n - 1, (n * 0.95).toInt())]
        val low = sorted[(n * 0.05).toInt()]
        return high - low
    }

    /** Gives the mean of [values], or null for an empty list. */
    fun mean(values: Collection<Double>): Double? =
        if (values.isEmpty()) null else values.sum() / values.size

    /** Gives the population standard deviation (divisor n) of [values], or null for an empty list. */
    fun populationSd(values: Collection<Double>): Double? {
        val m = mean(values) ?: return null
        return sqrt(values.sumOf { (it - m) * (it - m) } / values.size)
    }

    /** Gives the sample variance (divisor n − 1) of [values], or null for fewer than 2 values. */
    fun sampleVariance(values: Collection<Double>): Double? {
        if (values.size < 2) return null
        val m = values.sum() / values.size
        return values.sumOf { (it - m) * (it - m) } / (values.size - 1)
    }

    /**
     * Rounds [value] to the nearest whole number. A half value goes up, toward positive
     * infinity (the rule of Java `Math.round`). `roundToLong` of Kotlin has the same rule.
     */
    fun roundHalfUp(value: Double): Long = value.roundToLong()

    /** Gives the bin index `floor(position / width)` of [position]. */
    fun bin(position: Double, width: Double): Long = floor(position / width).toLong()

    /** Gives the least-squares slope of y against x, or 0 when all x are equal. */
    fun slope(xs: List<Double>, ys: List<Double>): Double {
        if (xs.size < 2) return 0.0
        val mx = xs.sum() / xs.size
        val my = ys.sum() / ys.size
        var sxx = 0.0
        var sxy = 0.0
        for (i in xs.indices) {
            val dx = xs[i] - mx
            sxx += dx * dx
            sxy += dx * (ys[i] - my)
        }
        return if (sxx == 0.0) 0.0 else sxy / sxx
    }
}
