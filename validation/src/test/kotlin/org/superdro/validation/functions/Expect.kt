package org.superdro.validation.functions

import kotlin.math.abs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The compare helpers of the validation tests of the machine functions. A value test has a
 * tolerance of 0.5 in the last digit of the display format.
 */
internal object Expect {

    /** 0.5 in the last digit of a mm value with 3 decimals, in µm. */
    const val MM3_UM: Double = 0.5

    /** 0.5 in the last digit of an angle with 2 decimals, in degrees. */
    const val DEG2: Double = 0.005

    /** 0.5 in the last digit of an angle with 4 decimals, in degrees. */
    const val DEG4: Double = 0.00005

    /** µm for each mm. */
    const val MM: Double = 1000.0

    /** Arc seconds for each degree. */
    const val DEG: Double = 3600.0

    fun near(expected: Double, actual: Double?, tolerance: Double, what: String = "value") {
        if (actual == null) fail("$what: expected $expected, but the value is null")
        assertTrue(abs(expected - actual) <= tolerance, "$what: expected $expected ± $tolerance, actual $actual")
    }

    /** Compares a value in µm with an expected value in mm (3 decimals). */
    fun mm(expectedMm: Double, actualUm: Double?, what: String = "value") = near(expectedMm * MM, actualUm, MM3_UM, what)
}
