package org.superdro.core.functions.graphics

/**
 * A 3 × 3 matrix of a plane transform, row by row. A point (x, y) is the column (x, y, 1). The
 * matrix can be projective: [map] divides by the weight `m20·x + m21·y + m22`.
 *
 * The reference image of a workspace stores a matrix of this type as 9 values
 * (`ReferenceImage.matrix`).
 */
data class Matrix3(
    val m00: Double, val m01: Double, val m02: Double,
    val m10: Double, val m11: Double, val m12: Double,
    val m20: Double, val m21: Double, val m22: Double,
) {

    /** Gives the product `this × other`. The result applies [other] first, then this matrix. */
    operator fun times(other: Matrix3): Matrix3 = Matrix3(
        m00 * other.m00 + m01 * other.m10 + m02 * other.m20,
        m00 * other.m01 + m01 * other.m11 + m02 * other.m21,
        m00 * other.m02 + m01 * other.m12 + m02 * other.m22,
        m10 * other.m00 + m11 * other.m10 + m12 * other.m20,
        m10 * other.m01 + m11 * other.m11 + m12 * other.m21,
        m10 * other.m02 + m11 * other.m12 + m12 * other.m22,
        m20 * other.m00 + m21 * other.m10 + m22 * other.m20,
        m20 * other.m01 + m21 * other.m11 + m22 * other.m21,
        m20 * other.m02 + m21 * other.m12 + m22 * other.m22,
    )

    /**
     * Gives the matrix that applies this matrix first and [next] after it: `next × this`. This is
     * the "post-concat" of a workspace transform.
     */
    fun then(next: Matrix3): Matrix3 = next * this

    /** Gives the projective weight of the point (x, y): `m20·x + m21·y + m22`. */
    fun weight(x: Double, y: Double): Double = m20 * x + m21 * y + m22

    /** Gives the image of the point (x, y). A weight of 0 gives an infinite or NaN value. */
    fun map(x: Double, y: Double): Vec2 {
        val w = weight(x, y)
        return Vec2((m00 * x + m01 * y + m02) / w, (m10 * x + m11 * y + m12) / w)
    }

    /** Gives the image of [point]. */
    fun map(point: Vec2): Vec2 = map(point.x, point.y)

    /** The determinant. */
    val determinant: Double
        get() = m00 * (m11 * m22 - m12 * m21) - m01 * (m10 * m22 - m12 * m20) + m02 * (m10 * m21 - m11 * m20)

    /** True when all 9 values are finite. */
    val isFinite: Boolean get() = toList().all { value -> value.isFinite() }

    /** True when the matrix has no projective part: `m20 = m21 = 0` and `m22 = 1`. */
    val isAffine: Boolean get() = m20 == 0.0 && m21 == 0.0 && m22 == 1.0

    /** Gives the inverse matrix, or null when the determinant is 0 or not finite. */
    fun inverse(): Matrix3? {
        val det = determinant
        if (det == 0.0 || !det.isFinite()) return null
        val result = Matrix3(
            (m11 * m22 - m12 * m21) / det,
            (m02 * m21 - m01 * m22) / det,
            (m01 * m12 - m02 * m11) / det,
            (m12 * m20 - m10 * m22) / det,
            (m00 * m22 - m02 * m20) / det,
            (m02 * m10 - m00 * m12) / det,
            (m10 * m21 - m11 * m20) / det,
            (m01 * m20 - m00 * m21) / det,
            (m00 * m11 - m01 * m10) / det,
        )
        return if (result.isFinite) result else null
    }

    /** Gives a matrix with all values divided by [m22], so that `m22` is 1. Gives this matrix when `m22` is 0. */
    fun normalized(): Matrix3 {
        if (m22 == 0.0 || m22 == 1.0) return this
        return Matrix3(m00 / m22, m01 / m22, m02 / m22, m10 / m22, m11 / m22, m12 / m22, m20 / m22, m21 / m22, 1.0)
    }

    /** Gives the 9 values row by row, the form of `ReferenceImage.matrix`. */
    fun toList(): List<Double> = listOf(m00, m01, m02, m10, m11, m12, m20, m21, m22)

    companion object {
        val IDENTITY: Matrix3 = Matrix3(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

        /** Gives the matrix of 9 values row by row. The list must have 9 values or more; the rest is ignored. */
        fun of(values: List<Double>): Matrix3 {
            require(values.size >= 9) { "A matrix needs 9 values, not ${values.size}" }
            return Matrix3(
                values[0], values[1], values[2],
                values[3], values[4], values[5],
                values[6], values[7], values[8],
            )
        }

        /** Gives a translation by (dx, dy). */
        fun translation(dx: Double, dy: Double): Matrix3 = Matrix3(1.0, 0.0, dx, 0.0, 1.0, dy, 0.0, 0.0, 1.0)

        /** Gives a scale by (sx, sy) about the point (0, 0). */
        fun scale(sx: Double, sy: Double): Matrix3 = Matrix3(sx, 0.0, 0.0, 0.0, sy, 0.0, 0.0, 0.0, 1.0)

        /** Gives a uniform scale by [s] about the pivot (px, py), as Android `Matrix.setScale(s, s, px, py)`. */
        fun scaleAbout(s: Double, px: Double, py: Double): Matrix3 =
            Matrix3(s, 0.0, px - s * px, 0.0, s, py - s * py, 0.0, 0.0, 1.0)

        /**
         * Gives a rotation about the pivot (px, py) with the values [cos] and [sin] of the angle. A
         * positive angle turns counterclockwise when the y axis points up.
         */
        fun rotationAbout(cos: Double, sin: Double, px: Double, py: Double): Matrix3 = Matrix3(
            cos, -sin, px - px * cos + py * sin,
            sin, cos, py - px * sin - py * cos,
            0.0, 0.0, 1.0,
        )
    }
}
