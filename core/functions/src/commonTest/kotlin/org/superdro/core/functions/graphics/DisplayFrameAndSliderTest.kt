package org.superdro.core.functions.graphics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.superdro.core.model.AxisAlignment
import org.superdro.core.model.AxisAlignmentSource
import org.superdro.core.model.AxisId
import org.superdro.core.model.MachineMode
import org.superdro.core.model.MachineType
import org.superdro.core.model.PartRotation
import org.superdro.core.model.Plane
import org.superdro.core.model.Workspace

/** The Rotate Part and Align Axes transforms, and the position slider geometry. */
class DisplayFrameAndSliderTest {

    @Test
    fun rotatePartBy90DegreesShowsThePoint10And0At0AndMinus10() {
        // Pivot (0, 0), α = 90°.
        val rotation = PartRotationTransform.typed(alphaArcSec = 324_000.0, pivot = Vec2(0.0, 0.0))
        assertEquals(Vec2(0.0, -10.0), rotation.apply(Vec2(10.0, 0.0)))
        assertEquals(Vec2(10.0, 0.0), rotation.inverse(Vec2(0.0, -10.0)))
        // The linked source: φ = −(A − offset).
        val linked = PartRotationTransform.linked(angularArcSec = 400_000.0, offsetArcSec = 76_000.0, pivot = Vec2(1000.0, 1000.0))
        assertEquals(-324_000.0, linked.phiArcSec)
        assertEquals(Vec2(1000.0, 0.0), linked.apply(Vec2(2000.0, 1000.0)))
        // The matrix agrees with the point map.
        assertNear(linked.apply(Vec2(1234.0, -567.0)), linked.toMatrix().map(Vec2(1234.0, -567.0)), 1e-9)
    }

    @Test
    fun rotatePartAboutAPivotIsReversible() {
        val rotation = PartRotationTransform.typed(AngleMath.degreesToArcSec(17.5), Vec2(25_000.0, -4_000.0))
        val point = Vec2(60_000.0, 12_000.0)
        assertNear(point, rotation.inverse(rotation.apply(point)), 1e-9)
        assertEquals(point.distanceTo(rotation.pivot), rotation.apply(point).distanceTo(rotation.pivot), 1e-9)
    }

    @Test
    fun alignAxesSquaresTheReadoutsToTheMeasuredEdge() {
        // A = (10, 10) mm and B = (60, 15) mm give a = 5.7106°.
        val a = Vec2(10_000.0, 10_000.0)
        val b = Vec2(60_000.0, 15_000.0)
        val angle = assertNotNull(AxisAlignmentTransform.measuredAngleArcSec(a, b))
        assertEquals(5.7106, AngleMath.arcSecToDegrees(angle), 0.00005)
        val alignment = AxisAlignmentTransform(angle)
        val alignedA = alignment.apply(a)
        val alignedB = alignment.apply(b)
        assertNear(Vec2(10_945.0, 8_955.0), alignedA, 0.5)
        assertNear(Vec2(61_195.0, 8_955.0), alignedB, 0.5)
        assertEquals(alignedA.y, alignedB.y, 1e-9)
        assertNear(b, alignment.inverse(alignedB), 1e-9)
        assertNear(alignedB, alignment.toMatrix().map(b), 1e-9)
    }

    @Test
    fun alignAxesHasNoDirectionForTwoPointsInOnePlace() {
        assertNull(AxisAlignmentTransform.measuredAngleArcSec(Vec2(1.0, 1.0), Vec2(1.00005, 1.0)))
        assertEquals(-10_800.0, AxisAlignmentTransform.linkedAngleArcSec(angularArcSec = 18_000.0, offsetArcSec = 7_200.0))
    }

    @Test
    fun alignAxesAppliesOnlyInTheMeasurementModeOnAMillInXy() {
        assertTrue(AxisAlignmentTransform.isActive(true, MachineType.VerticalMill, MachineMode.Measurement, Plane.XY))
        assertTrue(AxisAlignmentTransform.isActive(true, MachineType.HorizontalMill, MachineMode.Measurement, Plane.XY))
        assertFalse(AxisAlignmentTransform.isActive(false, MachineType.VerticalMill, MachineMode.Measurement, Plane.XY))
        assertFalse(AxisAlignmentTransform.isActive(true, MachineType.VerticalMill, MachineMode.Cutting, Plane.XY))
        assertFalse(AxisAlignmentTransform.isActive(true, MachineType.VerticalMill, MachineMode.Measurement, Plane.XZ))
        assertFalse(AxisAlignmentTransform.isActive(true, MachineType.Lathe, MachineMode.Measurement, Plane.ZX))
    }

    @Test
    fun theDisplayFrameComesFromTheStoredState() {
        val typed = PartRotation(active = true, angleArcSec = 324_000.0)
        assertEquals(PartRotationTransform(-324_000.0, Vec2(0.0, 0.0)), PartRotationTransform.of(typed, angularArcSec = null))
        val linked = PartRotation(active = true, pivotXUm = 1000.0, linkedToAngularEncoder = true, encoderOffsetArcSec = 76_000.0)
        assertEquals(PartRotationTransform(-324_000.0, Vec2(1000.0, 0.0)), PartRotationTransform.of(linked, angularArcSec = 400_000.0))
        assertNull(PartRotationTransform.of(linked, angularArcSec = null))
        assertNull(PartRotationTransform.of(PartRotation(active = false, angleArcSec = 10.0), 0.0))
        val encoder = AxisAlignment(active = true, source = AxisAlignmentSource.AngularEncoder, encoderOffsetArcSec = 7_200.0)
        assertEquals(
            AxisAlignmentTransform(-10_800.0),
            AxisAlignmentTransform.of(encoder, 18_000.0, MachineType.VerticalMill, MachineMode.Measurement, Plane.XY),
        )
        assertNull(AxisAlignmentTransform.of(encoder, 18_000.0, MachineType.VerticalMill, MachineMode.Cutting, Plane.XY))
        val measured = AxisAlignment(active = true, angleArcSec = 20_557.0, source = AxisAlignmentSource.MeasuredLine)
        assertEquals(AxisAlignmentTransform(20_557.0), AxisAlignmentTransform.of(measured, null, MachineType.VerticalMill, MachineMode.Measurement, Plane.XY))
        val workspace = Workspace(partRotation = typed, axisAlignment = measured)
        val frame = DisplayFrame.of(workspace, angularArcSec = null, MachineType.VerticalMill, MachineMode.Measurement)
        assertEquals(DisplayFrame(PartRotationTransform(-324_000.0, Vec2(0.0, 0.0)), AxisAlignmentTransform(20_557.0)), frame)
        assertTrue(DisplayFrame.of(Workspace(), null, MachineType.VerticalMill, MachineMode.Cutting).isIdentity)
    }

    @Test
    fun theDisplayFrameTurnsFirstAndAlignsAfter() {
        val rotation = PartRotationTransform.typed(AngleMath.degreesToArcSec(30.0), Vec2(5_000.0, 0.0))
        val alignment = AxisAlignmentTransform(AngleMath.degreesToArcSec(-12.0))
        val frame = DisplayFrame(rotation, alignment)
        val point = Vec2(20_000.0, 7_000.0)
        assertNear(alignment.apply(rotation.apply(point)), frame.apply(point), 1e-9)
        assertNear(point, frame.inverse(frame.apply(point)), 1e-9)
        assertNear(frame.apply(point), frame.toMatrix().map(point), 1e-9)
        assertTrue(DisplayFrame().isIdentity)
        assertEquals(point, DisplayFrame().apply(point))
    }

    @Test
    fun theSliderBaseLineCenterTickAndMetricTicks() {
        val slider = PositionSlider.geometry(1000.0, 40.0, positionUm = 0.0, toolSizeUm = 0.0, toolOffsetUm = 0.0, metric = true)
        assertEquals(Segment(25.0, 20.0, 975.0, 20.0), slider.baseLine)
        assertEquals(Segment(500.0, 20.0, 500.0, 40.0), slider.centerTick)
        // ±10, 20, 30, 40 mm; the tick at ±50 mm is outside the base line.
        assertEquals(listOf(100.0, 200.0, 300.0, 400.0, 600.0, 700.0, 800.0, 900.0), slider.ticks.map { tick -> tick.x1 }.sorted())
        assertTrue(slider.ticks.all { tick -> tick.y1 == 20.0 && tick.y2 == 30.0 })
        assertEquals(500.0, slider.markerX)
        assertNull(slider.toolBar)
    }

    @Test
    fun theSliderInchTicksAreEachHalfInch() {
        val slider = PositionSlider.geometry(1000.0, 40.0, 0.0, 0.0, 0.0, metric = false)
        assertEquals(listOf(119.0, 246.0, 373.0, 627.0, 754.0, 881.0), slider.ticks.map { tick -> tick.x1 }.sorted())
    }

    @Test
    fun theSliderMarkerIsClampedAtTheLimits() {
        val right = PositionSlider.geometry(1000.0, 40.0, positionUm = 60_000.0, toolSizeUm = 0.0, toolOffsetUm = 0.0, metric = true)
        assertEquals(1000.0, right.markerX)
        val left = PositionSlider.geometry(1000.0, 40.0, positionUm = -50_000.0, toolSizeUm = 0.0, toolOffsetUm = 0.0, metric = true)
        assertEquals(0.0, left.markerX)
        assertEquals(Triangle(Vec2(-20.0, 25.0), Vec2(-5.0, 25.0), Vec2(-5.0, 35.0)), left.markerLeft)
        assertEquals(Triangle(Vec2(20.0, 25.0), Vec2(5.0, 25.0), Vec2(5.0, 35.0)), left.markerRight)
        val quarter = PositionSlider.geometry(1000.0, 40.0, positionUm = 25_000.0, toolSizeUm = 0.0, toolOffsetUm = 0.0, metric = true)
        assertEquals(750.0, quarter.markerX)
    }

    @Test
    fun theToolBarHasTheToolDiameterAtTheSliderScaleAndIsClamped() {
        val toolSize = PositionSlider.toolSizeUm(AxisId.X, MachineType.VerticalMill, toolDiameterUm = 10_000.0)
        assertEquals(5000.0, toolSize)
        val atLimit = PositionSlider.geometry(1000.0, 40.0, positionUm = 50_000.0, toolSizeUm = toolSize, toolOffsetUm = 0.0, metric = true)
        assertEquals(Rect(950.0, 12.0, 1050.0, 18.0), atLimit.toolBar)
        val offset = PositionSlider.geometry(1000.0, 40.0, positionUm = 48_000.0, toolSizeUm = toolSize, toolOffsetUm = -5_000.0, metric = true)
        assertEquals(1000.0, offset.toolBar?.centerX)
        val inside = PositionSlider.geometry(1000.0, 40.0, positionUm = 10_000.0, toolSizeUm = toolSize, toolOffsetUm = 5_000.0, metric = true)
        assertEquals(550.0, inside.toolBar?.centerX)
        assertEquals(100.0, inside.toolBar?.width)
        assertEquals(0.0, PositionSlider.toolSizeUm(AxisId.Z, MachineType.VerticalMill, 10_000.0))
        assertEquals(0.0, PositionSlider.toolSizeUm(AxisId.X, MachineType.Lathe, 10_000.0))
    }
}
