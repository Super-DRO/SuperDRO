package org.superdro.core.ui.settings.keys

import androidx.compose.ui.input.key.Key
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.SessionState
import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.KeyAction
import org.superdro.core.model.KeyMapping
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.SubDatum
import org.superdro.core.ui.navigation.KeypadRoute
import org.superdro.core.ui.navigation.TabNavigator
import org.superdro.core.ui.navigation.TabRoute
import org.superdro.core.ui.readout.ReadoutHarness

/** A [TabNavigator] that keeps each push. */
class RecordingNavigator(override val deviceId: DeviceId = DeviceId("tab")) : TabNavigator {
    val pushed = mutableListOf<TabRoute>()
    override fun push(route: TabRoute) {
        pushed += route
    }
    override fun pop() = Unit
    override fun pop(route: TabRoute) = Unit
    override fun replaceTop(route: TabRoute) = Unit
    override fun popToRoot() = Unit
}

/** A [KeyActionTarget] that keeps each action. */
class RecordingTarget(private val answer: Boolean = true) : KeyActionTarget {
    val runs = mutableListOf<Pair<KeyAction, AxisId?>>()
    override fun run(action: KeyAction, axis: AxisId?): Boolean {
        runs += action to axis
        return answer
    }
}

/** The runtime handler of the hardware keys: the dispatch and the actions on the readout. */
class HardwareKeysTest {

    private val scope = TestScope()
    private val harness = ReadoutHarness(scope)
    private val navigator = RecordingNavigator()

    @AfterTest
    fun close() = harness.close()

    @Test
    fun aHeldKeyRunsItsActionOneTime() {
        val target = RecordingTarget()
        val map = KeyMaps.default()
        val filter = KeyRepeatFilter()
        val code = Key.NumPad2.keyCode
        // The first key down runs the action. The repeats of the held key (key downs with no key
        // up) give the same answer and run nothing.
        assertTrue(KeyDispatch.dispatchDown(map, code, target, filter))
        assertTrue(KeyDispatch.dispatchDown(map, code, target, filter))
        assertTrue(KeyDispatch.dispatchDown(map, code, target, filter))
        assertEquals(listOf<Pair<KeyAction, AxisId?>>(KeyAction.ZeroAxis to AxisId.Y), target.runs)
        // After the key up, the next press runs the action again.
        filter.onKeyUp(code)
        assertTrue(KeyDispatch.dispatchDown(map, code, target, filter))
        assertEquals(2, target.runs.size)
        // A key with no mapping goes on, also while it repeats.
        assertFalse(KeyDispatch.dispatchDown(map, Key.A.keyCode, target, filter))
        assertFalse(KeyDispatch.dispatchDown(map, Key.A.keyCode, target, filter))
        // A loss of the focus gives no key up: the filter forgets the keys.
        filter.clear()
        assertTrue(KeyDispatch.dispatchDown(map, code, target, filter))
        assertEquals(3, target.runs.size)
    }

    @Test
    fun aRefusedKeyKeepsItsAnswerWhileItRepeats() {
        val target = RecordingTarget(answer = false)
        val filter = KeyRepeatFilter()
        val code = Key.NumPad2.keyCode
        assertFalse(KeyDispatch.dispatchDown(KeyMaps.default(), code, target, filter))
        assertFalse(KeyDispatch.dispatchDown(KeyMaps.default(), code, target, filter))
        assertEquals(1, target.runs.size)
    }

    @Test
    fun aMappedKeyRunsItsActionAndAnUnknownKeyGoesOn() {
        val target = RecordingTarget()
        val map = KeyMaps.default()
        assertTrue(KeyDispatch.dispatch(map, Key.NumPad2.keyCode, target))
        assertTrue(KeyDispatch.dispatch(map, Key.PageDown.keyCode, target))
        assertFalse(KeyDispatch.dispatch(map, Key.A.keyCode, target), "a key with no mapping goes on to a text field")
        assertEquals(listOf(KeyAction.ZeroAxis to AxisId.Y, KeyAction.NextSubDatum to null), target.runs)
    }

    @Test
    fun anAxisActionWithNoStoredAxisUsesX() {
        val target = RecordingTarget()
        KeyDispatch.dispatch(listOf(KeyMapping(Key.F2.keyCode, action = KeyAction.HoldAxis)), Key.F2.keyCode, target)
        assertEquals(listOf<Pair<KeyAction, AxisId?>>(KeyAction.HoldAxis to AxisId.X), target.runs)
    }

    @Test
    fun numOneZerosXOnTheReadoutOfTheTab() = scope.runTest {
        harness.start(states = mapOf(AxisId.X to AxisState(metric = true)))
        harness.inputs(AxisId.X to 10_000.0)
        assertEquals("10.000", harness.text(AxisId.X))
        val actions = ReadoutKeyActions(harness.presenter, navigator)
        assertTrue(KeyDispatch.dispatch(KeyMaps.default(), Key.NumPad1.keyCode, actions))
        advanceUntilIdle()
        assertEquals("0.000", harness.text(AxisId.X))
        assertEquals(-10_000.0, harness.axisState(AxisId.X).incrementalOffset)
        assertTrue(harness.axisState(AxisId.X).relative)
    }

    @Test
    fun numFourOpensTheKeypadOfX() = scope.runTest {
        harness.start()
        val actions = ReadoutKeyActions(harness.presenter, navigator)
        assertTrue(KeyDispatch.dispatch(KeyMaps.default(), Key.NumPad4.keyCode, actions))
        assertEquals(listOf<TabRoute>(KeypadRoute(AxisId.X)), navigator.pushed)
    }

    @Test
    fun anActionOfAnAxisThatIsNotEnabledDoesNothing() = scope.runTest {
        // A lathe with no auxiliary readout: the Y axis is off.
        val lathe = MachineProfile.create(MachineType.Lathe).let { base -> base.copy(y = base.y.copy(enabled = false)) }
        harness.start(machine = lathe)
        val actions = ReadoutKeyActions(harness.presenter, navigator)
        assertFalse(KeyDispatch.dispatch(KeyMaps.default(), Key.NumPad5.keyCode, actions))
        assertEquals(emptyList<TabRoute>(), navigator.pushed)
    }

    @Test
    fun theNextAndThePreviousPointKeysActivateThePointsInOrderAndWrap() = scope.runTest {
        val base = MachineProfile.create(MachineType.VerticalMill, "Mill")
        val points = listOf(
            SubDatum(name = "P1", x = 1000.0, y = 0.0),
            SubDatum(name = "P2", x = 2000.0, y = 0.0),
            SubDatum(name = "P3", x = 3000.0, y = 0.0),
        )
        val workspace = base.workspaces.first().copy(subDatums = points)
        harness.start(machine = base.copy(workspaces = listOf(workspace), activeWorkspaceId = workspace.id))
        harness.inputs(AxisId.X to 0.0, AxisId.Y to 0.0, AxisId.Z to 0.0)
        val order = harness.presenter.frame.value!!.workspace.subDatums.map { it.name }
        assertEquals(listOf("P1", "P2", "P3"), order)
        val actions = ReadoutKeyActions(harness.presenter, navigator)
        val map = KeyMaps.default()
        fun active(): String? = harness.presenter.frame.value?.activeSubDatum?.name

        assertNull(active())
        KeyDispatch.dispatch(map, Key.PageDown.keyCode, actions)
        advanceUntilIdle()
        assertEquals("P1", active(), "with no active point, next gives the first point")
        KeyDispatch.dispatch(map, Key.NumPadAdd.keyCode, actions)
        advanceUntilIdle()
        assertEquals("P2", active())
        KeyDispatch.dispatch(map, Key.NumPadAdd.keyCode, actions)
        KeyDispatch.dispatch(map, Key.NumPadAdd.keyCode, actions)
        advanceUntilIdle()
        assertEquals("P1", active(), "the list wraps at the end")
        KeyDispatch.dispatch(map, Key.PageUp.keyCode, actions)
        advanceUntilIdle()
        assertEquals("P3", active(), "the list wraps at the start")
    }

    @Test
    fun theNextIndexWrapsAtEachEnd() {
        assertEquals(0, ReadoutKeyActions.nextIndex(-1, 3, forward = true))
        assertEquals(2, ReadoutKeyActions.nextIndex(-1, 3, forward = false))
        assertEquals(0, ReadoutKeyActions.nextIndex(2, 3, forward = true))
        assertEquals(2, ReadoutKeyActions.nextIndex(0, 3, forward = false))
        assertEquals(1, ReadoutKeyActions.nextIndex(0, 3, forward = true))
    }

    @Test
    fun aZeroWithNoLiveSessionChangesNoOffset() = scope.runTest {
        harness.start(state = SessionState.Disconnected)
        val actions = ReadoutKeyActions(harness.presenter, navigator)
        KeyDispatch.dispatch(KeyMaps.default(), Key.NumPad1.keyCode, actions)
        advanceUntilIdle()
        assertEquals(0.0, harness.axisState(AxisId.X).incrementalOffset)
        assertFalse(harness.axisState(AxisId.X).relative)
    }
}
