package org.superdro.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The projection state and the encoder offsets of the workspace. */
class ProjectionsDefaultsTest {

    @Test
    fun aNewWorkspaceHasNoProjection() {
        val workspace = Workspace()
        assertFalse(workspace.compoundProjection.active)
        assertTrue(workspace.compoundProjection.applyX)
        assertTrue(workspace.compoundProjection.applyZ)
        assertFalse(workspace.headTilt.active)
        assertTrue(workspace.headTilt.applyX)
        assertTrue(workspace.headTilt.applyY)
        assertEquals(0.0, workspace.partRotation.encoderOffsetArcSec)
        assertEquals(0.0, workspace.axisAlignment.encoderOffsetArcSec)
    }

    @Test
    fun theNewFieldsGoToJsonAndBack() {
        val workspace = Workspace(
            id = "w1",
            compoundProjection = CompoundProjection(active = true, angleArcSec = 108000.0, applyX = false),
            headTilt = HeadTilt(active = true, tiltArcSec = 36000.0, nodArcSec = 18000.0, applyY = false),
            partRotation = PartRotation(active = true, linkedToAngularEncoder = true, encoderOffsetArcSec = 3600.0),
            axisAlignment = AxisAlignment(active = true, source = AxisAlignmentSource.AngularEncoder, encoderOffsetArcSec = -7200.0),
        )
        val text = SuperDroJson.encodeToString(Workspace.serializer(), workspace)
        assertEquals(workspace, SuperDroJson.decodeFromString(Workspace.serializer(), text))
    }

    @Test
    fun anOldWorkspaceWithoutTheNewFieldsLoads() {
        val old = """{"id":"w1","name":"Old","partRotation":{"active":true,"angleArcSec":10.0}}"""
        val workspace = SuperDroJson.decodeFromString(Workspace.serializer(), old)
        assertEquals(CompoundProjection(), workspace.compoundProjection)
        assertEquals(HeadTilt(), workspace.headTilt)
        assertEquals(0.0, workspace.partRotation.encoderOffsetArcSec)
    }
}
