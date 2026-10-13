package org.superdro.core.readout

import kotlin.test.Test
import kotlin.test.assertEquals
import org.superdro.core.model.ReadoutFormat

/** The number formats and the round-off. */
class NumberPatternTest {

    @Test
    fun theLinearPatternsOfEachDisplayFormat() {
        assertEquals("##0.000", ReadoutPatterns.linear(ReadoutFormat.Format2, metric = false).pattern)
        assertEquals("###0.00", ReadoutPatterns.linear(ReadoutFormat.Format2, metric = true).pattern)
        assertEquals("##0.0000", ReadoutPatterns.linear(ReadoutFormat.Format3, metric = false).pattern)
        assertEquals("###0.000", ReadoutPatterns.linear(ReadoutFormat.Format3, metric = true).pattern)
        assertEquals("##0.00000", ReadoutPatterns.linear(ReadoutFormat.Format5, metric = false).pattern)
        assertEquals("###0.0000", ReadoutPatterns.linear(ReadoutFormat.Format5, metric = true).pattern)
    }

    @Test
    fun theTouchDroCodesZeroOneAndFourMapToTwoThreeAndFive() {
        assertEquals(ReadoutFormat.Format2, ReadoutPatterns.formatForCode(0))
        assertEquals(ReadoutFormat.Format3, ReadoutPatterns.formatForCode(1))
        assertEquals(ReadoutFormat.Format2, ReadoutPatterns.formatForCode(2))
        assertEquals(ReadoutFormat.Format3, ReadoutPatterns.formatForCode(3))
        assertEquals(ReadoutFormat.Format5, ReadoutPatterns.formatForCode(4))
        assertEquals(ReadoutFormat.Format5, ReadoutPatterns.formatForCode(5))
        assertEquals(ReadoutFormat.Format3, ReadoutPatterns.formatForCode(9))
    }

    @Test
    fun theOtherPatterns() {
        assertEquals("##0.00", ReadoutPatterns.ANGULAR.pattern)
        assertEquals("####0", ReadoutPatterns.RPM.pattern)
        assertEquals("###0.00", ReadoutPatterns.SURFACE_SPEED.pattern)
        assertEquals("###0.00", ReadoutPatterns.THREAD_PITCH_MM.pattern)
        assertEquals("###0.0", ReadoutPatterns.THREADS_PER_INCH.pattern)
        assertEquals("1200", ReadoutPatterns.RPM.format(1200.0))
        assertEquals("101.6", ReadoutPatterns.THREADS_PER_INCH.format(101.6))
    }

    @Test
    fun theWorkedExamplesOfTheNumberFormat() {
        val inch = ReadoutPatterns.linear(ReadoutFormat.Format3, metric = false)
        val mm = ReadoutPatterns.linear(ReadoutFormat.Format3, metric = true)
        assertEquals("2.0000", inch.format(UnitConversion.umToInch(50800.0)))
        assertEquals("2.4301", inch.format(UnitConversion.umToInch(12345 * 5.0)))
        assertEquals("61.725", mm.format(UnitConversion.umToMm(61725.0)))
        assertEquals("1.0000", inch.format(UnitConversion.umToInch(25400.0)))
    }

    @Test
    fun theRoundOffGoesHalfUpAsJavaMathRound() {
        assertEquals(12346L, NumberPattern.roundHalfUp(12345.5))
        assertEquals(-12345L, NumberPattern.roundHalfUp(-12345.5))
        assertEquals(1L, NumberPattern.roundHalfUp(0.5))
        assertEquals(0L, NumberPattern.roundHalfUp(-0.5))
        assertEquals(0L, NumberPattern.roundHalfUp(0.49999999999999994))
        // 0.25 and 0.125 have exact binary values, so the scaled value is exactly a half step.
        assertEquals("0.3", NumberPattern("##0.0").format(0.25))
        assertEquals("-0.2", NumberPattern("##0.0").format(-0.25))
        assertEquals("0.13", NumberPattern("##0.00").format(0.125))
        assertEquals("-0.12", NumberPattern("##0.00").format(-0.125))
    }

    @Test
    fun theTextNeverShowsMinusZero() {
        val pattern = NumberPattern("##0.0000")
        assertEquals("0.0000", pattern.format(-0.00001))
        assertEquals("0.0000", pattern.format(-0.0))
        assertEquals("-0.0001", pattern.format(-0.00006))
    }

    @Test
    fun aLargeValueHasNoIntLimit() {
        val pattern = NumberPattern("##0.00000")
        assertEquals("21474.83648", pattern.format(21474.83648))
        assertEquals("100000.00000", pattern.format(100000.0))
    }

    @Test
    fun aValueThatIsNotFiniteShowsTheDashPattern() {
        assertEquals("---.----", NumberPattern("##0.0000").format(Double.NaN))
        assertEquals("----.---", NumberPattern("###0.000").format(Double.POSITIVE_INFINITY))
        assertEquals("-----", ReadoutPatterns.RPM.format(Double.NEGATIVE_INFINITY))
    }
}
