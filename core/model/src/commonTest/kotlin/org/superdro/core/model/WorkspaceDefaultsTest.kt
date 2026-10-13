package org.superdro.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The defaults of a new workspace and of the axis state. */
class WorkspaceDefaultsTest {

    @Test
    fun aNewProfileHasOneDefaultWorkspace() {
        val profile = MachineProfile.create()
        assertEquals(listOf("Default Workspace"), profile.workspaces.map { workspace -> workspace.name })
        assertEquals(profile.workspaces.single().id, profile.activeWorkspaceId)
        assertEquals(profile.workspaces.single(), profile.activeWorkspace)
    }

    @Test
    fun aLatheWorkspaceHasTheZxPlaneAndTheXDiameterMode() {
        val workspace = MachineProfile.create(MachineType.Lathe).workspaces.single()
        assertEquals(Plane.ZX, workspace.plane)
        assertTrue(workspace.axisStates.getValue(AxisId.X).diameterMode)
        assertFalse(workspace.axisStates.getValue(AxisId.Z).diameterMode)
    }

    @Test
    fun aMillWorkspaceHasTheXyPlane() {
        val workspace = MachineProfile.create().workspaces.single()
        assertEquals(Plane.XY, workspace.plane)
        assertFalse(workspace.axisStates.getValue(AxisId.X).diameterMode)
    }

    @Test
    fun anAxisStateHasNoOffsetsAndTheMetricDefaultOfTheProfile() {
        val state = AxisState()
        assertEquals(0.0, state.absoluteOffset)
        assertEquals(0.0, state.incrementalOffset)
        assertFalse(state.relative)
        assertEquals(0.0, state.toolOffset)
        assertFalse(state.toolOffsetActive)
        assertEquals(null, state.metric)
        assertFalse(state.divisionUnits)
        assertEquals(null, state.divisionsPerRev)
        assertFalse(state.surfaceSpeedMode)
    }

    @Test
    fun eachAxisHasAState() {
        assertEquals(AxisId.entries.toSet(), Workspace().axisStates.keys)
    }

    @Test
    fun aNewIdIsAUuid() {
        val id = newId()
        assertEquals(36, id.length)
        assertTrue(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(id))
    }
}
