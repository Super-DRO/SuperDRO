package org.superdro.core.ui.readout

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.superdro.core.model.AxisButtonMode
import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.TachometerConfig
import org.superdro.core.model.TachometerMode
import org.superdro.core.readout.input.AxisValue
import org.superdro.core.readout.input.AxisValueStatus
import org.superdro.core.readout.input.InputSnapshot
import org.superdro.core.ui.graphics.proximity.ProximityTargets

/**
 * The two paths of the readout presenter: a new position changes only the live values (the frame,
 * the axis values, and the RPM text), and an action at a press uses the newest inputs.
 */
class ReadoutLivePathTest {

    private val scope = TestScope()
    private val harness = ReadoutHarness(scope)

    @AfterTest
    fun close() = harness.close()

    private fun snapshot(vararg values: Pair<AxisId, Double>, timeMs: Long = 0L) =
        InputSnapshot(axes = values.toMap().mapValues { (axis, value) -> AxisValue(axis, value, timeMs, AxisValueStatus.Ok) })

    @Test
    fun aNewPositionChangesTheAxisValueAndNotTheUiState() = scope.runTest {
        harness.start(states = mapOf(AxisId.X to AxisState(metric = true)))
        harness.inputs(AxisId.X to 1_000.0, AxisId.Y to 5_000.0)
        val ui = harness.presenter.uiState.value
        val y = harness.presenter.axisValue(AxisId.Y).value
        repeat(20) { index -> harness.inputs(AxisId.X to 2_000.0 + index * 10.0, AxisId.Y to 5_000.0) }
        assertEquals("2.190", harness.text(AxisId.X))
        assertSame(ui, harness.presenter.uiState.value, "the main UI state does not change for a new position")
        assertSame(y, harness.presenter.axisValue(AxisId.Y).value, "the value of an axis that did not move does not change")
        // The frame and the axis values come from one set of inputs.
        assertEquals(harness.presenter.frame.value!!.reading(AxisId.X).text, harness.text(AxisId.X))
    }

    @Test
    fun aSnapshotWithOnlyANewTimeGivesNoNewFrame() = scope.runTest {
        harness.start()
        harness.session.inputs.value = snapshot(AxisId.X to 1_000.0, timeMs = 10L)
        advanceUntilIdle()
        val frame = harness.presenter.frame.value
        harness.session.inputs.value = snapshot(AxisId.X to 1_000.0, timeMs = 20L)
        advanceUntilIdle()
        assertSame(frame, harness.presenter.frame.value)
        harness.session.inputs.value = snapshot(AxisId.X to 1_001.0, timeMs = 30L)
        advanceUntilIdle()
        assertNotSame(frame, harness.presenter.frame.value)
    }

    @Test
    fun aChangeBelowTheDisplayResolutionGivesNoNewAxisValue() = scope.runTest {
        harness.start(states = mapOf(AxisId.X to AxisState(metric = true)))
        harness.inputs(AxisId.X to 1_000.0)
        val value = harness.presenter.axisValue(AxisId.X).value
        assertEquals(1_000.0, value.positionUm)
        // 0.3 µm is less than the last digit of "1.000" mm.
        harness.inputs(AxisId.X to 1_000.3)
        assertSame(value, harness.presenter.axisValue(AxisId.X).value)
        harness.inputs(AxisId.X to 1_001.0)
        assertEquals(1_001.0, harness.presenter.axisValue(AxisId.X).value.positionUm)
    }

    @Test
    fun zeroUsesTheNewestInputsOfTheSession() = scope.runTest {
        harness.start(MachineProfile.create().copy(metricDefault = true))
        harness.inputs(AxisId.X to 10_000.0)
        // The session has a newer position that it did not publish yet.
        harness.session.unpublished = snapshot(AxisId.X to 12_000.0)
        assertEquals(12_000.0, harness.presenter.latestFrame()!!.reading(AxisId.X).r)
        harness.presenter.onActionLongPress(AxisId.X, AxisButtonMode.SetZero)
        advanceUntilIdle()
        assertEquals(-12_000.0, harness.axisState(AxisId.X).absoluteOffset)
        harness.session.unpublished = null
        harness.inputs(AxisId.X to 12_000.0)
        assertEquals("0.000", harness.text(AxisId.X))
    }

    @Test
    fun holdUsesTheNewestInputsOfTheSession() = scope.runTest {
        harness.start(states = mapOf(AxisId.X to AxisState(metric = true)))
        harness.inputs(AxisId.X to 25_000.0)
        harness.session.unpublished = snapshot(AxisId.X to 25_500.0)
        harness.presenter.onActionTap(AxisId.X, AxisButtonMode.Hold)
        advanceUntilIdle()
        assertEquals(ValueState.Held, harness.presenter.axisValue(AxisId.X).value.state)
        assertEquals("25.500", harness.text(AxisId.X))
    }

    @Test
    fun theRpmTextChangesWithNoNewUiState() = scope.runTest {
        harness.start(MachineProfile.create().copy(tachometer = TachometerConfig(enabled = true, mode = TachometerMode.Sensor)))
        harness.inputs(AxisId.Tachometer to 1_000.0)
        val ui = harness.presenter.uiState.value
        assertEquals("1000", harness.presenter.rpmText.value)
        harness.inputs(AxisId.Tachometer to 1_200.0)
        assertEquals("1200", harness.presenter.rpmText.value)
        assertSame(ui, harness.presenter.uiState.value)
    }

    @Test
    fun theProximityFlagFollowsTheWorkspace() = scope.runTest {
        harness.start()
        assertFalse(AxisId.X in harness.presenter.proximityAxes.value)
        harness.presenter.applyWorkspace(null) { workspace -> ProximityTargets.toggle(workspace, AxisId.X) }
        advanceUntilIdle()
        assertTrue(AxisId.X in harness.presenter.proximityAxes.value)
        assertFalse(AxisId.Y in harness.presenter.proximityAxes.value)
    }
}
