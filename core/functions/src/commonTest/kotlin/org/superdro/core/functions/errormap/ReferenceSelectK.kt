package org.superdro.core.functions.errormap

import kotlin.math.max
import kotlin.math.sqrt
import org.superdro.core.model.MapPoint

/**
 * The first versions of [MapBuilderSteps.selectK], [MapBuilderSteps.heldOutResiduals],
 * [MapBuilderSteps.fitNodes], and [MapBuilderSteps.isotonic], without changes. They sort the
 * training samples again for each `K` and each fold. The tests use them as the reference for the
 * fast versions. Do not use them in the app.
 */
internal object ReferenceSelectK {

    fun fitNodes(k: Int, samples: List<MapSample>): List<MapPoint> {
        if (samples.isEmpty() || k < 1) return emptyList()
        val sorted = samples.sortedBy { it.position }
        val positions = ArrayList<Double>()
        val deltas = ArrayList<Double>()
        var i = 0
        while (i < sorted.size) {
            val end = minOf(i + k, sorted.size)
            val group = sorted.subList(i, end)
            if (group.size < max(2, k / 2) && positions.isNotEmpty()) break
            val position = Stats.median(group.map { it.position })!!
            val delta = Stats.median(group.map { it.delta })!!
            if (positions.isEmpty() || position >= positions.last() + 5.0) {
                positions.add(position)
                deltas.add(delta)
            }
            i = end
        }
        val y = DoubleArray(positions.size) { positions[it] + deltas[it] - it }
        val iso = isotonic(y)
        val nodes = ArrayList<MapPoint>()
        for (n in positions.indices) {
            val corrected = iso[n] + n
            val position = Stats.roundHalfUp(positions[n])
            var correction = Stats.roundHalfUp(corrected - positions[n])
            val last = nodes.lastOrNull()
            if (last != null) {
                if (position <= last.position) continue
                if (position + correction <= last.position + last.value) {
                    correction = last.position + last.value + 1 - position
                }
            }
            nodes.add(MapPoint(position, correction))
        }
        return nodes
    }

    fun isotonic(values: DoubleArray): DoubleArray {
        val sums = ArrayList<Double>()
        val counts = ArrayList<Int>()
        for (value in values) {
            sums.add(value)
            counts.add(1)
            while (sums.size >= 2 && sums[sums.size - 2] / counts[counts.size - 2] > sums.last() / counts.last()) {
                val s = sums.removeAt(sums.size - 1)
                val c = counts.removeAt(counts.size - 1)
                sums[sums.size - 1] += s
                counts[counts.size - 1] += c
            }
        }
        val result = DoubleArray(values.size)
        var index = 0
        for (b in sums.indices) {
            val mean = sums[b] / counts[b]
            repeat(counts[b]) { result[index++] = mean }
        }
        return result
    }

    fun selectK(folds: List<List<MapSample>>): Int {
        data class Score(val k: Int, val score: Double, val se: Double)
        val scores = ArrayList<Score>()
        for (k in MapBuilderSteps.K_CANDIDATES) {
            val residualsByFold = heldOutResiduals(k, folds).filter { it.isNotEmpty() }
            val all = residualsByFold.flatten()
            if (all.size < 20 || residualsByFold.size < 2) continue
            val score = Stats.populationSd(all) ?: continue
            val foldSd = residualsByFold.map { Stats.populationSd(it)!! }
            val variance = Stats.sampleVariance(foldSd) ?: 0.0
            scores.add(Score(k, score, sqrt(variance / residualsByFold.size)))
        }
        val total = folds.sumOf { it.size }
        if (scores.isEmpty()) return if (total < 64) 2 else 16
        val best = scores.minBy { it.score }
        return scores.filter { it.score <= best.score + best.se }.maxOf { it.k }
    }

    fun heldOutResiduals(k: Int, folds: List<List<MapSample>>): List<List<Double>> =
        folds.indices.map { f ->
            val training = folds.filterIndexed { index, _ -> index != f }.flatten()
            if (training.size < 2 * k) {
                emptyList()
            } else {
                val nodes = fitNodes(k, training)
                folds[f].map { it.delta - MapBuilderSteps.fitValue(nodes, it.position) }
            }
        }
}
