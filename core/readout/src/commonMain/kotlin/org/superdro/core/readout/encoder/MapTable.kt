package org.superdro.core.readout.encoder

import kotlin.math.floor
import kotlin.math.round
import org.superdro.core.model.MapPoint

/**
 * The lookup table of an error map or a backlash map.
 *
 * The table sorts the points by position (a stable sort) and keeps the first point of each
 * position. The lookup:
 *
 * ```text
 * if x <= P[0]:   C[0]
 * if x >= P[n-1]: C[n-1]
 * else: i = last index with P[i] <= x; frac = (x − P[i]) / (P[i+1] − P[i])
 *       C[i] + rint((C[i+1] − C[i]) × frac)          (rint: half to even)
 * ```
 *
 * The value is flat outside the map. An empty table gives 0 everywhere. SuperDRO calculates the
 * fraction as `Double`.
 */
class MapTable private constructor(
    private val positions: LongArray,
    private val values: LongArray,
) {

    /** The number of points of the table. */
    val size: Int get() = positions.size

    /** True when the table has no point. */
    val isEmpty: Boolean get() = positions.isEmpty()

    /** Gives the map value at the position [x] (counts). */
    fun lookup(x: Double): Long {
        val n = positions.size
        if (n == 0) return 0L
        if (x <= positions[0].toDouble()) return values[0]
        if (x >= positions[n - 1].toDouble()) return values[n - 1]
        var low = 0
        var high = n - 1
        while (high - low > 1) {
            val middle = (low + high) ushr 1
            if (positions[middle].toDouble() <= x) low = middle else high = middle
        }
        val fraction = (x - positions[low].toDouble()) / (positions[high] - positions[low]).toDouble()
        val delta = (values[high] - values[low]).toDouble() * fraction
        return values[low] + round(delta).toLong()
    }

    /** Gives the map value at the position [x] (counts). */
    fun lookup(x: Long): Long = lookup(x.toDouble())

    companion object {
        /** The empty table: the value is 0 everywhere. */
        val EMPTY: MapTable = MapTable(LongArray(0), LongArray(0))

        /**
         * Makes the table of [points]. More than [MapText.MAX_POINTS] points gives [EMPTY]: the
         * input stays without correction.
         */
        fun of(points: List<MapPoint>): MapTable {
            if (points.isEmpty() || points.size > MapText.MAX_POINTS) return EMPTY
            val sorted = points.sortedBy { point -> point.position }
            val kept = ArrayList<MapPoint>(sorted.size)
            sorted.forEach { point -> if (kept.isEmpty() || kept.last().position != point.position) kept.add(point) }
            return MapTable(
                LongArray(kept.size) { index -> kept[index].position },
                LongArray(kept.size) { index -> kept[index].value },
            )
        }
    }
}

/**
 * The error map stage: `E = F + correction(F)`.
 *
 * When [cyclicCounts] is not null (an angular input with the "cyclic" switch on), the lookup
 * position goes modulo one revolution: `x = x − N × floor(x / N)`, so `0 ≤ x < N`.
 *
 * @property cyclicCounts The counts of one revolution `N`, or null for no wrap.
 */
class ErrorCorrection(private val table: MapTable, val cyclicCounts: Double? = null) {

    /** True when the stage changes no count. */
    val isIdentity: Boolean get() = table.isEmpty

    /** Gives the correction at the filtered count [count]. */
    fun correction(count: Long): Long {
        if (table.isEmpty) return 0L
        var x = count.toDouble()
        val n = cyclicCounts
        if (n != null && n > 0.0) x -= n * floor(x / n)
        return table.lookup(x)
    }

    /** Gives the corrected count `count + correction(count)`. */
    fun apply(count: Long): Long = count + correction(count)

    companion object {
        /** The stage with no map. */
        val NONE: ErrorCorrection = ErrorCorrection(MapTable.EMPTY)
    }
}
