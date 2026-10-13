package org.superdro.core.readout.operations

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.superdro.core.model.AxisAlignment
import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.MachineMode
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.SubDatum
import org.superdro.core.model.Workspace
import org.superdro.core.readout.Fixtures
import org.superdro.core.readout.ReadoutFrame
import org.superdro.core.readout.assertNear

/** The axis operations. */
class AxisOperationsTest {

    private fun frame(
        profile: MachineProfile = Fixtures.mill(),
        inputs: Map<AxisId, Double?>,
        states: Map<AxisId, AxisState> = emptyMap(),
        change: (Workspace) -> Workspace = { it },
    ): ReadoutFrame {
        val base = Fixtures.workspace(profile)
        return ReadoutFrame(profile, change(base.copy(axisStates = base.axisStates + states)), inputs)
    }

    private fun OperationResult.frame(from: ReadoutFrame, inputs: Map<AxisId, Double?> = from.inputs): ReadoutFrame =
        ReadoutFrame(from.profile, workspace, inputs, from.instrument, hold, from.projectedAxes)

    private fun OperationResult.state(axis: AxisId): AxisState = workspace.axisStates.getValue(axis)

    private val numbers: (String) -> Result<Double> = { text ->
        text.trim().toDoubleOrNull()?.let { value -> Result.success(value) } ?: Result.failure(IllegalArgumentException(text))
    }

    @Test
    fun theIncrementalZeroExample() {
        val start = frame(inputs = mapOf(AxisId.X to 40000.0))
        val result = AxisOperations.setIncremental(start, AxisId.X, 0.0)
        assertEquals(-40000.0, result.state(AxisId.X).incrementalOffset)
        assertTrue(result.state(AxisId.X).relative)
        val moved = result.frame(start, mapOf(AxisId.X to 41000.0))
        assertEquals(1000.0, moved.reading(AxisId.X).shown)
        assertEquals("0.0394", moved.reading(AxisId.X).text)
    }

    @Test
    fun theAbsoluteValueSetsTheAbsoluteOffset() {
        val start = frame(Fixtures.lathe(), mapOf(AxisId.X to 10000.0), mapOf(AxisId.X to AxisState(diameterMode = true, toolOffset = 500.0, toolOffsetActive = true)))
        // A = post.c(V) − (R + T) = 31750 / 2 − 10500.
        val result = AxisOperations.setAbsolute(start, AxisId.X, 31750.0)
        assertEquals(15875.0 - 10500.0, result.state(AxisId.X).absoluteOffset)
        assertEquals(31750.0, result.frame(start).reading(AxisId.X).shown)
    }

    @Test
    fun setTheAxisInAbsoluteModeDoesNothingForADisabledAxis() {
        val mill = Fixtures.mill()
        val disabledX = mill.copy(x = mill.x.copy(enabled = false))
        val start = frame(disabledX, mapOf(AxisId.X to 1000.0), mapOf(AxisId.X to AxisState(relative = true)))
        val result = AxisOperations.setAxis(start, AxisId.X, 0.0)
        assertNull(result.undo)
        val enabled = AxisOperations.setAxis(frame(mill, mapOf(AxisId.X to 1000.0), mapOf(AxisId.X to AxisState(relative = true))), AxisId.X, 0.0)
        assertEquals(-1000.0, enabled.state(AxisId.X).absoluteOffset)
        assertFalse(enabled.state(AxisId.X).relative)
    }

    @Test
    fun anIncrementalSetOnAnAxisOfTheActiveSubDatumMakesItNotActive() {
        val point = SubDatum(id = "s1", x = 100.0, y = 200.0)
        val start = frame(inputs = mapOf(AxisId.X to 1000.0, AxisId.Y to 1000.0, AxisId.Z to 0.0)) {
            it.copy(subDatums = listOf(point), activeSubDatumId = "s1")
        }
        assertNull(AxisOperations.setIncremental(start, AxisId.X, 0.0).workspace.activeSubDatumId)
        assertEquals("s1", AxisOperations.setIncremental(start, AxisId.Z, 0.0).workspace.activeSubDatumId, "the point has no Z")
    }

    @Test
    fun theClearOperationsAndTheToggleKeepTheOldIncrementalOffset() {
        val start = frame(inputs = mapOf(AxisId.X to 1000.0), states = mapOf(AxisId.X to AxisState(absoluteOffset = 50.0, incrementalOffset = -300.0, relative = true)))
        assertEquals(0.0, AxisOperations.clearAbsolute(start, AxisId.X).state(AxisId.X).absoluteOffset)
        assertEquals(0.0, AxisOperations.clearIncremental(start, AxisId.X).state(AxisId.X).incrementalOffset)
        val absolute = AxisOperations.toggleMode(start, AxisId.X)
        assertFalse(absolute.state(AxisId.X).relative)
        val again = AxisOperations.toggleMode(absolute.frame(start), AxisId.X)
        assertEquals(1000.0 + 50.0 - 300.0, again.frame(start).reading(AxisId.X).shown, "the old incremental value comes back")
    }

    @Test
    fun theHalfInIncrementalModeAndInAbsoluteMode() {
        val start = frame(inputs = mapOf(AxisId.X to 30000.0))
        val half = AxisOperations.half(start, AxisId.X)
        assertTrue(half.state(AxisId.X).relative)
        assertEquals(15000.0, half.frame(start).reading(AxisId.X).shown)
        val shortPress = Fixtures.mill().let { mill -> mill.copy(display = mill.display.copy(shortPressZero = true)) }
        val absoluteStart = frame(shortPress, mapOf(AxisId.X to 30000.0))
        val absoluteHalf = AxisOperations.half(absoluteStart, AxisId.X)
        assertFalse(absoluteHalf.state(AxisId.X).relative)
        assertEquals(-15000.0, absoluteHalf.state(AxisId.X).absoluteOffset)
        assertEquals(15000.0, absoluteHalf.frame(absoluteStart).reading(AxisId.X).shown)
    }

    @Test
    fun theCenterSetsTheAbsoluteValueHalfTheShownValue() {
        val start = frame(inputs = mapOf(AxisId.X to 30000.0), states = mapOf(AxisId.X to AxisState(relative = true, incrementalOffset = -10000.0)))
        val center = AxisOperations.center(start, AxisId.X)
        assertFalse(center.state(AxisId.X).relative)
        assertEquals(10000.0, center.frame(start).reading(AxisId.X).shown)
    }

    @Test
    fun theHoldExample() {
        val profile = Fixtures.mill(metric = true)
        val start = frame(profile, mapOf(AxisId.X to 25000.0))
        val held = AxisOperations.holdTap(start, AxisId.X)
        assertNull(held.undo)
        val moved = held.frame(start, mapOf(AxisId.X to 30000.0))
        assertEquals("25.000", moved.reading(AxisId.X).text)
        val set = AxisOperations.holdTap(moved, AxisId.X)
        assertEquals(emptyMap(), set.hold)
        val after = set.frame(moved)
        assertEquals("25.000", after.reading(AxisId.X).text)
        assertEquals(30000.0, after.reading(AxisId.X).r)
    }

    @Test
    fun theHoldLongPressStopsTheHoldWithNoOffsetChange() {
        val start = frame(inputs = mapOf(AxisId.X to 25000.0))
        val held = AxisOperations.holdTap(start, AxisId.X)
        val cancelled = AxisOperations.holdLongPress(held.frame(start, mapOf(AxisId.X to 1000.0)), AxisId.X)
        assertEquals(emptyMap(), cancelled.hold)
        assertNull(cancelled.undo)
        assertNull(AxisOperations.holdTap(frame(inputs = mapOf(AxisId.X to null)), AxisId.X).hold[AxisId.X], "the first tap needs a finite value")
    }

    @Test
    fun theKeypadRelativeEntries() {
        val start = frame(inputs = mapOf(AxisId.X to 31750.0))
        assertEquals("1.2500", start.reading(AxisId.X).text)
        val plus = AxisOperations.keypadText(start, AxisId.X, "++0.5", numbers).getOrThrow()
        assertEquals(44450.0, plus.frame(start).reading(AxisId.X).absolute)
        assertEquals(44450.0 - 31750.0, plus.state(AxisId.X).absoluteOffset)
        assertFalse(plus.state(AxisId.X).relative)
        val minus = AxisOperations.keypadText(start, AxisId.X, "--0.25", numbers).getOrThrow()
        assertEquals(25400.0, minus.frame(start).reading(AxisId.X).absolute)
        assertTrue(AxisOperations.keypadText(start, AxisId.X, "abc", numbers).isFailure)
        assertEquals(1.75, KeypadEntry.resolve(" ++0.5 ", 1.25, numbers).getOrThrow())
    }

    @Test
    fun theKeypadValueIsIncrementalInIncrementalMode() {
        val start = frame(inputs = mapOf(AxisId.X to 1000.0), states = mapOf(AxisId.X to AxisState(relative = true)))
        val result = AxisOperations.keypadValue(start, AxisId.X, 2540.0)
        assertEquals(1540.0, result.state(AxisId.X).incrementalOffset)
        assertEquals(0.0, result.state(AxisId.X).absoluteOffset)
    }

    @Test
    fun theUndoRestoresAllOffsetsThatTheActionChanged() {
        val point = SubDatum(id = "s1", x = 100.0)
        val start = frame(inputs = mapOf(AxisId.X to 1000.0, AxisId.Y to 2000.0, AxisId.Z to 3000.0)) {
            it.copy(subDatums = listOf(point), activeSubDatumId = "s1")
        }
        val result = ZeroButtons.globalTap(start)
        val undo = result.undo!!
        assertEquals(setOf(AxisId.X, AxisId.Y, AxisId.Z), undo.axisStates.keys)
        assertTrue(undo.restoresSubDatum)
        val restored = undo.restore(result.workspace)
        assertEquals("s1", restored.activeSubDatumId)
        listOf(AxisId.X, AxisId.Y, AxisId.Z).forEach { axis ->
            assertEquals(start.state(axis), restored.axisStates.getValue(axis), "$axis")
        }
    }

    @Test
    fun anAbsoluteSetWithTheAlignmentChangesXAndYInTheMachineFrame() {
        val profile = Fixtures.mill().copy(machineMode = MachineMode.Measurement)
        val start = frame(profile, mapOf(AxisId.X to 100000.0, AxisId.Y to 0.0)) {
            it.copy(axisAlignment = AxisAlignment(active = true, angleArcSec = 2.0 * 3600.0))
        }
        val shownY = start.reading(AxisId.Y).absolute
        val result = AxisOperations.setAbsolute(start, AxisId.X, 0.0)
        assertTrue(result.state(AxisId.X).absoluteOffset != 0.0)
        assertTrue(result.state(AxisId.Y).absoluteOffset != 0.0)
        val after = result.frame(start)
        assertNear(0.0, after.reading(AxisId.X).absolute, 1e-6)
        assertNear(shownY, after.reading(AxisId.Y).absolute, 1e-6)
        val cleared = AxisOperations.clearAbsolute(after, AxisId.X)
        assertEquals(0.0, cleared.state(AxisId.Y).absoluteOffset)
    }
}
