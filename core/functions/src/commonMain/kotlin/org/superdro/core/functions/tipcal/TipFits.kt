package org.superdro.core.functions.tipcal

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * A circle from the mean points of the clock positions.
 * All values are µm.
 *
 * @property radiusUm The radius `R` of the circle of the tip centers.
 * @property residualRmsUm `sqrt(Σ (|P − C| − R)² / N)`.
 */
data class CircleFit(
    val centerXUm: Double,
    val centerYUm: Double,
    val radiusUm: Double,
    val residualRmsUm: Double,
)

/**
 * An ellipse with its axes along X and Y. All values are µm.
 *
 * @property semiAxisXUm The semi axis along X.
 * @property semiAxisYUm The semi axis along Y.
 */
data class EllipseFit(val semiAxisXUm: Double, val semiAxisYUm: Double)

/** The two fits of the tip calibration. */
object TipFits {

    /** The relative limit of the determinant of the circle fit. */
    const val CIRCLE_DET_LIMIT: Double = 1e-12

    /** The pivot limit of the Gauss-Jordan step of the ellipse fit. */
    const val PIVOT_LIMIT: Double = 1e-12

    /**
     * Fits a circle to the points ([xs], [ys]) with the algebraic least squares method about the
     * centroid:
     *
     * ```text
     * xm = mean(x), ym = mean(y); u = x − xm, v = y − ym
     * det = Suu·Svv − Suv²                      fails if |det| <= Suu·Svv·1e-12
     * Bu = (Suuu + Suvv) / 2;  Bv = (Svvv + Svuu) / 2
     * uc = (Bu·Svv − Bv·Suv) / det;  vc = (Bv·Suu − Bu·Suv) / det
     * R = sqrt(uc² + vc² + (Suu + Svv) / N)      fails if R <= 0
     * ```
     *
     * Gives null when the fit fails or when there are fewer than 3 points. Then the UI shows
     * [TipCalibrationError.FitFailed].
     */
    fun fitCircle(xs: List<Double>, ys: List<Double>): CircleFit? {
        require(xs.size == ys.size) { "The point lists must have the same size." }
        val n = xs.size
        if (n < 3) return null
        val xm = xs.sum() / n
        val ym = ys.sum() / n
        var suu = 0.0
        var svv = 0.0
        var suv = 0.0
        var suuu = 0.0
        var svvv = 0.0
        var suvv = 0.0
        var svuu = 0.0
        for (i in 0 until n) {
            val u = xs[i] - xm
            val v = ys[i] - ym
            suu += u * u
            svv += v * v
            suv += u * v
            suuu += u * u * u
            svvv += v * v * v
            suvv += u * v * v
            svuu += u * u * v
        }
        val det = suu * svv - suv * suv
        if (!(abs(det) > suu * svv * CIRCLE_DET_LIMIT)) return null
        val bu = (suuu + suvv) / 2.0
        val bv = (svvv + svuu) / 2.0
        val uc = (bu * svv - bv * suv) / det
        val vc = (bv * suu - bu * suv) / det
        val radius = sqrt(uc * uc + vc * vc + (suu + svv) / n)
        if (!(radius > 0.0)) return null
        val cx = xm + uc
        val cy = ym + vc
        var sumSquares = 0.0
        for (i in 0 until n) {
            val d = hypot(xs[i] - cx, ys[i] - cy) - radius
            sumSquares += d * d
        }
        return CircleFit(cx, cy, radius, sqrt(sumSquares / n))
    }

    /**
     * Fits an ellipse with its axes along X and Y to exactly 4 points:
     *
     * ```text
     * s = sqrt(Σ ((x − xm)² + (y − ym)²) / 4)                fails if s <= 0
     * u = (x − xm) / s, v = (y − ym) / s
     * solve A·u² + B·v² + C·u + D·v = 1 for the 4 points       Gauss-Jordan, partial pivot
     * fails if A <= 0 or B <= 0
     * u0 = −C / (2A), v0 = −D / (2B), K = A·u0² + B·v0² + 1    fails if K <= 0
     * semiAxisX = sqrt(K / A)·s; semiAxisY = sqrt(K / B)·s
     * ```
     *
     * Gives null when the fit fails or when the number of points is not 4.
     */
    fun fitEllipse(xs: List<Double>, ys: List<Double>): EllipseFit? {
        require(xs.size == ys.size) { "The point lists must have the same size." }
        if (xs.size != 4) return null
        val xm = xs.sum() / 4.0
        val ym = ys.sum() / 4.0
        var sumSquares = 0.0
        for (i in 0 until 4) {
            sumSquares += (xs[i] - xm) * (xs[i] - xm) + (ys[i] - ym) * (ys[i] - ym)
        }
        val s = sqrt(sumSquares / 4.0)
        if (!(s > 0.0)) return null
        val matrix = Array(4) { i ->
            val u = (xs[i] - xm) / s
            val v = (ys[i] - ym) / s
            doubleArrayOf(u * u, v * v, u, v, 1.0)
        }
        val solution = solveGaussJordan(matrix) ?: return null
        val a = solution[0]
        val b = solution[1]
        val c = solution[2]
        val d = solution[3]
        if (!(a > 0.0) || !(b > 0.0)) return null
        val u0 = -c / (2.0 * a)
        val v0 = -d / (2.0 * b)
        val k = a * u0 * u0 + b * v0 * v0 + 1.0
        if (!(k > 0.0)) return null
        return EllipseFit(semiAxisXUm = sqrt(k / a) * s, semiAxisYUm = sqrt(k / b) * s)
    }

    /**
     * Solves the augmented system [augmented] (each row: the coefficients, then the right side)
     * with Gauss-Jordan elimination and partial pivot. Gives null when the largest pivot of a
     * column is less than [PIVOT_LIMIT] in absolute value.
     */
    internal fun solveGaussJordan(augmented: Array<DoubleArray>): DoubleArray? {
        val n = augmented.size
        val rows = Array(n) { i -> augmented[i].copyOf() }
        for (column in 0 until n) {
            var pivotRow = column
            for (row in column + 1 until n) {
                if (abs(rows[row][column]) > abs(rows[pivotRow][column])) pivotRow = row
            }
            if (!(abs(rows[pivotRow][column]) >= PIVOT_LIMIT)) return null
            if (pivotRow != column) {
                val swap = rows[pivotRow]
                rows[pivotRow] = rows[column]
                rows[column] = swap
            }
            val pivot = rows[column][column]
            for (j in column..n) rows[column][j] /= pivot
            for (row in 0 until n) {
                if (row == column) continue
                val factor = rows[row][column]
                if (factor == 0.0) continue
                for (j in column..n) rows[row][j] -= factor * rows[column][j]
            }
        }
        return DoubleArray(n) { i -> rows[i][n] }
    }
}
