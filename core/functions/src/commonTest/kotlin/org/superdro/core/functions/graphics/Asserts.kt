package org.superdro.core.functions.graphics

import kotlin.math.abs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.superdro.core.functions.PointUm

/** Asserts that [actual] is within [tolerance] of [expected] on each axis. */
fun assertNear(expected: Vec2, actual: Vec2?, tolerance: Double = 1e-6, message: String = "") {
    val value = assertNotNull(actual, message)
    assertTrue(
        abs(expected.x - value.x) <= tolerance && abs(expected.y - value.y) <= tolerance,
        "$message expected $expected, actual $value (tolerance $tolerance)",
    )
}

/** Asserts that [actual] is within [tolerance] of [expected] on each axis. */
fun assertNear(expected: PointUm, actual: PointUm?, tolerance: Double = 1e-6, message: String = "") {
    val value = assertNotNull(actual, message)
    assertTrue(
        abs(expected.h - value.h) <= tolerance && abs(expected.v - value.v) <= tolerance,
        "$message expected $expected, actual $value (tolerance $tolerance)",
    )
}

/** Asserts that each value of [actual] is within [tolerance] of [expected]. */
fun assertNear(expected: Matrix3, actual: Matrix3?, tolerance: Double = 1e-9, message: String = "") {
    val value = assertNotNull(actual, message)
    val pairs = expected.toList().zip(value.toList())
    assertTrue(
        pairs.all { (e, a) -> abs(e - a) <= tolerance * maxOf(1.0, abs(e)) },
        "$message expected $expected, actual $value (tolerance $tolerance)",
    )
}
