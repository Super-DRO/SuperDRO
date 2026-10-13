package org.superdro.core.functions.proximity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.superdro.core.model.MachineMode

/** The proximity warning. */
class ProximityWarningTest {

    private fun after(vararg values: Double, target: Double = 0.0): ProximityState =
        values.foldIndexed(ProximityWarning.start(target)) { index, state, value -> state.update(value, index * 100L) }

    @Test
    fun aDistanceOf1000UmWhileTheAxisMovesCloserGivesTheLevel065() {
        val state = after(1500.0, 1000.0)
        assertEquals(0.65, state.level)
        assertEquals(0.65, ProximityWarning.approachLevel(1000.0))
        assertFalse(state.passed)
    }

    @Test
    fun theValueAtTheTargetOrAcrossItGivesLevel0AndPassed() {
        val at = after(200.0, 0.0)
        assertEquals(0.0, at.level)
        assertTrue(at.passed)
        val across = after(100.0, -50.0)
        assertEquals(0.0, across.level)
        assertTrue(across.passed)
    }

    @Test
    fun aMotionAwayFromTheTargetGivesLevel0() {
        assertEquals(0.0, after(1500.0, 1000.0, 1200.0).level)
    }

    @Test
    fun afterThePassTheContactZoneIsSilent() {
        val passed = after(100.0, -50.0, -20.0)
        assertEquals(0.0, passed.level)
        assertTrue(passed.passed)
        // Out of the contact zone, a motion to the target clears the flag.
        val again = passed.update(-1000.0, 400L).update(-900.0, 500L)
        assertFalse(again.passed)
        assertEquals(ProximityWarning.approachLevel(900.0), again.level)
    }

    @Test
    fun outOfTheNearZoneIsSilentAndTheContactZoneIsContinuous() {
        assertEquals(0.0, after(3000.0, 2600.0).level)
        assertEquals(1.0, after(300.0, 127.0).level)
        assertEquals(1.0, after(127.0).level)
        assertEquals(ProximityTone.Continuous, ProximityWarning.tone(1.0))
    }

    @Test
    fun noMotionKeepsTheLevel() {
        val state = after(1500.0, 1000.0, 1000.0)
        assertEquals(0.65, state.level)
        assertEquals(0.0, after(1000.0).level)
    }

    @Test
    fun theLevelStepsOfTheNearZone() {
        assertEquals(0.95, ProximityWarning.approachLevel(128.0))
        assertEquals(0.90, ProximityWarning.approachLevel(127.0 + 127.0))
        assertEquals(0.05, ProximityWarning.approachLevel(2539.0))
        assertEquals(0.45, ProximityWarning.approachLevel(1397.0))
    }

    @Test
    fun theBeepPeriod() {
        assertEquals(100.0 / 0.65, ProximityWarning.beepPeriodMs(0.65))
        assertEquals(2000.0, ProximityWarning.beepPeriodMs(0.05))
        assertEquals(100.0 / 0.999, ProximityWarning.beepPeriodMs(0.999))
        assertEquals(ProximityTone.Beep(100.0 / 0.45), ProximityWarning.tone(0.45))
        assertEquals(ProximityTone.Off, ProximityWarning.tone(0.0))
    }

    @Test
    fun theWarningIsSilentAfter5000MsWithNoValue() {
        val state = after(1500.0, 1000.0)
        assertEquals(0.65, state.levelAt(100L + 4999L))
        assertEquals(0.0, state.levelAt(100L + 5000L))
        assertEquals(ProximityTone.Off, state.tone(100L + 5000L))
        assertEquals(ProximityTone.Beep(100.0 / 0.65), state.tone(200L))
        assertEquals(0.0, ProximityWarning.start(0.0).levelAt(0L))
    }

    @Test
    fun theMeasurementModeStopsTheWarning() {
        val state = after(300.0, 100.0)
        assertEquals(ProximityTone.Continuous, state.tone(200L, MachineMode.Cutting))
        assertEquals(ProximityTone.Off, state.tone(200L, MachineMode.Measurement))
    }

    @Test
    fun theTargetCanBeANonZeroValue() {
        val state = after(11500.0, 11000.0, target = 10000.0)
        assertEquals(0.65, state.level)
        assertEquals(800, ProximityWarning.TONE_HZ)
    }
}
