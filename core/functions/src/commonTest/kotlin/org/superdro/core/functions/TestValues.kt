package org.superdro.core.functions

import kotlin.math.abs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The value checks: a tolerance of 0.5 in the last digit of the display format.
 */
object TestValues {

    /** Checks that [actualUm] shows as [expectedMm] with 3 decimals (tolerance 0.5 µm). */
    fun assertMm(expectedMm: Double, actualUm: Double?, label: String = "") {
        assertNotNull(actualUm, "$label: no value")
        val difference = abs(expectedMm * 1000.0 - actualUm)
        assertTrue(difference <= 0.5 + 1e-9, "$label: expected $expectedMm mm, got ${actualUm / 1000.0} mm")
    }

    /** Checks that [actualArcSec] shows as [expectedDegrees] with [decimals] decimals. */
    fun assertDegrees(expectedDegrees: Double, actualArcSec: Double?, decimals: Int = 4, label: String = "") {
        assertNotNull(actualArcSec, "$label: no value")
        var unit = 1.0
        repeat(decimals) { unit /= 10.0 }
        val difference = abs(expectedDegrees - actualArcSec / 3600.0)
        assertTrue(
            difference <= unit / 2.0 + 1e-12,
            "$label: expected $expectedDegrees°, got ${actualArcSec / 3600.0}°",
        )
    }

    /** Converts degrees to arc seconds. */
    fun deg(degrees: Double): Double = degrees * 3600.0

    /** Converts mm to µm. */
    fun mm(millimeters: Double): Double = millimeters * 1000.0
}
