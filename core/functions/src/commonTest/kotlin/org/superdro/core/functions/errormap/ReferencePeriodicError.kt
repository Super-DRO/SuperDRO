package org.superdro.core.functions.errormap

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * The first periodic error scan of [ErrorMapBuilder], without changes. It calculates `sin` and
 * `cos` for each sample at each trial frequency. The tests use it as the reference for the fast
 * scan. Do not use it in the app.
 */
internal object ReferencePeriodicError {

    /** The best trial period of one pass. */
    data class Best(val period: Double, val fraction: Double, val amplitude: Double, val phase: Double)

    /**
     * The periodic error of [passes], with the rules of [ErrorMapBuilder.periodicError]. A test
     * can give the [bests] of the passes, if it has them.
     */
    fun periodicError(passes: List<List<MapSample>>, bests: List<Best>? = bests(passes)): PeriodicError? {
        val eligible = passes.filter { it.size >= 32 }
        if (eligible.size < 2) return null
        if (bests == null) return null
        val medianPeriod = Stats.median(bests.map { it.period })!!
        if (bests.any { abs(it.period - medianPeriod) > 0.1 * medianPeriod }) return null
        if (bests.any { it.fraction < 0.1 }) return null
        val meanAmplitude = bests.sumOf { it.amplitude } / bests.size
        if (meanAmplitude < 1.0) return null
        val coherence = hypot(bests.sumOf { cos(it.phase) } / bests.size, bests.sumOf { sin(it.phase) } / bests.size)
        if (coherence < 0.8) return null
        return PeriodicError(medianPeriod, meanAmplitude, bests.sumOf { it.fraction } / bests.size, bests.size)
    }

    /** The best period of each eligible pass, or null when the scan has no grid or a pass has no fit. */
    fun bests(passes: List<List<MapSample>>): List<Best>? {
        val eligible = passes.filter { it.size >= 32 }
        if (eligible.size < 2) return null
        val all = eligible.flatten()
        val unique = all.map { it.position }.distinct().sorted()
        val gaps = (1 until unique.size).map { unique[it] - unique[it - 1] }
        val span = unique.last() - unique.first()
        if (span <= 0.0) return null
        val minPeriod = max(4.0, 2.0 * (Stats.median(gaps) ?: 0.0))
        val maxPeriod = span / 3.0
        if (maxPeriod < minPeriod) return null
        val fMin = 1.0 / maxPeriod
        val fMax = 1.0 / minPeriod
        val nominalStep = 1.0 / (5.0 * span)
        val steps = (floor((fMax - fMin) / nominalStep).toLong() + 1).coerceIn(1, 2000).toInt()
        val step = if (steps > 1) (fMax - fMin) / (steps - 1) else 0.0
        return eligible.map { pass ->
            var best: Best? = null
            for (s in 0 until steps) {
                val f = fMin + s * step
                val fit = sineFit(pass, f) ?: continue
                if (best == null || fit.first > best.fraction) {
                    best = Best(1.0 / f, fit.first, fit.second, fit.third)
                }
            }
            best ?: return null
        }
    }

    /** The fit of one frequency: (variance fraction, amplitude, phase), or null for a singular system. */
    fun sineFit(samples: List<MapSample>, frequency: Double): Triple<Double, Double, Double>? {
        val w = 2.0 * PI * frequency
        var sss = 0.0; var ssc = 0.0; var scc = 0.0; var ss = 0.0; var sc = 0.0
        var sy = 0.0; var sys = 0.0; var syc = 0.0
        val n = samples.size.toDouble()
        for (sample in samples) {
            val s = sin(w * sample.position)
            val c = cos(w * sample.position)
            sss += s * s; ssc += s * c; scc += c * c; ss += s; sc += c
            sy += sample.delta; sys += sample.delta * s; syc += sample.delta * c
        }
        val m = arrayOf(
            doubleArrayOf(sss, ssc, ss, sys),
            doubleArrayOf(ssc, scc, sc, syc),
            doubleArrayOf(ss, sc, n, sy),
        )
        val solution = solve3(m) ?: return null
        val (a, b, c) = Triple(solution[0], solution[1], solution[2])
        val mean = sy / n
        var total = 0.0
        var residual = 0.0
        for (sample in samples) {
            val fit = a * sin(w * sample.position) + b * cos(w * sample.position) + c
            total += (sample.delta - mean) * (sample.delta - mean)
            residual += (sample.delta - fit) * (sample.delta - fit)
        }
        val fraction = if (total <= 0.0) 0.0 else 1.0 - residual / total
        return Triple(fraction, hypot(a, b), atan2(b, a))
    }

    private fun solve3(m: Array<DoubleArray>): DoubleArray? {
        for (col in 0 until 3) {
            var pivot = col
            for (row in col + 1 until 3) if (abs(m[row][col]) > abs(m[pivot][col])) pivot = row
            if (abs(m[pivot][col]) < 1e-12) return null
            val tmp = m[col]; m[col] = m[pivot]; m[pivot] = tmp
            for (row in 0 until 3) {
                if (row == col) continue
                val factor = m[row][col] / m[col][col]
                for (j in col until 4) m[row][j] -= factor * m[col][j]
            }
        }
        return DoubleArray(3) { m[it][3] / m[it][it] }
    }
}
