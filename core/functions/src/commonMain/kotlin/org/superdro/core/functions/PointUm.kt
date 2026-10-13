package org.superdro.core.functions

import kotlin.math.hypot

/**
 * A point in a plane, in µm. The functions of this module (hole patterns, arcs, measurement,
 * transforms) use it.
 */
data class PointUm(val h: Double, val v: Double) {

    /** Gives the distance to [other] in µm. */
    fun distanceTo(other: PointUm): Double = hypot(other.h - h, other.v - v)

    /** Gives the midpoint between this point and [other]. */
    fun midpoint(other: PointUm): PointUm = PointUm((h + other.h) / 2.0, (v + other.v) / 2.0)
}
