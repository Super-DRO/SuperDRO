package org.superdro.core.readout.encoder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.superdro.core.model.EncoderKind
import org.superdro.core.model.EncoderProfile
import org.superdro.core.readout.encoder.legacy.LegacyFiveSampleFilter
import org.superdro.core.readout.encoder.legacy.LegacyInput

/** The scale factor and the legacy reference. */
class ScaleAndLegacyTest {

    @Test
    fun theScaleFactors() {
        assertEquals(5.0, ScaleFactor.linear(5080.0))
        assertEquals(10.0, ScaleFactor.linear(2540.0))
        assertEquals(900.0, ScaleFactor.angular(1440.0))
        assertEquals(1296.0, ScaleFactor.angular(1000.0))
        assertEquals(180.0, ScaleFactor.angular(7200.0))
        assertEquals(0.25, ScaleFactor.rotary(4.0))
        assertEquals(0.0, ScaleFactor.rotary(0.0))
        assertEquals(-5.0, ScaleFactor.withDirection(5.0, invert = true))
    }

    @Test
    fun theScaleFactorOfAnEncoderProfile() {
        assertEquals(1.0, ScaleFactor.of('x', EncoderProfile.defaultFor('x')))
        assertEquals(1296.0, ScaleFactor.of('w', EncoderProfile.defaultFor('w', EncoderKind.Angular)))
        assertEquals(0.25, ScaleFactor.of('t', EncoderProfile.defaultFor('t').copy(resolution = 4.0)))
        assertEquals(-5.0, ScaleFactor.of('x', EncoderProfile(resolution = 5080.0, reversed = true)))
    }

    @Test
    fun noProfileADisabledInputOrABadResolutionGivesNoValue() {
        assertNull(ScaleFactor.of('x', null))
        assertNull(ScaleFactor.of('x', EncoderProfile(kind = EncoderKind.Disabled)))
        assertNull(ScaleFactor.of('x', EncoderProfile(resolution = 0.0)))
        assertNull(ScaleFactor.of('x', EncoderProfile(resolution = -5.0)))
    }

    @Test
    fun theHysteresisExample() {
        val input = LegacyInput(k = 5.0, hysteresis = 3L, filterEnabled = false)
        input.process(100L)
        assertEquals(520.0, input.process(101L))
        assertEquals(500.0, input.process(100L))
        // A repeated count keeps the last direction.
        assertEquals(500.0, input.process(100L))
    }

    @Test
    fun theFiveSampleFilterAlgorithmGivesTheExpectedTableWithTheZeroStart() {
        val filter = LegacyFiveSampleFilter(zeroStart = true)
        val out = (1000L..1007L).map { count -> filter.filter(count) }
        assertEquals(listOf(200L, 400L, 600L, 801L, 1002L, 1003L, 1004L, 1005L), out)
    }

    @Test
    fun theSuperDroFilterStartsWithTheFirstCountInAllFivePlaces() {
        val filter = LegacyFiveSampleFilter()
        assertEquals(1000L, filter.filter(1000L))
        assertEquals(1000L, filter.filter(1000L))
    }

    @Test
    fun theRepeatAtRestEach100MsBringsTheOutputToTheCount() {
        val input = LegacyInput(k = 1.0, hysteresis = 0L, filterEnabled = true)
        input.process(1000L, timeMs = 0L)
        val first = input.process(1100L, timeMs = 10L)
        assertTrue(first < 1100.0)
        assertTrue(input.needsRepeat)
        assertFalse(input.onTick(nowMs = 50L), "less than 100 ms passed")
        var time = 10L
        var ticks = 0
        while (input.needsRepeat && ticks < 50) {
            time += LegacyInput.REPEAT_INTERVAL_MS
            input.onTick(time)
            ticks += 1
        }
        assertEquals(1100.0, input.value)
        assertFalse(input.needsRepeat)
        assertFalse(input.onTick(time + 1000L), "no repeat when the output equals the count")
    }
}
