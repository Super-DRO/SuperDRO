package org.superdro.core.functions.transform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.superdro.core.functions.graphics.AxisAlignmentTransform
import org.superdro.core.functions.graphics.DisplayFrame
import org.superdro.core.functions.graphics.Vec2
import org.superdro.core.functions.graphics.assertNear
import org.superdro.core.model.AxisId
import org.superdro.core.model.MachineType
import org.superdro.core.model.Plane
import org.superdro.core.model.SubDatum

/**
 * The origin commands and the pair commands. The machine
 * datums are in `core:readout` (`operations.MachineDatums`) with their tests.
 */
class OriginAndPairCommandsTest {

    private val xyz = setOf(AxisId.X, AxisId.Y, AxisId.Z)

    @Test
    fun alignAbsoluteOriginToSubDatumRenumbersTheStoredGeometry() {
        val s = SubDatum(id = "s", x = 25_000.0, y = 40_000.0, z = -5_000.0)
        val absolute = mapOf(AxisId.X to 60_000.0, AxisId.Y to 70_000.0, AxisId.Z to 10_000.0)
        val result = OriginCommands.alignAbsoluteOriginToSubDatum(s, xyz, absolute)
        assertEquals(TranslateTransform(-25_000.0, -40_000.0, 5_000.0, 0.0), result.transform)
        assertEquals(mapOf(AxisId.X to 35_000.0, AxisId.Y to 30_000.0, AxisId.Z to 15_000.0), result.newAbsolute)
        val p = result.transform.mapPoint(SubDatum(x = 30_000.0, y = 50_000.0, z = 0.0))
        assertEquals(listOf(5_000.0, 10_000.0, 5_000.0), listOf(p.x, p.y, p.z))
        val moved = result.transform.mapPoint(s)
        assertEquals(listOf(0.0, 0.0, 0.0), listOf(moved.x, moved.y, moved.z))
    }

    @Test
    fun alignAbsoluteOriginUsesZeroForAnAxisThatIsOff() {
        val s = SubDatum(x = 25_000.0, y = 40_000.0, z = -5_000.0, a = 3_600.0)
        val absolute = mapOf(AxisId.X to 60_000.0, AxisId.Y to 70_000.0, AxisId.Z to 10_000.0, AxisId.Angular to 7_200.0)
        val result = OriginCommands.alignAbsoluteOriginToSubDatum(s, setOf(AxisId.Y), absolute)
        assertEquals(TranslateTransform(-25_000.0, -40_000.0, 0.0, 0.0), result.transform)
        assertEquals(mapOf(AxisId.X to 35_000.0, AxisId.Y to 30_000.0), result.newAbsolute)
        val angular = OriginCommands.alignAbsoluteOriginToSubDatum(s, setOf(AxisId.Angular), absolute)
        assertEquals(-3_600.0, angular.transform.daArcSec)
        assertEquals(3_600.0, angular.newAbsolute[AxisId.Angular])
    }

    @Test
    fun alignSubDatumToSpindleShiftsTheReadoutToTheSubDatum() {
        val s = SubDatum(x = 25_000.0, y = 40_000.0)
        val absolute = mapOf(AxisId.X to 60_000.0, AxisId.Y to 70_000.0, AxisId.Z to 10_000.0)
        val result = assertIs<SpindleAlignResult.Apply>(OriginCommands.alignSubDatumToSpindle(s, xyz, absolute))
        assertEquals(mapOf(AxisId.X to 25_000.0, AxisId.Y to 40_000.0), result.newAbsolute)
        assertEquals(listOf(AxisShift(AxisId.X, -35_000.0), AxisShift(AxisId.Y, -30_000.0)), result.shifts)
        // Below the no-op limit of 0.5 µm.
        val near = mapOf(AxisId.X to 25_000.4, AxisId.Y to 39_999.6)
        assertEquals(SpindleAlignResult.NoOp, OriginCommands.alignSubDatumToSpindle(s, xyz, near))
        // The lathe "(Z Only)" button.
        val lathe = SubDatum(x = 10_000.0, z = -20_000.0)
        val zOnly = assertIs<SpindleAlignResult.Apply>(
            OriginCommands.alignSubDatumToSpindle(lathe, setOf(AxisId.Z), mapOf(AxisId.X to 0.0, AxisId.Z to 0.0), onlyAxes = setOf(AxisId.Z)),
        )
        assertEquals(mapOf(AxisId.Z to -20_000.0), zOnly.newAbsolute)
    }

    @Test
    fun alignAToBMovesTheAbsoluteOrigin() {
        val result = PairCommands.alignAToB(a = Vec2(10_000.0, 20_000.0), b = Vec2(15_000.0, 20_000.0), absolute = Vec2(50_000.0, 30_000.0))
        assertEquals(AlignAToBResult.Apply(newU = 45_000.0, newV = 30_000.0, shiftU = -5_000.0, shiftV = 0.0), result)
        assertEquals(AlignAToBResult.NoOp, PairCommands.alignAToB(Vec2(0.0, 0.0), Vec2(0.4, -0.4), Vec2(1.0, 1.0)))
    }

    @Test
    fun alignBToSpindleSnapsTo90Degrees() {
        val a = Vec2(0.0, 0.0)
        val b = Vec2(50_000.0, 0.0)
        val result = assertIs<AlignSpindleResult.Rotate>(PairCommands.alignBToSpindle(a, b, spindle = Vec2(0.5, 50_000.0)))
        assertEquals(90.0, result.thetaDegrees)
        assertEquals(0.016206, result.toleranceDegrees, 0.0000005)
        assertFalse(result.uncertain)
        assertEquals(0.0, result.landUm, 0.0005)
        val moved = result.transform.mapPoint(SubDatum(x = b.x, y = b.y))
        assertEquals(listOf(0.0, 50_000.0), listOf(moved.x, moved.y))
        // An angle far from a multiple of 90° does not snap.
        val spindle30 = Vec2(50_000.0 * kotlin.math.cos(kotlin.math.PI / 6.0), 50_000.0 * kotlin.math.sin(kotlin.math.PI / 6.0))
        val raw = assertIs<AlignSpindleResult.Rotate>(PairCommands.alignBToSpindle(a, b, spindle30))
        assertEquals(30.0, raw.thetaDegrees, 1e-9)
    }

    @Test
    fun aShortBaselineWarnsAndADegeneratePairHasNoDirection() {
        val geometry = PairCommands.geometry(Vec2(0.0, 0.0), Vec2(5_000.0, 0.0))
        assertEquals(0.16206, geometry.toleranceDegrees, 0.000005)
        assertTrue(geometry.shortBaseline)
        assertFalse(geometry.degenerate)
        val result = assertIs<AlignSpindleResult.Rotate>(PairCommands.alignBToSpindle(Vec2(0.0, 0.0), Vec2(5_000.0, 0.0), Vec2(3_000.0, 3_000.0)))
        assertEquals(45.0, result.thetaDegrees, 1e-9)
        assertTrue(result.uncertain)
        // The limit: about 8.103 mm.
        assertFalse(PairCommands.geometry(Vec2(0.0, 0.0), Vec2(8_200.0, 0.0)).shortBaseline)
        assertTrue(PairCommands.geometry(Vec2(0.0, 0.0), Vec2(8_000.0, 0.0)).shortBaseline)
        val degenerate = PairCommands.geometry(Vec2(0.0, 0.0), Vec2(0.5, 0.5))
        assertTrue(degenerate.degenerate)
        assertTrue(degenerate.toleranceDegrees.isNaN())
        assertEquals(AlignSpindleResult.NoDirection, PairCommands.alignBToSpindle(Vec2(0.0, 0.0), Vec2(5_000.0, 0.0), Vec2(0.5, 0.5)))
        assertEquals(AlignSpindleResult.NoOp, PairCommands.alignBToSpindle(Vec2(0.0, 0.0), Vec2(5_000.0, 0.0), Vec2(9_000.0, 0.1)))
    }

    @Test
    fun thePairAngleIsALineDirection() {
        assertEquals(-45.0, PairCommands.geometry(Vec2(0.0, 0.0), Vec2(-1_000.0, 1_000.0)).lineAngleDegrees, 1e-9)
        assertEquals(90.0, PairCommands.geometry(Vec2(0.0, 0.0), Vec2(0.0, -1_000.0)).lineAngleDegrees, 1e-9)
        assertEquals(0.0, PairCommands.geometry(Vec2(0.0, 0.0), Vec2(-1_000.0, 0.0)).lineAngleDegrees, 1e-9)
        assertTrue(PairCommands.alignSpindleVisible(MachineType.VerticalMill, Plane.XY))
        assertFalse(PairCommands.alignSpindleVisible(MachineType.Lathe, Plane.ZX))
        assertFalse(PairCommands.alignSpindleVisible(MachineType.VerticalMill, Plane.XZ))
    }

    @Test
    fun theShownPositionGoesBackToTheWorkspaceFrameFirst() {
        // With Align Axes on, the readout shows the spindle in the aligned frame.
        val frame = DisplayFrame(axisAlignment = AxisAlignmentTransform(36_000.0))
        val spindle = Vec2(0.5, 50_000.0)
        val shown = frame.apply(spindle)
        val workspaceFrame = frame.inverse(shown)
        assertNear(spindle, workspaceFrame, 1e-9)
        val fromWorkspace = PairCommands.alignBToSpindle(Vec2(0.0, 0.0), Vec2(50_000.0, 0.0), workspaceFrame)
        assertEquals(90.0, assertIs<AlignSpindleResult.Rotate>(fromWorkspace).thetaDegrees)
    }
}
