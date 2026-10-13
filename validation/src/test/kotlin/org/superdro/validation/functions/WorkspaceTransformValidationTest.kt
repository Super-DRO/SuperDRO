package org.superdro.validation.functions

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.superdro.core.functions.graphics.Matrix3
import org.superdro.core.functions.graphics.Vec2
import org.superdro.core.functions.transform.AlignAToBResult
import org.superdro.core.functions.transform.AlignSpindleResult
import org.superdro.core.functions.transform.FlipAxis
import org.superdro.core.functions.transform.FlipTransform
import org.superdro.core.functions.transform.GuideMapResult
import org.superdro.core.functions.transform.GuidePolicy
import org.superdro.core.functions.transform.OriginCommands
import org.superdro.core.functions.transform.PairCommands
import org.superdro.core.functions.transform.RotateTransform
import org.superdro.core.functions.transform.ScaleTransform
import org.superdro.core.functions.transform.applyTo
import org.superdro.core.functions.transform.countBrokenGuides
import org.superdro.core.functions.transform.mapReferenceImage
import org.superdro.core.model.AxisAlignment
import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.Drawing
import org.superdro.core.model.DrawingShape
import org.superdro.core.model.Guide
import org.superdro.core.model.MachineDatum
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.ReferenceImage
import org.superdro.core.model.ShapeType
import org.superdro.core.model.SubDatum
import org.superdro.core.model.Workspace
import org.superdro.core.readout.ReadoutFrame
import org.superdro.core.readout.operations.MachineDatumPlan
import org.superdro.core.readout.operations.MachineDatums
import org.superdro.validation.functions.Expect.MM

/**
 * The machine datums and the workspace transforms, with each row of the transform table and the
 * worked examples.
 */
class WorkspaceTransformValidationTest {

    private fun point(xMm: Double?, yMm: Double?, zMm: Double? = null, tags: List<String> = emptyList()) =
        SubDatum(name = "P", x = xMm?.times(MM), y = yMm?.times(MM), z = zMm?.times(MM), tags = tags)

    private fun assertPoint(expected: Triple<Double, Double, Double?>, actual: SubDatum) {
        Expect.mm(expected.first, actual.x, "X")
        Expect.mm(expected.second, actual.y, "Y")
        val z = expected.third
        if (z == null) assertNull(actual.z) else Expect.mm(z, actual.z, "Z")
    }

    @Test
    fun flip_across_x_at_25_mm() {
        val flip = FlipTransform(FlipAxis.AcrossX, 25 * MM)
        assertPoint(Triple(10.0, 10.0, -2.0), flip.mapPoint(point(10.0, 40.0, -2.0)))
        val guideY = assertIs<GuideMapResult.Kept>(flip.mapGuide(Guide(axis = AxisId.Y, valueUm = 30 * MM), GuidePolicy.Delete)).guide
        Expect.mm(20.0, guideY.valueUm, "guide Y")
        val guideX = assertIs<GuideMapResult.Kept>(flip.mapGuide(Guide(axis = AxisId.X, valueUm = 5 * MM), GuidePolicy.Delete)).guide
        Expect.mm(5.0, guideX.valueUm, "guide X does not change")
        assertEquals(Matrix3(1.0, 0.0, 0.0, 0.0, -1.0, 50_000.0, 0.0, 0.0, 1.0), flip.imageMatrix)
        val shape = flip.mapShape(DrawingShape(type = ShapeType.Point, x1 = 10_000_000L, y1 = 40_000_000L))
        assertEquals(10_000_000L, shape.y1)
        val arc = flip.mapShape(DrawingShape(type = ShapeType.Arc, x1 = 0L, y1 = 0L, radius = 1_000_000L, startAngle = 0.5, sweepAngle = 1.0))
        assertEquals(2 * PI - 0.5, arc.startAngle!!, 1e-9, "a0' = −0.5 → 5.7832 rad")
        Expect.near(5.7832, arc.startAngle, 0.00005, "a0'")
        assertEquals(-1.0, arc.sweepAngle!!, 1e-12)
    }

    @Test
    fun rotate_30_degrees_about_10_0() {
        val rotate = RotateTransform.ofDegrees(30.0, 10 * MM, 0.0)
        assertPoint(Triple(18.660, 5.0, 3.0), rotate.mapPoint(point(20.0, 0.0, 3.0)))
        assertEquals("30°", rotate.label)
        val guide = Guide(id = "g", axis = AxisId.X, valueUm = 30 * MM)
        assertEquals(GuideMapResult.Deleted, rotate.mapGuide(guide, GuidePolicy.Delete), "Continue")
        val converted = assertIs<GuideMapResult.ConvertedToPoint>(rotate.mapGuide(guide, GuidePolicy.Convert))
        Expect.mm(27.321, converted.xUm, "converted X")
        Expect.mm(10.0, converted.yUm, "converted Y")
        assertEquals(1, rotate.countBrokenGuides(listOf(guide, Guide(axis = AxisId.Z, valueUm = 1.0))))
    }

    @Test
    fun rotate_90_degrees_keeps_guides_on_the_other_axis_and_normalizes_the_angle() {
        val rotate = RotateTransform.ofDegrees(90.0, 0.0, 0.0)
        assertPoint(Triple(-5.0, 20.0, null), rotate.mapPoint(point(20.0, 5.0)))
        val guide = assertIs<GuideMapResult.Kept>(rotate.mapGuide(Guide(axis = AxisId.X, valueUm = 10 * MM), GuidePolicy.Delete)).guide
        assertEquals(AxisId.Y, guide.axis)
        Expect.mm(10.0, guide.valueUm, "guide Y")
        assertFalse(rotate.breaksGuides)
        assertEquals(-90.0, RotateTransform.ofDegrees(270.0, 0.0, 0.0).angleDegrees, 1e-9, "(−180°, 180°]")
        assertEquals(180.0, RotateTransform.ofDegrees(-180.0, 0.0, 0.0).angleDegrees, 1e-9, "(−180°, 180°]")
    }

    @Test
    fun scale_150_percent_about_100_50_0() {
        val scale = ScaleTransform.ofPercent(150.0, 100 * MM, 50 * MM, 0.0)
        assertPoint(Triple(130.0, 50.0, -15.0), scale.mapPoint(point(120.0, 50.0, -10.0)))
        val guide = assertIs<GuideMapResult.Kept>(scale.mapGuide(Guide(axis = AxisId.Y, valueUm = 60 * MM), GuidePolicy.Delete)).guide
        Expect.mm(65.0, guide.valueUm, "guide Y")
        assertEquals(Matrix3(1.5, 0.0, -50_000.0, 0.0, 1.5, -25_000.0, 0.0, 0.0, 1.0), scale.imageMatrix, "setScale(1.5, 1.5, 100000, 50000)")
        // A scale without Z keeps Z, so a 2D sub-datum can be the pivot.
        val noZ = ScaleTransform.ofPercent(150.0, 100 * MM, 50 * MM, null)
        assertPoint(Triple(130.0, 50.0, -10.0), noZ.mapPoint(point(120.0, 50.0, -10.0)))
        assertFailsWith<IllegalArgumentException>("the percent must be more than 0") { ScaleTransform.ofPercent(0.0, 0.0, 0.0) }
    }

    @Test
    fun align_absolute_origin_to_sub_datum() {
        val s = point(25.0, 40.0, -5.0)
        val result = OriginCommands.alignAbsoluteOriginToSubDatum(
            s, setOf(AxisId.X, AxisId.Y, AxisId.Z), mapOf(AxisId.X to 60 * MM, AxisId.Y to 70 * MM, AxisId.Z to 10 * MM),
        )
        assertEquals(-25 * MM, result.transform.dxUm)
        assertEquals(-40 * MM, result.transform.dyUm)
        assertEquals(5 * MM, result.transform.dzUm)
        assertPoint(Triple(5.0, 10.0, 5.0), result.transform.mapPoint(point(30.0, 50.0, 0.0)))
        assertPoint(Triple(0.0, 0.0, 0.0), result.transform.mapPoint(s))
        Expect.mm(35.0, result.newAbsolute[AxisId.X], "new X")
        Expect.mm(30.0, result.newAbsolute[AxisId.Y], "new Y")
        Expect.mm(15.0, result.newAbsolute[AxisId.Z], "new Z")
    }

    @Test
    fun readout_machine_datum_plan_gives_7_6_3_8_12_7_mm() {
        // core:readout owns the machine datum plan. Machine position (160, 80, 0) mm and absolute (10, 5, 20) mm:
        // the absolute offsets are (−150, −75, 20) mm.
        val profile = MachineProfile.create(MachineType.VerticalMill)
        val states = Workspace.forMachine(MachineType.VerticalMill).axisStates.toMutableMap()
        states[AxisId.X] = AxisState(absoluteOffset = -150 * MM)
        states[AxisId.Y] = AxisState(absoluteOffset = -75 * MM)
        states[AxisId.Z] = AxisState(absoluteOffset = 20 * MM)
        val workspace = Workspace.forMachine(MachineType.VerticalMill).copy(axisStates = states)
        val inputs = mapOf<AxisId, Double?>(AxisId.X to 160 * MM, AxisId.Y to 80 * MM, AxisId.Z to 0.0)
        val frame = ReadoutFrame(profile, workspace, inputs)
        assertEquals(10 * MM, frame.reading(AxisId.X).absolute, 1e-9)
        val datum = MachineDatum(name = "Datum 1", x = 152.4 * MM, y = 76.2 * MM, z = -12.7 * MM)
        val plan = assertIs<MachineDatumPlan.Apply>(
            MachineDatums.plan(frame, datum),
        )
        assertFalse(plan.noOp)
        Expect.mm(-2.4, plan.shifts[AxisId.X], "X shift")
        Expect.mm(-1.2, plan.shifts[AxisId.Y], "Y shift")
        Expect.mm(-7.3, plan.shifts[AxisId.Z], "Z shift")
        val after = ReadoutFrame(profile, plan.result.workspace, inputs)
        Expect.mm(7.6, after.reading(AxisId.X).absolute, "X")
        Expect.mm(3.8, after.reading(AxisId.Y).absolute, "Y")
        Expect.mm(12.7, after.reading(AxisId.Z).absolute, "Z")
        assertTrue(plan.result.changed, "the apply has an undo")
        assertEquals("Datum 1", MachineDatums.nextQuickCaptureName(emptyList()))
        assertEquals("Datum 2", MachineDatums.nextQuickCaptureName(listOf(datum)))
    }

    @Test
    fun no_op_limits_0_5_um_and_0_5_arc_seconds_and_a_is_captured() {
        val base = MachineProfile.create(MachineType.VerticalMill)
        val profile = base.copy(angular = base.angular.copy(enabled = true))
        val datum = MachineDatum(x = 1_000.0, a = 100.0)
        val inputs = mapOf<AxisId, Double?>(AxisId.X to 1_000.0, AxisId.Y to 0.0, AxisId.Z to 0.0, AxisId.Angular to 100.0)
        // The shift is −D − (the absolute offset): offsets of −1000.4 µm and −100.4″ give shifts of 0.4.
        fun frame(xOffset: Double, aOffset: Double): ReadoutFrame {
            val states = Workspace.forMachine(MachineType.VerticalMill).axisStates.toMutableMap()
            states[AxisId.X] = AxisState(absoluteOffset = xOffset)
            states[AxisId.Angular] = AxisState(absoluteOffset = aOffset)
            return ReadoutFrame(profile, Workspace.forMachine(MachineType.VerticalMill).copy(axisStates = states), inputs)
        }
        fun plan(xOffset: Double, aOffset: Double) = assertIs<MachineDatumPlan.Apply>(MachineDatums.plan(frame(xOffset, aOffset), datum))
        assertTrue(plan(-1_000.4, -100.4).noOp)
        assertFalse(plan(-1_000.4, -100.6).noOp, "0.6″ is not a no-op")
        assertFalse(plan(-1_000.6, -100.0).noOp, "0.6 µm is not a no-op")
        val capture = MachineDatums.capture(frame(0.0, 0.0), "Datum 1")
        assertEquals(100.0, capture?.a, "A is stored when the angular axis is on")
        val noA = MachineDatums.capture(ReadoutFrame(base, Workspace.forMachine(MachineType.VerticalMill), inputs), "Datum 2")
        assertNull(noA?.a)
    }

    @Test
    fun machine_position_has_no_tool_offset() {
        val profile = MachineProfile.create(MachineType.VerticalMill)
        val states = Workspace.forMachine(MachineType.VerticalMill).axisStates.toMutableMap()
        states[AxisId.X] = AxisState(absoluteOffset = 1_000.0, toolOffset = 5_000.0, toolOffsetActive = true)
        val workspace = Workspace.forMachine(MachineType.VerticalMill).copy(axisStates = states)
        val frame = ReadoutFrame(profile, workspace, mapOf(AxisId.X to 20_000.0, AxisId.Y to 0.0, AxisId.Z to 0.0))
        val reading = frame.reading(AxisId.X)
        assertEquals(26_000.0, reading.absolute, 1e-9, "abs = R + T + A")
        assertEquals(20_000.0, reading.machinePosition, 1e-9, "the machine position is R")
        val datum = MachineDatums.capture(frame, "Datum 1")
        assertEquals(20_000.0, datum?.x)
    }

    @Test
    fun align_a_to_b() {
        val result = assertIs<AlignAToBResult.Apply>(PairCommands.alignAToB(Vec2(10 * MM, 20 * MM), Vec2(15 * MM, 20 * MM), Vec2(50 * MM, 30 * MM)))
        Expect.mm(45.0, result.newU, "X")
        Expect.mm(30.0, result.newV, "Y")
        Expect.mm(-5.0, result.shiftU, "X shift")
        Expect.mm(0.0, result.shiftV, "Y shift")
        assertEquals(AlignAToBResult.NoOp, PairCommands.alignAToB(Vec2(0.0, 0.0), Vec2(0.4, 0.0), Vec2(0.0, 0.0)))
    }

    @Test
    fun align_b_to_spindle_snaps_to_90_degrees() {
        val result = assertIs<AlignSpindleResult.Rotate>(PairCommands.alignBToSpindle(Vec2(0.0, 0.0), Vec2(50 * MM, 0.0), Vec2(0.5, 50 * MM)))
        assertEquals(90.0, result.thetaDegrees, 1e-12)
        Expect.near(0.016206, result.toleranceDegrees, 0.0000005, "t")
        assertFalse(result.uncertain, "no warning: t ≤ 0.1°")
        Expect.mm(0.0, result.landUm, "land distance")
        val b = result.transform.mapPoint(point(50.0, 0.0))
        assertPoint(Triple(0.0, 50.0, null), b)
        val short = PairCommands.geometry(Vec2(0.0, 0.0), Vec2(5 * MM, 0.0))
        Expect.near(0.16206, short.toleranceDegrees, 0.000005, "t at 5 mm")
        assertTrue(short.shortBaseline, "t > 0.1°")
        assertTrue(PairCommands.geometry(Vec2(0.0, 0.0), Vec2(0.9, 0.0)).degenerate, "less than 1 µm")
        assertFalse(PairCommands.geometry(Vec2(0.0, 0.0), Vec2(1.0, 0.0)).degenerate)
    }

    @Test
    fun one_transform_changes_all_stored_geometry_and_keeps_all_tags() {
        val p = point(10.0, 40.0, -2.0, tags = listOf("Holes", "Top", "Batch_2"))
        val workspace = Workspace(
            activeSubDatumId = p.id,
            subDatums = listOf(p),
            guides = listOf(Guide(id = "gy", axis = AxisId.Y, valueUm = 30 * MM)),
            referenceImage = ReferenceImage(fileName = "images/a.jpg"),
            axisAlignment = AxisAlignment(active = true, angleArcSec = 30 * 3600.0),
        )
        val drawing = Drawing(shapes = listOf(DrawingShape(type = ShapeType.Point, x1 = 0L, y1 = 40_000_000L)))
        val outcome = FlipTransform(FlipAxis.AcrossX, 25 * MM).applyTo(workspace, drawing, GuidePolicy.Delete)
        assertTrue(outcome.changed)
        val moved = outcome.workspace.subDatums.single()
        assertPoint(Triple(10.0, 10.0, -2.0), moved)
        assertEquals(listOf("Holes", "Top", "Batch_2"), moved.tags, "all tags stay")
        Expect.mm(20.0, outcome.workspace.guides.single().valueUm, "guide")
        assertEquals(listOf(1.0, 0.0, 0.0, 0.0, -1.0, 50_000.0, 0.0, 0.0, 1.0), outcome.workspace.referenceImage!!.matrix)
        assertEquals(10_000_000L, outcome.drawing!!.shapes.single().y1)
        assertNull(outcome.workspace.activeSubDatumId, "the active sub-datum is deactivated first")
        // The stored rows use the workspace frame. The Align Axes angle does not change the result.
        assertEquals(10 * MM, moved.y!!, 1e-9)
        val identity = RotateTransform.ofDegrees(0.0, 0.0, 0.0).applyTo(workspace, drawing, GuidePolicy.Delete)
        assertFalse(identity.changed, "an identity transform writes nothing")
    }

    @Test
    fun the_mapped_image_matrix_is_the_stored_matrix_then_the_transform() {
        val stored = ReferenceImage(matrix = listOf(2.0, 0.0, 10.0, 0.0, 2.0, 20.0, 0.0, 0.0, 1.0))
        val mapped = FlipTransform(FlipAxis.AcrossY, 5.0).mapReferenceImage(stored)
        // x' = 2·5 − (2x + 10) = −2x; y' = 2y + 20.
        assertEquals(listOf(-2.0, 0.0, 0.0, 0.0, 2.0, 20.0, 0.0, 0.0, 1.0), mapped.matrix)
    }
}
