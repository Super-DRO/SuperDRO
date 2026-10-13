package org.superdro.core.functions.measurement

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt
import org.superdro.core.functions.PointUm
import org.superdro.core.model.CollectedPoint

/**
 * The reason why a measurement function gives no result. SuperDRO gives a separate code for each
 * case. The KDoc of each value gives the
 * English text. The texts marked "SuperDRO text" are new in SuperDRO.
 */
enum class MeasurementFailure {
    /** "Points are in a line — replace one" (`|D| / L < tol`, or `L ≤ 0`). */
    Collinear,

    /**
     * SuperDRO text: "No circle agrees with the probe directions — touch off again". The tip fit has
     * no candidate that agrees with the approach signs.
     */
    TipFitNoCandidate,

    /**
     * SuperDRO text: "Two circles agree with the probe directions — touch off again from a different
     * direction". The tip fit has two or more candidates that agree with the approach signs.
     */
    TipFitTwoCandidates,

    /** "Points are too close — replace one" (`L < tol` of a line). */
    PointsTooClose,

    /**
     * SuperDRO text: "The tip radii are too different for these points — replace one". The two tip
     * circles have no common tangent (`|k| > 1`).
     */
    TangentImpossible,

    /**
     * SuperDRO text: "No side of the line agrees with the probe directions — touch off again". No
     * normal of the common tangent agrees with the approach signs.
     */
    LineTipNoNormal,

    /**
     * SuperDRO text: "Both sides of the line agree with the probe directions — touch off again". The
     * two normals of the common tangent agree with the approach signs.
     */
    LineTipTwoNormals,
}

/**
 * A fitted circle in µm.
 *
 * @property side 0 for a fit with no tip radius, −1 for a bore (the tip is inside the circle),
 *   +1 for a boss (the tip is outside the circle).
 */
data class FittedCircle(val centerXUm: Double, val centerYUm: Double, val radiusUm: Double, val side: Int = 0) {

    /** The diameter `2R` in µm. */
    val diameterUm: Double get() = 2.0 * radiusUm
}

/** The result of the circle fit. */
sealed interface CircleFit {

    /** The fit gives [circle]. */
    data class Fitted(val circle: FittedCircle) : CircleFit

    /** The fit fails for [failure]. */
    data class Failed(val failure: MeasurementFailure) : CircleFit
}

/**
 * A fitted line from the start to the end, in µm. The ends are surface points: the tip centers
 * minus the tip radius along the normal.
 */
data class FittedLine(val startXUm: Double, val startYUm: Double, val endXUm: Double, val endYUm: Double) {

    /** end − start on X. */
    val dxUm: Double get() = endXUm - startXUm

    /** end − start on Y. */
    val dyUm: Double get() = endYUm - startYUm

    /** The length in µm. */
    val lengthUm: Double get() = hypot(dxUm, dyUm)

    /** The angle to X in degrees, in (−90°, 90°]. */
    val angleToX: Double get() = MeasurementMath.angleToX(dxUm, dyUm)

    /** The angle to Y in degrees, in (−90°, 90°]. */
    val angleToY: Double get() = MeasurementMath.angleToY(angleToX)
}

/** The result of the line fit. */
sealed interface LineFit {

    /** The fit gives [line]. */
    data class Fitted(val line: FittedLine) : LineFit

    /** The fit fails for [failure]. */
    data class Failed(val failure: MeasurementFailure) : LineFit
}

/**
 * The math of the measurement functions. Lengths are µm, angles degrees.
 */
object MeasurementMath {

    /** Below this value of `|cross| / (|line 1| × |line 2|)`, two lines are parallel. */
    const val PARALLEL_LIMIT: Double = 1e-4

    /**
     * The corner add button is enabled when the largest distance from the corner to the four
     * points is not more than this factor × the longer line length.
     */
    const val CORNER_DISTANCE_FACTOR: Double = 10.0

    /** Below this `|a|`, the quadratic of the tip fit is linear. */
    const val LINEAR_LIMIT: Double = 1e-12

    /**
     * Fits a circle through three points.
     *
     * Step 1: `D = (x2 − x1)(y3 − y1) − (x3 − x1)(y2 − y1)` and `L` = the longest side. `L ≤ 0` or
     * `|D| / L < tol` gives [MeasurementFailure.Collinear]. With no tip radius, the plain circle
     * through the three points is the result.
     *
     * Step 2: with tip radii, the fit solves `|Pi − C| = R + s·ri` for each side `s` (−1 bore, +1
     * boss): Cramer's rule gives `C = k + m·R`, then a quadratic in `R`. A root is kept only if
     * `R > 0` and `R + s·ri > 0` for all three points.
     *
     * Step 3: a candidate is valid when each point with a tip radius and an approach agrees:
     * `dot = approach · (Pi − C)`; `dot > 0` needs the side −1, `dot < 0` needs +1, `dot = 0` is
     * not examined. At least one point must be examined. Exactly one valid candidate gives the
     * result. No valid candidate gives [MeasurementFailure.TipFitNoCandidate]. Two or more give
     * [MeasurementFailure.TipFitTwoCandidates].
     */
    fun circleFrom3Points(p1: CollectedPoint, p2: CollectedPoint, p3: CollectedPoint, toleranceUm: Double): CircleFit {
        val x1 = p1.x
        val y1 = p1.y
        val x2 = p2.x
        val y2 = p2.y
        val x3 = p3.x
        val y3 = p3.y
        val d = (x2 - x1) * (y3 - y1) - (x3 - x1) * (y2 - y1)
        val l = maxOf(hypot(x2 - x1, y2 - y1), hypot(x3 - x2, y3 - y2), hypot(x1 - x3, y1 - y3))
        if (!(l > 0.0) || abs(d) / l < toleranceUm) return CircleFit.Failed(MeasurementFailure.Collinear)
        val s1 = x1 * x1 + y1 * y1
        val s2 = x2 * x2 + y2 * y2
        val s3 = x3 * x3 + y3 * y3
        val points = listOf(p1, p2, p3)
        val radii = points.map { point -> tipRadius(point) }
        if (radii.all { radius -> radius == 0.0 }) {
            val cx = ((y1 - y2) * s3 + (y3 - y1) * s2 + (y2 - y3) * s1) / (2.0 * d)
            val cy = ((x2 - x1) * s3 + (x1 - x3) * s2 + (x3 - x2) * s1) / (2.0 * d)
            return CircleFit.Fitted(FittedCircle(cx, cy, hypot(x1 - cx, y1 - cy)))
        }
        val candidates = tipCandidates(points, radii)
        val valid = candidates.filter { candidate -> agreesWithApproach(candidate, points) }
        return when (valid.size) {
            0 -> CircleFit.Failed(MeasurementFailure.TipFitNoCandidate)
            1 -> CircleFit.Fitted(valid.single())
            else -> CircleFit.Failed(MeasurementFailure.TipFitTwoCandidates)
        }
    }

    /**
     * Gives all circles that solve `|Pi − C| = R + s·ri` for the sides −1 and +1, with the root
     * rules of step 2 of [circleFrom3Points].
     */
    fun tipCandidates(points: List<CollectedPoint>, radii: List<Double>): List<FittedCircle> {
        require(points.size == 3 && radii.size == 3) { "three points and three radii" }
        val (x1, x2, x3) = points.map { point -> point.x }
        val (y1, y2, y3) = points.map { point -> point.y }
        val (r1, r2, r3) = radii
        val s1 = x1 * x1 + y1 * y1
        val s2 = x2 * x2 + y2 * y2
        val s3 = x3 * x3 + y3 * y3
        val candidates = mutableListOf<FittedCircle>()
        for (side in intArrayOf(-1, 1)) {
            val s = side.toDouble()
            val a1 = 2.0 * (x1 - x2)
            val b1 = 2.0 * (y1 - y2)
            val c1 = 2.0 * s * (r1 - r2)
            val e1 = r2 * r2 + s1 - s2 - r1 * r1
            val a2 = 2.0 * (x1 - x3)
            val b2 = 2.0 * (y1 - y3)
            val c2 = 2.0 * s * (r1 - r3)
            val e2 = r3 * r3 + s1 - s3 - r1 * r1
            val det = a1 * b2 - a2 * b1
            if (det == 0.0) continue
            val kx = (e1 * b2 - e2 * b1) / det
            val mx = -(b2 * c1 - b1 * c2) / det
            val ky = (e2 * a1 - e1 * a2) / det
            val my = -(a1 * c2 - a2 * c1) / det
            val u = x1 - kx
            val v = y1 - ky
            val a = mx * mx + my * my - 1.0
            val b = -2.0 * (u * mx + v * my + s * r1)
            val c = u * u + v * v - r1 * r1
            for (r in quadraticRoots(a, b, c)) {
                if (r > 0.0 && radii.all { radius -> r + s * radius > 0.0 }) {
                    candidates += FittedCircle(kx + mx * r, ky + my * r, r, side)
                }
            }
        }
        return candidates
    }

    /**
     * Gives true when [candidate] agrees with the approach signs of [points] (step 3 of
     * [circleFrom3Points]).
     */
    fun agreesWithApproach(candidate: FittedCircle, points: List<CollectedPoint>): Boolean {
        var examined = 0
        for (point in points) {
            if (tipRadius(point) <= 0.0 || !hasApproach(point)) continue
            val dot = point.approachX * (point.x - candidate.centerXUm) + point.approachY * (point.y - candidate.centerYUm)
            if (dot == 0.0) continue
            val neededSide = if (dot > 0.0) -1 else 1
            if (neededSide != candidate.side) return false
            examined++
        }
        return examined > 0
    }

    /**
     * Fits a line through two points.
     *
     * `L = |P1 − P2|`. `L < tol` gives [MeasurementFailure.PointsTooClose]. With no tip radius on
     * the two points, the line is P1 → P2. Else the line is the common tangent of the two tip
     * circles: `u = (P1 − P2) / L`, `k = (r1 − r2) / L` (`|k| > 1` gives
     * [MeasurementFailure.TangentImpossible]), `w = √(1 − k²)`, the normals
     * `n1 = (ux·k − uy·w, uy·k + ux·w)` and `n2 = (ux·k + uy·w, uy·k − ux·w)`. A normal is kept when
     * `n · approach < 0` for each point with a tip radius and an approach, and at least one point is
     * examined. Exactly one normal must stay: the line is `(P1 − r1·n) → (P2 − r2·n)`. No normal
     * gives [MeasurementFailure.LineTipNoNormal], two give [MeasurementFailure.LineTipTwoNormals].
     */
    fun lineFrom2Points(p1: CollectedPoint, p2: CollectedPoint, toleranceUm: Double): LineFit {
        val l = hypot(p1.x - p2.x, p1.y - p2.y)
        if (!(l >= toleranceUm) || !(l > 0.0)) return LineFit.Failed(MeasurementFailure.PointsTooClose)
        val r1 = tipRadius(p1)
        val r2 = tipRadius(p2)
        if (r1 == 0.0 && r2 == 0.0) return LineFit.Fitted(FittedLine(p1.x, p1.y, p2.x, p2.y))
        val ux = (p1.x - p2.x) / l
        val uy = (p1.y - p2.y) / l
        val k = (r1 - r2) / l
        if (abs(k) > 1.0) return LineFit.Failed(MeasurementFailure.TangentImpossible)
        val w = sqrt(1.0 - k * k)
        val normals = listOf(
            PointUm(ux * k - uy * w, uy * k + ux * w),
            PointUm(ux * k + uy * w, uy * k - ux * w),
        )
        val kept = normals.filter { normal -> normalAgrees(normal, listOf(p1, p2)) }
        return when (kept.size) {
            0 -> LineFit.Failed(MeasurementFailure.LineTipNoNormal)
            1 -> {
                val n = kept.single()
                LineFit.Fitted(FittedLine(p1.x - r1 * n.h, p1.y - r1 * n.v, p2.x - r2 * n.h, p2.y - r2 * n.v))
            }
            else -> LineFit.Failed(MeasurementFailure.LineTipTwoNormals)
        }
    }

    /**
     * Gives the angle to X in degrees of the direction ([dx], [dy]): `degrees(atan2(dy, dx)) mod
     * 180`, then into (−90°, 90°]. The `mod` keeps the sign of the dividend.
     */
    fun angleToX(dx: Double, dy: Double): Double = intoHalfTurn((atan2(dy, dx) * DEGREES_PER_RADIAN) % HALF_TURN)

    /** Gives the angle to Y in degrees: `(angleToX − 90) mod 180`, then into (−90°, 90°]. */
    fun angleToY(angleToX: Double): Double = intoHalfTurn((angleToX - QUARTER_TURN) % HALF_TURN)

    /** Moves [degrees] from (−180°, 180°) into (−90°, 90°]. */
    fun intoHalfTurn(degrees: Double): Double = when {
        degrees > QUARTER_TURN -> degrees - HALF_TURN
        degrees <= -QUARTER_TURN -> degrees + HALF_TURN
        else -> degrees
    }

    /**
     * Gives the included angle in degrees in [0°, 180°): `(a2 − a1) mod 180`, plus 180 if
     * negative.
     */
    fun includedAngle(angleToX1: Double, angleToX2: Double): Double {
        val angle = (angleToX2 - angleToX1) % HALF_TURN
        val positive = if (angle < 0.0) angle + HALF_TURN else angle
        return if (positive >= HALF_TURN) 0.0 else positive + 0.0
    }

    /**
     * Gives the corner of two lines, or null when the lines are parallel:
     * `|cross| / (|line 1| × |line 2|) < `[PARALLEL_LIMIT].
     */
    fun corner(line1: FittedLine, line2: FittedLine): PointUm? {
        val cross = line1.dxUm * line2.dyUm - line1.dyUm * line2.dxUm
        val lengths = line1.lengthUm * line2.lengthUm
        if (!(lengths > 0.0) || abs(cross) / lengths < PARALLEL_LIMIT) return null
        val t = (line2.dyUm * (line2.startXUm - line1.startXUm) - line2.dxUm * (line2.startYUm - line1.startYUm)) / cross
        return PointUm(line1.startXUm + t * line1.dxUm, line1.startYUm + t * line1.dyUm)
    }

    /**
     * Gives true when the corner add button is enabled: the largest distance from [corner] to the
     * four line ends is not more than [CORNER_DISTANCE_FACTOR] × the longer line length.
     */
    fun cornerAddEnabled(corner: PointUm, line1: FittedLine, line2: FittedLine): Boolean {
        val ends = listOf(
            PointUm(line1.startXUm, line1.startYUm),
            PointUm(line1.endXUm, line1.endYUm),
            PointUm(line2.startXUm, line2.startYUm),
            PointUm(line2.endXUm, line2.endYUm),
        )
        val farthest = ends.maxOf { end -> corner.distanceTo(end) }
        return farthest <= CORNER_DISTANCE_FACTOR * max(line1.lengthUm, line2.lengthUm)
    }

    /** Gives the tip radius of [point] in µm, or 0 when it has none. */
    internal fun tipRadius(point: CollectedPoint): Double = point.tipRadiusUm?.takeIf { radius -> radius > 0.0 } ?: 0.0

    private fun hasApproach(point: CollectedPoint): Boolean = point.approachX != 0 || point.approachY != 0

    private fun normalAgrees(normal: PointUm, points: List<CollectedPoint>): Boolean {
        var examined = 0
        for (point in points) {
            if (tipRadius(point) <= 0.0 || !hasApproach(point)) continue
            val dot = normal.h * point.approachX + normal.v * point.approachY
            if (!(dot < 0.0)) return false
            examined++
        }
        return examined > 0
    }

    /**
     * Gives the roots of `a·R² + b·R + c = 0` with the stable form. `|a| < 1e-12` gives
     * `R = −c / b` when `b ≠ 0`.
     */
    private fun quadraticRoots(a: Double, b: Double, c: Double): List<Double> {
        if (abs(a) < LINEAR_LIMIT) return if (b != 0.0) listOf(-c / b) else emptyList()
        val disc = b * b - 4.0 * a * c
        return when {
            disc < 0.0 -> emptyList()
            disc == 0.0 -> listOf(-0.5 * b / a)
            else -> {
                val signB = if (b < 0.0) -1.0 else 1.0
                val q = -0.5 * (b + signB * sqrt(disc))
                if (q == 0.0) listOf(q / a, 0.0) else listOf(q / a, c / q)
            }
        }
    }

    private const val DEGREES_PER_RADIAN = 180.0 / PI
    private const val HALF_TURN = 180.0
    private const val QUARTER_TURN = 90.0
}
