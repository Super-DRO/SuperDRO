package org.superdro.core.ui.readout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.superdro.core.model.AxisId
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.readout.ReadoutFrame

/** The structural frames: only a new profile, workspace, instrument, hold, or projection comes through. */
class FrameStructureTest {

    private val profile = MachineProfile.create(MachineType.VerticalMill, "Mill")
    private val workspace = profile.activeWorkspace!!

    private fun frame(
        x: Double,
        current: MachineProfile = profile,
        hold: Map<AxisId, Double> = emptyMap(),
    ): ReadoutFrame = ReadoutFrame(current, current.activeWorkspace ?: workspace, mapOf(AxisId.X to x), hold = hold)

    @Test
    fun aMoveKeepsTheStructure() {
        assertTrue(frame(1.0).sameStructure(frame(2.0)))
        assertFalse(frame(1.0).sameStructure(frame(1.0, hold = mapOf(AxisId.X to 1.0))))
        val renamed = profile.copy(workspaces = profile.workspaces.map { item -> item.copy(name = "Other") })
        assertFalse(frame(1.0).sameStructure(frame(1.0, current = renamed)))
        // Equal objects that are not the same object give the same structure.
        assertTrue(frame(1.0).sameStructure(frame(1.0, current = profile.copy())))
    }

    @Test
    fun theFlowGivesOnlyTheChangesOfTheStructure() = runTest {
        val frames = MutableStateFlow<ReadoutFrame?>(null)
        val seen = mutableListOf<ReadoutFrame?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { frames.structure().toList(seen) }
        val first = frame(1.0)
        frames.value = first
        frames.value = frame(2.0)
        frames.value = frame(3.0)
        val renamed = profile.copy(name = "Lathe")
        val changed = frame(4.0, current = renamed)
        frames.value = changed
        frames.value = frame(5.0, current = renamed)
        frames.value = null
        assertEquals(4, seen.size)
        assertEquals(null, seen[0])
        assertSame(first, seen[1])
        assertSame(changed, seen[2])
        assertEquals(null, seen[3])
    }
}
