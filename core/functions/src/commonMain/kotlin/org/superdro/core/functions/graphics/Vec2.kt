package org.superdro.core.functions.graphics

import kotlin.math.atan2
import kotlin.math.hypot
import org.superdro.core.functions.PointUm

/**
 * A vector in a plane. The unit comes from the use: screen pixels, image pixels, or µm. The
 * graphical view uses [PointUm] for a model position and this type for the other values.
 */
data class Vec2(val x: Double, val y: Double) {

    operator fun plus(other: Vec2): Vec2 = Vec2(x + other.x, y + other.y)

    operator fun minus(other: Vec2): Vec2 = Vec2(x - other.x, y - other.y)

    operator fun times(factor: Double): Vec2 = Vec2(x * factor, y * factor)

    operator fun div(divisor: Double): Vec2 = Vec2(x / divisor, y / divisor)

    operator fun unaryMinus(): Vec2 = Vec2(-x, -y)

    /** The length of the vector. */
    val length: Double get() = hypot(x, y)

    /** The direction of the vector in radians, from `atan2(y, x)`. */
    val angle: Double get() = atan2(y, x)

    /** Gives the dot product with [other]. */
    fun dot(other: Vec2): Double = x * other.x + y * other.y

    /** Gives the z value of the cross product with [other]. A positive value is a turn to the left. */
    fun cross(other: Vec2): Double = x * other.y - y * other.x

    /** Gives the distance to [other]. */
    fun distanceTo(other: Vec2): Double = hypot(other.x - x, other.y - y)

    /** Gives this vector turned by [angleRad] (counterclockwise when the y axis points up). */
    fun rotated(angleRad: Double): Vec2 {
        val (cos, sin) = AngleMath.cosSinRad(angleRad)
        return Vec2(x * cos - y * sin, x * sin + y * cos)
    }

    /** Gives the model position with the same values. */
    fun toPointUm(): PointUm = PointUm(x, y)

    companion object {
        val ZERO: Vec2 = Vec2(0.0, 0.0)

        /** Gives the vector of a model position. */
        fun of(point: PointUm): Vec2 = Vec2(point.h, point.v)
    }
}
