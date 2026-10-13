package org.superdro.validation.functions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.superdro.core.functions.patterns.AxisPoint
import org.superdro.core.functions.patterns.AxisRange
import org.superdro.core.functions.patterns.AxisSwitches
import org.superdro.core.functions.patterns.ArbitraryHoleCircle
import org.superdro.core.functions.patterns.EquidistantArray
import org.superdro.core.functions.patterns.HoleAngleList
import org.superdro.core.functions.patterns.HoleCircle
import org.superdro.core.functions.patterns.HoleCircleInput
import org.superdro.core.functions.patterns.HoleGrid
import org.superdro.core.functions.patterns.HoleGridInput
import org.superdro.core.functions.patterns.PatternBatch
import org.superdro.core.functions.patterns.PatternCommon
import org.superdro.core.functions.patterns.PatternGroup
import org.superdro.core.functions.patterns.PatternPlanes
import org.superdro.core.functions.patterns.PatternPrefix
import org.superdro.core.functions.patterns.PatternResult
import org.superdro.core.functions.patterns.PatternTags
import org.superdro.core.functions.patterns.PositionReference
import org.superdro.core.functions.patterns.PositionSource
import org.superdro.core.functions.patterns.PrefixResult
import org.superdro.core.functions.patterns.StepAndRepeat
import org.superdro.core.functions.PointUm
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.PatternAngleConvention
import org.superdro.core.model.Plane
import org.superdro.core.model.SubDatum
import org.superdro.core.model.Workspace
import org.superdro.validation.functions.Expect.DEG
import org.superdro.validation.functions.Expect.MM

/**
 * The hole patterns. The expected values come from the
 * worked examples.
 */
class HolePatternValidationTest {

    private fun success(result: PatternResult): PatternGroup = assertIs<PatternResult.Success>(result).group

    private fun failureTexts(result: PatternResult): List<String> = assertIs<PatternResult.Failure>(result).errors.map { it.text }

    private fun assertPoints(expectedMm: List<Pair<Double, Double>>, actual: List<AxisPoint>, plane: Plane = Plane.XY) {
        assertEquals(expectedMm.size, actual.size, "point count")
        expectedMm.forEachIndexed { index, (h, v) ->
            val point = actual[index]
            val (actualH, actualV) = when (plane) {
                Plane.XY -> point.x to point.y
                Plane.XZ, Plane.ZX -> point.x to point.z
                Plane.YZ -> point.y to point.z
            }
            Expect.mm(h, actualH, "point ${index + 1} h")
            Expect.mm(v, actualV, "point ${index + 1} v")
        }
    }

    private fun legacyCircleCommon(reference: PositionReference = PositionReference.Center) = PatternCommon(
        plane = Plane.XY,
        origin = AxisPoint(100 * MM, 50 * MM, null),
        reference = reference,
        machineType = MachineType.VerticalMill,
        convention = PatternAngleConvention.Legacy,
    )

    @Test
    fun hole_circle_legacy_six_hole_table() {
        val group = success(HoleCircle.create(legacyCircleCommon(), HoleCircleInput(50 * MM, 6, 0.0, 360 * DEG)))
        assertEquals("Circle", group.groupName)
        assertPoints(
            listOf(100.0 to 100.0, 143.301 to 75.0, 143.301 to 25.0, 100.0 to 0.0, 56.699 to 25.0, 56.699 to 75.0),
            group.points,
        )
    }

    @Test
    fun hole_circle_standard_convention_holes_1_and_2() {
        val common = legacyCircleCommon().copy(convention = PatternAngleConvention.Standard)
        val group = success(HoleCircle.create(common, HoleCircleInput(50 * MM, 6, 0.0, 360 * DEG)))
        Expect.mm(150.0, group.points[0].x, "hole 1 X")
        Expect.mm(50.0, group.points[0].y, "hole 1 Y")
        Expect.mm(125.0, group.points[1].x, "hole 2 X")
        Expect.mm(93.301, group.points[1].y, "hole 2 Y")
    }

    @Test
    fun new_mill_profile_is_standard_and_lathe_is_always_legacy() {
        assertEquals(PatternAngleConvention.Standard, MachineProfile.create(MachineType.VerticalMill).patternAngleConvention)
        assertEquals(PatternAngleConvention.Legacy, MachineProfile.create(MachineType.Lathe).patternAngleConvention)
        val lathe = PatternCommon(machineType = MachineType.Lathe, convention = PatternAngleConvention.Standard)
        assertEquals(PatternAngleConvention.Legacy, lathe.effectiveConvention)
    }

    @Test
    fun sweep_90_with_4_holes_gives_angles_0_30_60_90() {
        val angles = HoleCircle.angles(4, 0.0, 90 * DEG)
        assertEquals(listOf(0.0, 30 * DEG, 60 * DEG, 90 * DEG), angles.map { Math.round(it).toDouble() })
        val group = success(HoleCircle.create(legacyCircleCommon(), HoleCircleInput(50 * MM, 4, 0.0, 90 * DEG)))
        assertPoints(listOf(100.0 to 100.0, 125.0 to 93.301, 143.301 to 75.0, 150.0 to 50.0), group.points)
    }

    @Test
    fun first_hole_reference_puts_hole_1_at_the_origin() {
        val common = legacyCircleCommon(PositionReference.FirstPoint)
        val group = success(HoleCircle.create(common, HoleCircleInput(50 * MM, 6, 0.0, 360 * DEG)))
        Expect.mm(100.0, group.points[0].x, "hole 1 X")
        Expect.mm(50.0, group.points[0].y, "hole 1 Y")
        Expect.mm(143.301, group.points[1].x, "hole 2 X")
        Expect.mm(25.0, group.points[1].y, "hole 2 Y")
    }

    @Test
    fun sweep_minus_360_is_a_full_circle_with_no_duplicate_hole() {
        val angles = HoleCircle.angles(4, 0.0, -360 * DEG).map { Math.round(it / DEG).toInt() }
        assertEquals(listOf(0, 270, 180, 90), angles)
        assertEquals(4, angles.toSet().size, "no duplicate hole")
    }

    @Test
    fun sweep_0_is_360_and_a_negative_start_is_normalized() {
        assertEquals(HoleCircle.angles(4, 0.0, 360 * DEG), HoleCircle.angles(4, 0.0, 0.0))
        assertEquals(270.0, HoleCircle.angles(2, -90 * DEG, 360 * DEG).first() / DEG, 1e-9)
        assertEquals(10.0, HoleCircle.angles(2, 370 * DEG, 360 * DEG).first() / DEG, 1e-9)
    }

    @Test
    fun hole_circle_limits() {
        val common = legacyCircleCommon()
        assertTrue(failureTexts(HoleCircle.create(common, HoleCircleInput(50 * MM, 1))).any { "Hole Count" in it })
        assertTrue(failureTexts(HoleCircle.create(common, HoleCircleInput(50 * MM, 101))).any { "Hole Count" in it })
        assertIs<PatternResult.Success>(HoleCircle.create(common, HoleCircleInput(50 * MM, 2)))
        assertIs<PatternResult.Success>(HoleCircle.create(common, HoleCircleInput(50 * MM, 100)))
        assertTrue(failureTexts(HoleCircle.create(common, HoleCircleInput(0.0, 6))).any { "Circle Radius" in it })
        assertIs<PatternResult.Success>(HoleCircle.create(common, HoleCircleInput(0.001, 6)))
        assertTrue(failureTexts(HoleCircle.create(common, HoleCircleInput(50 * MM, 6, 361 * DEG))).any { "Start Angle" in it })
        assertTrue(failureTexts(HoleCircle.create(common, HoleCircleInput(50 * MM, 6, 0.0, -361 * DEG))).any { "Sweep Angle" in it })
    }

    @Test
    fun arbitrary_hole_circle_angles_10_100_200() {
        val list = HoleAngleList().add(10 * DEG).add(100 * DEG).add(200 * DEG)
        val common = PatternCommon(origin = AxisPoint(0.0, 0.0, null), convention = PatternAngleConvention.Legacy)
        val group = success(ArbitraryHoleCircle.create(common, 25400.0, list))
        assertPoints(listOf(4.411 to 25.014, 25.014 to -4.411, -8.687 to -23.868), group.points)
    }

    @Test
    fun angle_list_normalizes_rounds_ignores_duplicates_and_keeps_entry_order() {
        val list = HoleAngleList()
            .add(200 * DEG)
            .add(-10 * DEG)
            .add(370 * DEG)
            .add(10.00006 * DEG)
            .add(200 * DEG)
        val degrees = list.anglesArcSec.map { it / DEG }
        assertEquals(4, degrees.size)
        assertEquals(200.0, degrees[0], 1e-9)
        assertEquals(350.0, degrees[1], 1e-9)
        assertEquals(10.0, degrees[2], 1e-9)
        assertEquals(10.0001, degrees[3], 1e-9)
    }

    @Test
    fun arbitrary_hole_circle_needs_two_angles_with_the_correct_text() {
        val one = HoleAngleList().add(10 * DEG)
        val texts = failureTexts(ArbitraryHoleCircle.create(PatternCommon(), 25400.0, one))
        assertTrue("At least two hole angles are required." in texts, "texts: $texts")
    }

    @Test
    fun hole_grid_six_point_table_at_30_degrees_legacy() {
        val common = PatternCommon(origin = AxisPoint(0.0, 0.0, null), convention = PatternAngleConvention.Legacy)
        val input = HoleGridInput(repeat1 = 3, step1Um = 10 * MM, repeat2 = 2, step2Um = 20 * MM, angleArcSec = 30 * DEG)
        val group = success(HoleGrid.create(common, input))
        assertEquals("Grid", group.groupName)
        assertPoints(
            listOf(0.0 to 0.0, 8.660 to -5.0, 17.321 to -10.0, 10.0 to 17.321, 18.660 to 12.321, 27.321 to 7.321),
            group.points,
        )
    }

    @Test
    fun hole_grid_standard_convention_uses_t_plus_alpha() {
        // px = c×e×cos(α) − r×f×sin(α), py = c×e×sin(α) + r×f×cos(α) for t = +α.
        val common = PatternCommon(origin = AxisPoint(0.0, 0.0, null), convention = PatternAngleConvention.Standard)
        val input = HoleGridInput(repeat1 = 3, step1Um = 10 * MM, repeat2 = 2, step2Um = 20 * MM, angleArcSec = 30 * DEG)
        val group = success(HoleGrid.create(common, input))
        assertPoints(
            listOf(0.0 to 0.0, 8.660 to 5.0, 17.321 to 10.0, -10.0 to 17.321, -1.340 to 22.321, 7.321 to 27.321),
            group.points,
        )
    }

    @Test
    fun hole_grid_table_at_0_degrees() {
        val common = PatternCommon(origin = AxisPoint(0.0, 0.0, null), convention = PatternAngleConvention.Legacy)
        val input = HoleGridInput(repeat1 = 3, step1Um = 10 * MM, repeat2 = 2, step2Um = 20 * MM, angleArcSec = 0.0)
        val group = success(HoleGrid.create(common, input))
        assertPoints(listOf(0.0 to 0.0, 10.0 to 0.0, 20.0 to 0.0, 0.0 to 20.0, 10.0 to 20.0, 20.0 to 20.0), group.points)
    }

    @Test
    fun two_repeats_of_1_give_the_error_with_the_axis_names_of_the_plane() {
        val input = HoleGridInput(repeat1 = 1, repeat2 = 1)
        val xy = failureTexts(HoleGrid.create(PatternCommon(plane = Plane.XY), input))
        assertTrue("Repeat X or Repeat Y value must be larger than 1" in xy, "XY: $xy")
        val xz = failureTexts(HoleGrid.create(PatternCommon(plane = Plane.XZ), input))
        assertTrue("Repeat X or Repeat Z value must be larger than 1" in xz, "XZ: $xz")
        val yz = failureTexts(HoleGrid.create(PatternCommon(plane = Plane.YZ), input))
        assertTrue("Repeat Y or Repeat Z value must be larger than 1" in yz, "YZ: $yz")
        val lathe = failureTexts(HoleGrid.create(PatternCommon(machineType = MachineType.Lathe), input))
        assertTrue("Repeat X or Repeat Z value must be larger than 1" in lathe, "lathe: $lathe")
    }

    @Test
    fun a_step_is_required_when_its_repeat_is_more_than_1() {
        val texts = failureTexts(HoleGrid.create(PatternCommon(), HoleGridInput(repeat1 = 3, step1Um = null, repeat2 = 1)))
        assertTrue("Step X value is required" in texts, "texts: $texts")
        val ok = HoleGrid.create(PatternCommon(), HoleGridInput(repeat1 = 1, step1Um = null, repeat2 = 3, step2Um = 5 * MM))
        assertIs<PatternResult.Success>(ok)
    }

    @Test
    fun mill_step_and_repeat_keeps_the_sub_datum_z_and_drops_the_first_grid_point() {
        val source = SubDatum(name = "Hole", x = 5 * MM, y = 5 * MM, z = -3 * MM)
        val common = PatternCommon(origin = AxisPoint(0.0, 0.0, 0.0), convention = PatternAngleConvention.Legacy)
        val input = HoleGridInput(repeat1 = 3, step1Um = 10 * MM, repeat2 = 1)
        val group = success(StepAndRepeat.create(common, input, listOf(source)))
        assertEquals(2, group.points.size, "the first grid point does not repeat the source point")
        Expect.mm(15.0, group.points[0].x, "copy 1 X")
        Expect.mm(5.0, group.points[0].y, "copy 1 Y")
        Expect.mm(-3.0, group.points[0].z, "copy 1 Z")
        Expect.mm(25.0, group.points[1].x, "copy 2 X")
        Expect.mm(-3.0, group.points[1].z, "copy 2 Z")
    }

    @Test
    fun lathe_step_and_repeat_keeps_x_on_x_and_z_on_z() {
        val source = SubDatum(name = "Groove", x = 4 * MM, z = -10 * MM)
        val common = PatternCommon(machineType = MachineType.Lathe, origin = AxisPoint(0.0, null, 0.0))
        // Repeat 2 (Z on a lathe) = 3 with a step of 5 mm. The grid points are (0, 0), (0, 5), (0, 10).
        val input = HoleGridInput(repeat1 = 1, repeat2 = 3, step2Um = 5 * MM)
        val group = success(StepAndRepeat.create(common, input, listOf(source)))
        assertEquals(2, group.points.size)
        Expect.mm(4.0, group.points[0].x, "copy 1 X")
        Expect.mm(-5.0, group.points[0].z, "copy 1 Z")
        Expect.mm(4.0, group.points[1].x, "copy 2 X")
        Expect.mm(0.0, group.points[1].z, "copy 2 Z")
    }

    @Test
    fun step_and_repeat_has_one_copy_for_each_grid_point_and_each_source_in_list_order() {
        val a = SubDatum(name = "A", x = 1 * MM, y = 0.0)
        val b = SubDatum(name = "B", x = 2 * MM, y = 0.0)
        val common = PatternCommon(origin = AxisPoint(0.0, 0.0, null), convention = PatternAngleConvention.Legacy)
        val input = HoleGridInput(repeat1 = 3, step1Um = 10 * MM, repeat2 = 1)
        val xs = success(StepAndRepeat.create(common, input, listOf(a, b))).points.map { it.x!! / MM }
        assertEquals(listOf(11.0, 12.0, 21.0, 22.0), xs.map { Math.round(it * 1000) / 1000.0 })
    }

    @Test
    fun step_and_repeat_titles() {
        assertEquals("Step and Repeat 3 Sub-Datums", StepAndRepeat.title(3).text)
        assertEquals("Step and Repeat Workspace", StepAndRepeat.title(null).text)
    }

    @Test
    fun with_a_part_rotation_the_source_vector_turns_first_with_no_pivot() {
        // Part rotation α = 90° gives φ = −90°. The source (10, 0) turns
        // to (0, −10). The grid point (10, 0) then adds.
        val source = SubDatum(name = "S", x = 10 * MM, y = 0.0)
        val common = PatternCommon(origin = AxisPoint(0.0, 0.0, null), convention = PatternAngleConvention.Legacy)
        val input = HoleGridInput(repeat1 = 2, step1Um = 10 * MM, repeat2 = 1)
        val group = success(StepAndRepeat.create(common, input, listOf(source), sourceRotationArcSec = -90 * DEG))
        Expect.mm(10.0, group.points.single().x, "X")
        Expect.mm(-10.0, group.points.single().y, "Y")
    }

    @Test
    fun equidistant_array_with_5_points() {
        val axes = AxisSwitches(x = true, y = true, z = false)
        val result = EquidistantArray.create(5, axes, AxisRange(0.0, 100 * MM), AxisRange(0.0, 50 * MM), AxisRange(null, null))
        val group = success(result)
        assertEquals("Grid", group.groupName)
        assertPoints(listOf(0.0 to 0.0, 25.0 to 12.5, 50.0 to 25.0, 75.0 to 37.5, 100.0 to 50.0), group.points)
        assertTrue(group.points.all { it.z == null }, "Z is not included")
    }

    @Test
    fun equidistant_array_count_messages_and_included_axes() {
        val axes = AxisSwitches(x = true, y = false, z = false)
        val range = AxisRange(0.0, 10 * MM)
        assertEquals(
            listOf("Number of holes can't be less than 3."),
            failureTexts(EquidistantArray.create(2, axes, range, range, range)),
        )
        assertEquals(
            listOf("Please enter a valid number of holes."),
            failureTexts(EquidistantArray.create(null, axes, range, range, range)),
        )
        assertEquals(AxisSwitches(x = true, y = false, z = true), EquidistantArray.includedAxes(MachineType.Lathe, true, true, true, true))
        assertEquals(AxisSwitches(x = true, y = true, z = false), EquidistantArray.includedAxes(MachineType.VerticalMill, true, true, true, false))
    }

    @Test
    fun plane_map_table() {
        val origin = AxisPoint(1.0, 2.0, 3.0)
        val p = PointUm(10.0, 20.0)
        assertEquals(AxisPoint(11.0, 22.0, 3.0), PatternPlanes.map(Plane.XY, origin, p))
        assertEquals(AxisPoint(11.0, 2.0, 23.0), PatternPlanes.map(Plane.XZ, origin, p))
        assertEquals(AxisPoint(1.0, 12.0, 23.0), PatternPlanes.map(Plane.YZ, origin, p))
        assertEquals(AxisPoint(11.0, 2.0, 23.0), PatternPlanes.map(Plane.ZX, origin, p))
    }

    @Test
    fun planes_position_source_and_disabled_axis() {
        assertEquals(Plane.ZX, PatternPlanes.effectivePlane(MachineType.Lathe, Plane.XY))
        assertEquals(Plane.XY, PatternPlanes.effectivePlane(MachineType.VerticalMill))
        assertEquals(Plane.YZ, PatternPlanes.effectivePlane(MachineType.VerticalMill, Plane.YZ))
        val switches = AxisSwitches(x = true, y = true, z = false)
        val current = AxisPoint(1.0, 2.0, 3.0)
        assertEquals(AxisPoint(1.0, 2.0, null), PositionSource.Current.origin(switches, current))
        assertEquals(AxisPoint(0.0, 0.0, null), PositionSource.Zero.origin(switches, current))
        assertEquals(AxisPoint(7.0, 8.0, null), PositionSource.Custom.origin(switches, current, AxisPoint(7.0, 8.0, 9.0)))
    }

    @Test
    fun prefix_3_to_16_characters_and_the_defaults() {
        assertEquals(PrefixResult.Valid("Circle"), PatternPrefix.resolve("", "Circle"))
        assertEquals(PrefixResult.Valid("Abc"), PatternPrefix.resolve("Abc", "Circle"))
        assertEquals(PrefixResult.Valid("A".repeat(16)), PatternPrefix.resolve("A".repeat(16), "Circle"))
        val short = assertIs<PrefixResult.Invalid>(PatternPrefix.resolve("Ab", "Circle"))
        assertEquals("Sub-Datum Prefix must be at least 3 characters long.", short.error.text)
        val long = assertIs<PrefixResult.Invalid>(PatternPrefix.resolve("A".repeat(17), "Circle"))
        assertEquals("Sub-Datum Prefix can't be longer than 16 characters.", long.error.text)
        assertEquals("Circle", PatternPrefix.DEFAULT_CIRCLE)
        assertEquals("Grid", PatternPrefix.DEFAULT_GRID)
        assertEquals("Arc", PatternPrefix.DEFAULT_ARC)
    }

    @Test
    fun group_suffix_for_a_plane_that_is_not_the_default() {
        val xz = success(HoleCircle.create(PatternCommon(plane = Plane.XZ), HoleCircleInput(10 * MM, 4)))
        assertEquals("Circle (XZ)", xz.groupName)
        val xy = success(HoleCircle.create(PatternCommon(plane = Plane.XY), HoleCircleInput(10 * MM, 4)))
        assertEquals("Circle", xy.groupName)
        val lathe = success(HoleCircle.create(PatternCommon(machineType = MachineType.Lathe, origin = AxisPoint(0.0, null, 0.0)), HoleCircleInput(10 * MM, 4)))
        assertEquals("Circle (ZX)", lathe.groupName)
        // The point names: the suffix is part of each point name.
        val batch = PatternBatch.create(Workspace.forMachine(MachineType.Lathe), lathe, groupId = 1L)
        assertEquals("Circle (ZX) - 1", batch.subDatums.first().name)
    }

    @Test
    fun names_use_the_next_free_number_and_the_workspace_keeps_the_last_number() {
        val group = PatternGroup("Circle", List(6) { AxisPoint(it.toDouble(), 0.0, null) })
        val first = PatternBatch.create(Workspace(), group, groupId = 1L)
        assertEquals((1..6).map { "Circle - $it" }, first.subDatums.map { it.name })
        assertEquals(6, first.lastOrdinal, "the workspace keeps the last used number")
        val workspace = PatternBatch.addTo(Workspace(), group, groupId = 1L)
        assertEquals(6, workspace.lastOrdinal)
        val second = PatternBatch.create(workspace, PatternGroup("Grid", listOf(AxisPoint(0.0, 0.0, null))), groupId = 2L)
        assertEquals("Grid - 7", second.subDatums.single().name)
        val names = (workspace.subDatums + second.subDatums).map { it.name.substringAfter(" - ") }
        assertEquals(names.size, names.toSet().size, "no name number repeats")
    }

    @Test
    fun pattern_points_get_a_tag_from_the_prefix_and_a_used_tag_gets_a_number() {
        assertEquals("Circle", PatternTags.groupTag("Circle", Workspace()))
        val used = Workspace(subDatums = listOf(SubDatum(tags = listOf("Circle"))))
        assertEquals("Circle_2", PatternTags.groupTag("Circle", used))
        val usedTwice = Workspace(subDatums = listOf(SubDatum(tags = listOf("Circle")), SubDatum(tags = listOf("Circle_2"))))
        assertEquals("Circle_3", PatternTags.groupTag("Circle", usedTwice))
        assertEquals("My_Holes", PatternTags.groupTag("My Holes", Workspace()))
        assertEquals("Group", PatternTags.groupTag("  ", Workspace()))
        val batch = PatternBatch.create(used, PatternGroup("Circle", listOf(AxisPoint(0.0, 0.0, null))), groupId = 3L)
        assertEquals(listOf("Circle_2"), batch.subDatums.single().tags)
    }

    @Test
    fun create_keeps_the_full_value_of_each_field() {
        val common = PatternCommon(origin = AxisPoint(0.0, 0.0, null), convention = PatternAngleConvention.Standard)
        val group = success(HoleCircle.create(common, HoleCircleInput(10000.123456, 4)))
        assertEquals(10000.123456, group.points[0].x!!, 1e-9)
        assertNotEquals(10000.123, group.points[0].x!!)
    }

    @Test
    fun dialog_titles_for_mill_and_lathe() {
        assertEquals("Hole Circle", HoleCircle.title(MachineType.VerticalMill).text)
        assertEquals("Circular Point Array", HoleCircle.title(MachineType.Lathe).text)
        assertEquals("Hole Grid", HoleGrid.title(MachineType.VerticalMill).text)
        assertEquals("Linear Point Array", HoleGrid.title(MachineType.Lathe).text)
        assertEquals("Equidistant Hole Array", EquidistantArray.title(MachineType.VerticalMill).text)
        assertEquals("Equidistant Point Array", EquidistantArray.title(MachineType.Lathe).text)
        assertEquals("Arbitrary Hole Circle", ArbitraryHoleCircle.title().text)
    }

    @Test
    fun a_repeat_of_0_gives_no_point() {
        val group = success(HoleGrid.create(PatternCommon(), HoleGridInput(repeat1 = 0, repeat2 = 3, step2Um = 5 * MM)))
        assertTrue(group.points.isEmpty())
        assertNull(group.points.firstOrNull())
    }
}
