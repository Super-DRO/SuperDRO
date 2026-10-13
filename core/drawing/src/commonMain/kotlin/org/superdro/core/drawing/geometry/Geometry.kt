package org.superdro.core.drawing.geometry

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** A 2D vector of doubles. */
internal data class Vec(val x: Double, val y: Double) {
    operator fun plus(other: Vec) = Vec(x + other.x, y + other.y)

    operator fun minus(other: Vec) = Vec(x - other.x, y - other.y)

    operator fun times(factor: Double) = Vec(x * factor, y * factor)

    operator fun unaryMinus() = Vec(-x, -y)

    infix fun dot(other: Vec) = x * other.x + y * other.y

    infix fun cross(other: Vec) = x * other.y - y * other.x

    val length: Double get() = sqrt(x * x + y * y)

    companion object {
        val ZERO = Vec(0.0, 0.0)
    }
}

/**
 * A 2D affine map: `x' = a·x + b·y + e`, `y' = c·x + d·y + f`. The DXF converter maps the block
 * coordinates, the object coordinate systems, and the units to nm with this map.
 */
internal data class Affine(
    val a: Double,
    val b: Double,
    val c: Double,
    val d: Double,
    val e: Double,
    val f: Double,
) {
    fun apply(x: Double, y: Double): Vec = Vec(a * x + b * y + e, c * x + d * y + f)

    fun apply(point: Vec): Vec = apply(point.x, point.y)

    /** Maps a vector: only the linear part. */
    fun linear(vector: Vec): Vec = Vec(a * vector.x + b * vector.y, c * vector.x + d * vector.y)

    /** Gives the map that applies [inner] first and then this map. */
    operator fun times(inner: Affine): Affine = Affine(
        a = a * inner.a + b * inner.c,
        b = a * inner.b + b * inner.d,
        c = c * inner.a + d * inner.c,
        d = c * inner.b + d * inner.d,
        e = a * inner.e + b * inner.f + e,
        f = c * inner.e + d * inner.f + f,
    )

    val determinant: Double get() = a * d - b * c

    /** The scale of a similarity map: the square root of the absolute determinant. */
    val scale: Double get() = sqrt(abs(determinant))

    /**
     * True when the map keeps circles as circles: a rotation, a uniform scale, a translation, and
     * possibly a mirror.
     */
    val isSimilarity: Boolean
        get() {
            val size = max(max(abs(a), abs(b)), max(abs(c), abs(d)))
            if (size == 0.0) return false
            val tolerance = size * SIMILARITY_TOLERANCE
            val keeps = abs(a - d) <= tolerance && abs(b + c) <= tolerance
            val mirrors = abs(a + d) <= tolerance && abs(b - c) <= tolerance
            return keeps || mirrors
        }

    /** The rotation angle of the linear part: `atan2(c, a)`. */
    val rotation: Double get() = atan2(c, a)

    /** The largest stretch of the linear part (the largest singular value). */
    val maxStretch: Double
        get() {
            val t = a * a + b * b + c * c + d * d
            val det = determinant
            val disc = sqrt(max(0.0, t * t - 4 * det * det))
            return sqrt(max(0.0, (t + disc) / 2))
        }

    companion object {
        const val SIMILARITY_TOLERANCE: Double = 1e-9

        val IDENTITY = Affine(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)

        fun translate(x: Double, y: Double) = Affine(1.0, 0.0, 0.0, 1.0, x, y)

        fun scale(sx: Double, sy: Double) = Affine(sx, 0.0, 0.0, sy, 0.0, 0.0)

        /** A rotation by [radians] (counterclockwise). The angles 0, 90, 180, and 270 degrees give exact values. */
        fun rotate(radians: Double): Affine {
            val (cosine, sine) = exactCosSin(radians)
            return Affine(cosine, -sine, sine, cosine, 0.0, 0.0)
        }

        /**
         * The map from an object coordinate system (OCS) with the extrusion ([nx], [ny], [nz]) to the
         * XY plane of the world coordinate system (the arbitrary axis algorithm of the DXF
         * reference). [elevation] is the OCS Z value. The extrusion (0, 0, 1) gives the identity, and
         * (0, 0, −1) gives x → −x.
         */
        fun ocs(nx: Double, ny: Double, nz: Double, elevation: Double = 0.0): Affine {
            val length = sqrt(nx * nx + ny * ny + nz * nz)
            if (length == 0.0 || (nx == 0.0 && ny == 0.0 && nz > 0.0)) return IDENTITY
            val n = doubleArrayOf(nx / length, ny / length, nz / length)
            val ax = if (abs(n[0]) < 1.0 / 64 && abs(n[1]) < 1.0 / 64) {
                cross(doubleArrayOf(0.0, 1.0, 0.0), n)
            } else {
                cross(doubleArrayOf(0.0, 0.0, 1.0), n)
            }.normalized()
            val ay = cross(n, ax).normalized()
            return Affine(ax[0], ay[0], ax[1], ay[1], elevation * n[0], elevation * n[1])
        }

        private fun cross(u: DoubleArray, v: DoubleArray) = doubleArrayOf(
            u[1] * v[2] - u[2] * v[1],
            u[2] * v[0] - u[0] * v[2],
            u[0] * v[1] - u[1] * v[0],
        )

        private fun DoubleArray.normalized(): DoubleArray {
            val length = sqrt(this[0] * this[0] + this[1] * this[1] + this[2] * this[2])
            return if (length == 0.0 || length == 1.0) this else doubleArrayOf(this[0] / length, this[1] / length, this[2] / length)
        }
    }
}

/** Gives the cosine and the sine of [radians], exact for the multiples of 90°. */
internal fun exactCosSin(radians: Double): Pair<Double, Double> {
    val quarter = radians / (PI / 2)
    val rounded = kotlin.math.round(quarter)
    if (abs(quarter - rounded) < 1e-12) {
        return when (((rounded.toLong() % 4) + 4) % 4) {
            0L -> 1.0 to 0.0
            1L -> 0.0 to 1.0
            2L -> -1.0 to 0.0
            else -> 0.0 to -1.0
        }
    }
    return cos(radians) to sin(radians)
}

/** Gives [angle] in the range [0, 2π). */
internal fun normalizeAngle(angle: Double): Double {
    val full = 2 * PI
    var value = angle % full
    if (value < 0) value += full
    if (value >= full) value -= full
    return value
}

/** One cubic Bézier segment. */
internal data class Cubic(val p0: Vec, val p1: Vec, val p2: Vec, val p3: Vec) {
    fun at(t: Double): Vec {
        val s = 1 - t
        val b0 = s * s * s
        val b1 = 3 * s * s * t
        val b2 = 3 * s * t * t
        val b3 = t * t * t
        return Vec(
            b0 * p0.x + b1 * p1.x + b2 * p2.x + b3 * p3.x,
            b0 * p0.y + b1 * p1.y + b2 * p2.y + b3 * p3.y,
        )
    }
}

/**
 * An elliptical arc: `P(t) = center + u·cos t + v·sin t` for t from [start] to [start] + [sweep].
 * A circle of radius r has |u| = |v| = r and u ⟂ v.
 */
internal data class Conic(val center: Vec, val u: Vec, val v: Vec, val start: Double, val sweep: Double) {
    fun at(t: Double): Vec {
        val (cosine, sine) = exactCosSin(t)
        return center + u * cosine + v * sine
    }

    fun derivative(t: Double): Vec {
        val (cosine, sine) = exactCosSin(t)
        return v * cosine - u * sine
    }

    fun mapped(map: Affine): Conic = Conic(map.apply(center), map.linear(u), map.linear(v), start, sweep)

    /** The largest radius: the largest singular value of the matrix [u v]. */
    val maxRadius: Double
        get() = Affine(u.x, v.x, u.y, v.y, 0.0, 0.0).maxStretch

    /**
     * The number of cubic segments that approximate this arc within [toleranceNm]. The map from the
     * unit circle to the arc stretches the error of the unit circle by at most [maxRadius].
     */
    fun segmentCount(toleranceNm: Double): Long {
        val total = abs(sweep)
        val radius = maxRadius
        if (radius == 0.0 || total == 0.0) return 1
        var count = max(1.0, ceil(total / (PI / 2) - 1e-9))
        val first = radius * unitCircleError(total / count)
        if (first > toleranceNm) {
            // The error changes as the sixth power of the segment angle.
            count = ceil(count * (first / toleranceNm).pow(1.0 / 6))
        }
        while (count < 1e7 && radius * unitCircleError(total / count) > toleranceNm) count += max(1.0, ceil(count / 50))
        return count.toLong()
    }

    /** Gives [count] cubic segments, with `k = 4/3·tan(Δ/4)`. */
    fun cubics(count: Long): List<Cubic> {
        val delta = sweep / count
        val k = 4.0 / 3.0 * tan(delta / 4)
        val result = ArrayList<Cubic>(count.toInt())
        for (index in 0 until count) {
            val ta = start + delta * index
            val tb = if (index == count - 1) start + sweep else start + delta * (index + 1)
            val pa = at(ta)
            val pb = at(tb)
            result += Cubic(pa, pa + derivative(ta) * k, pb - derivative(tb) * k, pb)
        }
        return result
    }

    companion object {
        /**
         * The largest radial error of the cubic approximation of a unit circle arc of [angle]
         * radians, with a margin of 5 %.
         */
        fun unitCircleError(angle: Double): Double {
            val k = 4.0 / 3.0 * tan(angle / 4)
            val cubic = Cubic(Vec(1.0, 0.0), Vec(1.0, k), Vec(cos(angle) + k * sin(angle), sin(angle) - k * cos(angle)), Vec(cos(angle), sin(angle)))
            var worst = 0.0
            for (step in 0..32) {
                val point = cubic.at(step / 64.0)
                worst = max(worst, abs(point.length - 1))
            }
            return worst * 1.05 + 1e-15
        }
    }
}

/** The arc of a polyline segment from [start] to [end] with the [bulge] (`tan(angle / 4)`). */
internal fun bulgeArc(start: Vec, end: Vec, bulge: Double): Conic {
    val chord = end - start
    val middle = (start + end) * 0.5
    val offset = (1 - bulge * bulge) / (4 * bulge)
    val center = middle + Vec(-chord.y, chord.x) * offset
    val radius = (start - center).length
    val startAngle = atan2(start.y - center.y, start.x - center.x)
    val sweep = 4 * kotlin.math.atan(bulge)
    return Conic(center, Vec(radius, 0.0), Vec(0.0, radius), startAngle, sweep)
}

/** A straight line as a cubic segment. */
internal fun lineCubic(start: Vec, end: Vec): Cubic {
    val step = (end - start) * (1.0 / 3.0)
    return Cubic(start, start + step, end - step, end)
}
