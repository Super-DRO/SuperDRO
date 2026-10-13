package org.superdro.core.adapterprobe

import kotlin.test.Test
import kotlin.test.assertEquals

/** The number and text formats of the tool. The expected values come from Python 3 (`"%.Nf" % x`, `repr`). */
class PyTextTest {

    @Test
    fun fixedRoundsLikePython() {
        val cases = listOf(
            Triple(1.25, 1, "1.2"),
            Triple(0.15, 1, "0.1"),
            Triple(2.675, 2, "2.67"),
            Triple(-0.04, 1, "-0.0"),
            Triple(0.5, 0, "0"),
            Triple(1.5, 0, "2"),
            Triple(2.5, 0, "2"),
            Triple(50.0, 1, "50.0"),
            Triple(1234.5678, 3, "1234.568"),
            Triple(0.125, 2, "0.12"),
            Triple(0.0, 1, "0.0"),
            Triple(-0.0, 1, "-0.0"),
            Triple(99.95, 1, "100.0"),
            Triple(1e-7, 3, "0.000"),
            Triple(3.0000000000000004, 1, "3.0"),
            Triple(12.35, 1, "12.3"),
        )
        for ((value, decimals, expected) in cases) assertEquals(expected, PyText.fixed(value, decimals), "$value with $decimals decimals")
    }

    @Test
    fun signedAlwaysHasASign() {
        assertEquals("+0.0", PyText.signed(0.0, 1))
        assertEquals("-0.0", PyText.signed(-0.04, 1))
        assertEquals("+12.3", PyText.signed(12.34, 1))
    }

    @Test
    fun msGivesOneDecimalOrADash() {
        assertEquals("-", PyText.ms(null))
        assertEquals("50.0", PyText.ms(0.05))
        assertEquals("1500.0", PyText.ms(1.5))
    }

    @Test
    fun reprAgreesWithPython() {
        assertEquals("'X12 3;'", PyText.repr("X12 3;"))
        assertEquals("'\"#get:mac;\"'", PyText.repr("\"#get:mac;\""))
        assertEquals("\"it's\"", PyText.repr("it's"))
        assertEquals("'a\\\\b'", PyText.repr("a\\b"))
        assertEquals("'both \\' and \"'", PyText.repr("both ' and \""))
        assertEquals("'\\x07x'", PyText.repr("\u0007x"))
    }

    @Test
    fun strGivesNoneForNull() {
        assertEquals("None", PyText.str(null))
        assertEquals("12", PyText.str(12L))
        assertEquals("1.0", PyText.str(1.0))
    }
}
