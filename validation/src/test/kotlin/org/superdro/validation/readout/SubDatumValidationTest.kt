package org.superdro.validation.readout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.superdro.core.model.AxisId
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.SubDatum
import org.superdro.core.model.Workspace
import org.superdro.core.readout.ReadoutFrame
import org.superdro.core.readout.operations.SubDatumOperations

/**
 * The activation of a sub-datum and "Set origin at a sub-datum".
 */
class SubDatumValidationTest {

    private fun withPoint(profile: MachineProfile, point: SubDatum, change: (Workspace) -> Workspace = { it }): ReadoutFrame {
        val workspace = change(profile.workspace.copy(subDatums = listOf(point)))
        return ReadoutFrame(profile, workspace, mapOf(AxisId.X to 30000.0, AxisId.Y to 12000.0, AxisId.Z to 5000.0, AxisId.Angular to 7200.0))
    }

    @Test
    fun sub_datum_X_25400_abs_30000_shows_4600_um_0_1811() {
        val point = SubDatum(name = "Point 1", x = 25400.0)
        val start = withPoint(mill(), point)
        val result = SubDatumOperations.activate(start, point.id)
        val reading = start.with(result.workspace).reading(AxisId.X)
        assertEquals(4600.0, reading.shown)
        assertEquals("0.1811", reading.text)
        assertTrue(reading.incremental)
        assertEquals(-25400.0, reading.subDatumOffset)
    }

    @Test
    fun activation_sets_rel_and_I_is_not_in_use() {
        val point = SubDatum(name = "Point 1", x = 25400.0, y = 2000.0)
        val start = withPoint(mill(), point) { workspace ->
            workspace.axis(AxisId.X) { state -> state.copy(incrementalOffset = -999.0) }
        }
        val activated = start.with(SubDatumOperations.activate(start, point.id).workspace)
        assertTrue(activated.state(AxisId.X).relative)
        assertTrue(activated.state(AxisId.Y).relative)
        assertEquals(4600.0, activated.reading(AxisId.X).shown)
        assertEquals(10000.0, activated.reading(AxisId.Y).shown)
    }

    @Test
    fun axis_with_no_coordinate_gets_no_sub_datum_offset() {
        val point = SubDatum(name = "Point 1", x = 25400.0)
        val start = withPoint(mill(), point) { workspace ->
            workspace.axis(AxisId.Y) { state -> state.copy(relative = true, incrementalOffset = -2000.0) }
        }
        val activated = start.with(SubDatumOperations.activate(start, point.id).workspace)
        assertNull(activated.reading(AxisId.Y).subDatumOffset)
        // Y shows abs + I in the incremental mode.
        assertEquals(10000.0, activated.reading(AxisId.Y).shown)
    }

    @Test
    fun Z_coordinate_applies_on_a_mill_only_with_3D_sub_datums_on() {
        val point = SubDatum(name = "Point 1", x = 25400.0, z = 1000.0)
        val on = withPoint(mill(), point)
        assertTrue(mill().use3dSubDatums)
        val onActivated = on.with(SubDatumOperations.activate(on, point.id).workspace)
        assertEquals(4000.0, onActivated.reading(AxisId.Z).shown)
        assertTrue(onActivated.state(AxisId.Z).relative)

        val off = withPoint(mill().copy(use3dSubDatums = false), point)
        val offActivated = off.with(SubDatumOperations.activate(off, point.id).workspace)
        assertNull(offActivated.reading(AxisId.Z).subDatumOffset)
        assertFalse(offActivated.state(AxisId.Z).relative)
        assertEquals(5000.0, offActivated.reading(AxisId.Z).shown)
    }

    @Test
    fun lathe_always_uses_the_Z_coordinate() {
        val point = SubDatum(name = "Point 1", x = 1000.0, z = 1000.0)
        val start = withPoint(lathe().copy(use3dSubDatums = false), point)
        val activated = start.with(SubDatumOperations.activate(start, point.id).workspace)
        assertEquals(4000.0, activated.reading(AxisId.Z).shown)
    }

    @Test
    fun angular_coordinate_applies() {
        val point = SubDatum(name = "Point 1", a = 3600.0)
        val profile = mill().let { base -> base.copy(angular = base.angular.copy(enabled = true)) }
        val start = withPoint(profile, point)
        val activated = start.with(SubDatumOperations.activate(start, point.id).workspace)
        assertEquals(3600.0, activated.reading(AxisId.Angular).shown)
        assertEquals("1.00", activated.reading(AxisId.Angular).text)
    }

    @Test
    fun not_active_again_restores_I_in_incremental_mode() {
        val point = SubDatum(name = "Point 1", x = 25400.0)
        val start = withPoint(mill(), point) { workspace ->
            workspace.axis(AxisId.X) { state -> state.copy(incrementalOffset = -1000.0) }
        }
        val activated = start.with(SubDatumOperations.activate(start, point.id).workspace)
        val cleared = activated.with(SubDatumOperations.deactivate(activated).workspace)
        assertNull(cleared.workspace.activeSubDatumId)
        assertEquals(29000.0, cleared.reading(AxisId.X).shown)
    }

    @Test
    fun set_origin_at_a_sub_datum_moves_the_absolute_origin_to_the_point() {
        val point = SubDatum(name = "Point 1", x = 25400.0, y = 2000.0, z = 1000.0)
        val start = withPoint(mill(), point) { workspace -> workspace.axis(AxisId.X) { state -> state.copy(relative = true) } }
        val origin = start.with(SubDatumOperations.setOriginAt(start, point.id).workspace)
        assertEquals(4600.0, origin.reading(AxisId.X).absolute, 1e-9)
        assertEquals(10000.0, origin.reading(AxisId.Y).absolute, 1e-9)
        assertEquals(4000.0, origin.reading(AxisId.Z).absolute, 1e-9)
        listOf(AxisId.X, AxisId.Y, AxisId.Z).forEach { axis -> assertFalse(origin.state(axis).relative, "axis $axis") }
        // At the point, the absolute position reads 0.
        val atPoint = ReadoutFrame(start.profile, origin.workspace, mapOf(AxisId.X to 25400.0, AxisId.Y to 2000.0, AxisId.Z to 1000.0))
        assertEquals(0.0, atPoint.reading(AxisId.X).absolute, 1e-9)
        assertEquals(0.0, atPoint.reading(AxisId.Y).absolute, 1e-9)
        assertEquals(0.0, atPoint.reading(AxisId.Z).absolute, 1e-9)
    }

    @Test
    fun set_origin_on_a_lathe_X_in_diameter_mode_moves_the_origin_to_the_point() {
        // A stored lathe X is a radius.
        val point = SubDatum(name = "Point 1", x = 5000.0, z = 1000.0)
        val start = withPoint(lathe(), point)
        val origin = SubDatumOperations.setOriginAt(start, point.id).workspace
        val atPoint = ReadoutFrame(start.profile, origin, mapOf(AxisId.X to 5000.0, AxisId.Z to 1000.0))
        assertEquals(0.0, atPoint.reading(AxisId.X).absolute, 1e-9)
        assertEquals(0.0, atPoint.reading(AxisId.X).shown, 1e-9)
        assertEquals(0.0, atPoint.reading(AxisId.Z).absolute, 1e-9)
    }

    @Test
    fun set_origin_changes_only_the_enabled_axes() {
        val point = SubDatum(name = "Point 1", x = 25400.0, y = 2000.0, a = 3600.0)
        val base = mill()
        val profile = base.copy(y = base.y.copy(enabled = false))
        val start = withPoint(profile, point)
        val result = SubDatumOperations.setOriginAt(start, point.id).workspace
        assertEquals(-25400.0, result.axisStates.getValue(AxisId.X).absoluteOffset, 1e-9)
        assertEquals(0.0, result.axisStates.getValue(AxisId.Y).absoluteOffset)
        assertEquals(0.0, result.axisStates.getValue(AxisId.Angular).absoluteOffset)
    }
}
