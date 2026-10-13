package org.superdro.core.drawing.geometry

import kotlin.math.max
import kotlin.math.sqrt

/**
 * A NURBS curve in the plane. The control points are already in the output coordinates: an
 * affine map does not change a NURBS curve, so the converter maps the control points first.
 *
 * @property weights The weights, or null for a non-rational curve.
 */
internal class Nurbs(
    val degree: Int,
    val knots: DoubleArray,
    val points: List<Vec>,
    val weights: DoubleArray?,
) {
    private val count = points.size

    /** True when the curve has weights that are not all 1. */
    val rational: Boolean = weights != null && weights.any { weight -> weight != 1.0 }

    /** The homogeneous control points (w·x, w·y, w). */
    private val homogeneous: List<DoubleArray> = points.mapIndexed { index, point ->
        val weight = if (rational) weights!![index] else 1.0
        doubleArrayOf(point.x * weight, point.y * weight, weight)
    }

    /** The homogeneous control points of the derivative curve (degree − 1). */
    private val derivativePoints: List<DoubleArray> = (0 until count - 1).map { index ->
        val span = knots[index + degree + 1] - knots[index + 1]
        val factor = if (span == 0.0) 0.0 else degree / span
        val next = homogeneous[index + 1]
        val current = homogeneous[index]
        doubleArrayOf((next[0] - current[0]) * factor, (next[1] - current[1]) * factor, (next[2] - current[2]) * factor)
    }

    /** The knot spans of the domain with a length above 0: the index k of each span [t(k), t(k+1)). */
    fun spans(): List<Int> = (degree until count).filter { span -> knots[span + 1] > knots[span] }

    /** The point and the first derivative at [u], with the polynomial of the knot span [span]. */
    fun evaluate(u: Double, span: Int): Pair<Vec, Vec> {
        val a = deBoor(homogeneous, knots, degree, span, u)
        val point = Vec(a[0] / a[2], a[1] / a[2])
        if (degree == 0) return point to Vec.ZERO
        val innerKnots = knots.copyOfRange(1, knots.size - 1)
        val derivative = deBoor(derivativePoints, innerKnots, degree - 1, span - 1, u)
        val w = a[2]
        val tangent = Vec((derivative[0] - derivative[2] * point.x) / w, (derivative[1] - derivative[2] * point.y) / w)
        return point to tangent
    }

    /**
     * Converts the curve to cubic segments. A non-rational curve of degree 1 to 3 gives exact
     * segments (one for each knot span). Other curves give segments within [toleranceNm]; when a
     * span still has a larger error after [MAX_DEPTH] splits, [Approximation.toleranceMet] is false.
     */
    fun toCubics(toleranceNm: Double, maxSegments: Int): Approximation {
        val exact = !rational && degree <= 3
        val result = ArrayList<Cubic>()
        var toleranceMet = true
        for (span in spans()) {
            val a = knots[span]
            val b = knots[span + 1]
            if (exact) {
                result += hermite(span, a, b)
            } else {
                toleranceMet = approximate(span, a, b, toleranceNm, 0, result, maxSegments) && toleranceMet
            }
            if (result.size > maxSegments) break
        }
        return Approximation(result, toleranceMet)
    }

    private fun hermite(span: Int, a: Double, b: Double): Cubic {
        val (p0, d0) = evaluate(a, span)
        val (p3, d3) = evaluate(b, span)
        val h = (b - a) / 3
        return Cubic(p0, p0 + d0 * h, p3 - d3 * h, p3)
    }

    private fun approximate(
        span: Int,
        a: Double,
        b: Double,
        toleranceNm: Double,
        depth: Int,
        out: MutableList<Cubic>,
        maxSegments: Int,
    ): Boolean {
        val cubic = hermite(span, a, b)
        var worst = 0.0
        for (step in 1 until CHECKS) {
            val s = step.toDouble() / CHECKS
            val expected = evaluate(a + (b - a) * s, span).first
            worst = max(worst, (cubic.at(s) - expected).length)
        }
        if (worst <= toleranceNm || out.size > maxSegments) {
            out += cubic
            return worst <= toleranceNm
        }
        if (depth >= MAX_DEPTH) {
            out += cubic
            return false
        }
        val middle = (a + b) / 2
        val left = approximate(span, a, middle, toleranceNm, depth + 1, out, maxSegments)
        val right = approximate(span, middle, b, toleranceNm, depth + 1, out, maxSegments)
        return left && right
    }

    /** Cubic segments and the tolerance result. */
    class Approximation(val cubics: List<Cubic>, val toleranceMet: Boolean)

    companion object {
        /** The maximum number of splits of one knot span. */
        const val MAX_DEPTH = 12

        /** The number of check intervals of each approximated segment. */
        private const val CHECKS = 8

        /** De Boor's algorithm in homogeneous coordinates for the knot span [span]. */
        private fun deBoor(points: List<DoubleArray>, knots: DoubleArray, degree: Int, span: Int, u: Double): DoubleArray {
            val d = Array(degree + 1) { j -> points[j + span - degree].copyOf() }
            for (r in 1..degree) {
                for (j in degree downTo r) {
                    val left = knots[j + span - degree]
                    val right = knots[j + 1 + span - r]
                    val alpha = if (right == left) 0.0 else (u - left) / (right - left)
                    for (axis in 0..2) d[j][axis] = (1 - alpha) * d[j - 1][axis] + alpha * d[j][axis]
                }
            }
            return d[degree]
        }
    }
}

/**
 * The cubic spline through [points] with the chord-length parameter: the curve of a SPLINE that
 * has only fit points. A start or end tangent gives a clamped end; else the end is natural
 * (second derivative 0). With the chord-length parameter, a unit tangent is the derivative.
 */
internal fun interpolateFitPoints(points: List<Vec>, startTangent: Vec?, endTangent: Vec?): List<Cubic> {
    val unique = ArrayList<Vec>()
    for (point in points) if (unique.isEmpty() || (point - unique.last()).length > 0.0) unique += point
    if (unique.size < 2) return emptyList()
    if (unique.size == 2 && startTangent == null && endTangent == null) return listOf(lineCubic(unique[0], unique[1]))
    val n = unique.size
    val h = DoubleArray(n - 1) { index -> (unique[index + 1] - unique[index]).length }
    // The tridiagonal system for the derivatives D(i): lower, diagonal, upper, right side.
    val lower = DoubleArray(n)
    val diagonal = DoubleArray(n)
    val upper = DoubleArray(n)
    val rightX = DoubleArray(n)
    val rightY = DoubleArray(n)
    fun slope(index: Int) = (unique[index + 1] - unique[index]) * (1.0 / h[index])
    if (startTangent != null) {
        diagonal[0] = 1.0
        val unit = startTangent.unit()
        rightX[0] = unit.x
        rightY[0] = unit.y
    } else {
        diagonal[0] = 2.0
        upper[0] = 1.0
        val s = slope(0) * 3.0
        rightX[0] = s.x
        rightY[0] = s.y
    }
    for (i in 1 until n - 1) {
        lower[i] = h[i]
        diagonal[i] = 2 * (h[i - 1] + h[i])
        upper[i] = h[i - 1]
        val value = slope(i - 1) * (3 * h[i]) + slope(i) * (3 * h[i - 1])
        rightX[i] = value.x
        rightY[i] = value.y
    }
    if (endTangent != null) {
        diagonal[n - 1] = 1.0
        val unit = endTangent.unit()
        rightX[n - 1] = unit.x
        rightY[n - 1] = unit.y
    } else {
        lower[n - 1] = 1.0
        diagonal[n - 1] = 2.0
        val s = slope(n - 2) * 3.0
        rightX[n - 1] = s.x
        rightY[n - 1] = s.y
    }
    val dx = solveTridiagonal(lower, diagonal, upper, rightX)
    val dy = solveTridiagonal(lower, diagonal, upper, rightY)
    return (0 until n - 1).map { index ->
        val p0 = unique[index]
        val p3 = unique[index + 1]
        val step = h[index] / 3
        Cubic(p0, p0 + Vec(dx[index], dy[index]) * step, p3 - Vec(dx[index + 1], dy[index + 1]) * step, p3)
    }
}

private fun Vec.unit(): Vec {
    val size = sqrt(x * x + y * y)
    return if (size == 0.0) this else Vec(x / size, y / size)
}

/** The Thomas algorithm. */
private fun solveTridiagonal(lower: DoubleArray, diagonal: DoubleArray, upper: DoubleArray, right: DoubleArray): DoubleArray {
    val n = diagonal.size
    val c = DoubleArray(n)
    val d = DoubleArray(n)
    c[0] = upper[0] / diagonal[0]
    d[0] = right[0] / diagonal[0]
    for (i in 1 until n) {
        val m = diagonal[i] - lower[i] * c[i - 1]
        c[i] = if (i < n - 1) upper[i] / m else 0.0
        d[i] = (right[i] - lower[i] * d[i - 1]) / m
    }
    val x = DoubleArray(n)
    x[n - 1] = d[n - 1]
    for (i in n - 2 downTo 0) x[i] = d[i] - c[i] * x[i + 1]
    return x
}
