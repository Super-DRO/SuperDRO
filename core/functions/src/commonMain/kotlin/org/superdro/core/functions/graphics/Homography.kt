package org.superdro.core.functions.graphics

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The projective transform (homography) from 4 point pairs, as Android
 * `Matrix.setPolyToPoly(src, 0, dst, 0, 4)`. First the solver moves each point set to its
 * centroid and scales it to a mean distance of √2. Thus the singular test does not depend on
 * the units (image pixels and µm).
 */
object Homography {

    /** A pivot below this value in the normalized system makes the system singular. */
    const val SINGULAR_PIVOT: Double = 1e-10

    /**
     * Gives the matrix that maps each point of [source] to the point of [target] with the same
     * index. Gives null when the 4 pairs give a singular matrix.
     */
    fun solve(source: List<Vec2>, target: List<Vec2>): Matrix3? {
        require(source.size == 4 && target.size == 4) { "A homography needs 4 point pairs" }
        if ((source + target).any { point -> !point.x.isFinite() || !point.y.isFinite() }) return null
        val sourceNorm = normalization(source) ?: return null
        val targetNorm = normalization(target) ?: return null
        val src = source.map(sourceNorm::map)
        val dst = target.map(targetNorm::map)
        // The unknowns h0…h7 with h8 = 1. Each pair gives two rows.
        val a = Array(8) { DoubleArray(9) }
        for (index in 0 until 4) {
            val (x, y) = src[index]
            val (u, v) = dst[index]
            val r1 = a[2 * index]
            r1[0] = x; r1[1] = y; r1[2] = 1.0; r1[6] = -x * u; r1[7] = -y * u; r1[8] = u
            val r2 = a[2 * index + 1]
            r2[3] = x; r2[4] = y; r2[5] = 1.0; r2[6] = -x * v; r2[7] = -y * v; r2[8] = v
        }
        val h = gauss(a) ?: return null
        val normalized = Matrix3(h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], 1.0)
        if (abs(normalized.determinant) < SINGULAR_PIVOT) return null
        val targetInverse = targetNorm.inverse() ?: return null
        val result = (targetInverse * normalized * sourceNorm).normalized()
        if (!result.isFinite || result.inverse() == null) return null
        return result
    }

    /** Gives the matrix that moves [points] to their centroid and scales them to a mean distance of √2. */
    private fun normalization(points: List<Vec2>): Matrix3? {
        val cx = points.sumOf { point -> point.x } / points.size
        val cy = points.sumOf { point -> point.y } / points.size
        val mean = points.sumOf { point -> point.distanceTo(Vec2(cx, cy)) } / points.size
        if (!(mean > 0.0) || !mean.isFinite()) return null
        val s = sqrt(2.0) / mean
        return Matrix3(s, 0.0, -s * cx, 0.0, s, -s * cy, 0.0, 0.0, 1.0)
    }

    /**
     * Solves the augmented system [a] (8 × 9) by Gaussian elimination. In each column, the row
     * with the largest value becomes the pivot row. Gives null for a singular system.
     */
    private fun gauss(a: Array<DoubleArray>): DoubleArray? {
        val n = a.size
        for (column in 0 until n) {
            var pivotRow = column
            for (row in column + 1 until n) {
                if (abs(a[row][column]) > abs(a[pivotRow][column])) pivotRow = row
            }
            if (abs(a[pivotRow][column]) < SINGULAR_PIVOT) return null
            val swap = a[column]
            a[column] = a[pivotRow]
            a[pivotRow] = swap
            for (row in column + 1 until n) {
                val factor = a[row][column] / a[column][column]
                if (factor == 0.0) continue
                for (k in column until n + 1) a[row][k] -= factor * a[column][k]
            }
        }
        val x = DoubleArray(n)
        for (row in n - 1 downTo 0) {
            var sum = a[row][n]
            for (k in row + 1 until n) sum -= a[row][k] * x[k]
            x[row] = sum / a[row][row]
        }
        return if (x.all { value -> value.isFinite() }) x else null
    }
}
