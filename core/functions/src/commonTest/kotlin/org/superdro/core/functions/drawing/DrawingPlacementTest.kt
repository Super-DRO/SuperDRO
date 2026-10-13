package org.superdro.core.functions.drawing

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.superdro.core.functions.graphics.AngleMath
import org.superdro.core.functions.graphics.Vec2
import org.superdro.core.functions.graphics.assertNear
import org.superdro.core.model.DrawingShape
import org.superdro.core.model.ShapeType

/** "Set Up Drawing". */
class DrawingPlacementTest {

    private fun rectangle(x0: Long, y0: Long, width: Long, height: Long) = DrawingShape(
        type = ShapeType.Polyline,
        closed = true,
        points = listOf(x0, y0, x0 + width, y0, x0 + width, y0 + height, x0, y0 + height),
    )

    private fun point(x: Long, y: Long) = DrawingShape(type = ShapeType.Point, x1 = x, y1 = y)

    @Test
    fun aDrawingNearItsOriginKeepsTheDocumentOrigin() {
        val placement = DrawingPlacement.load(listOf(rectangle(-5_000_000, 0, 50_000_000, 30_000_000)))
        assertEquals(DefaultPlacement.DocumentOrigin, placement.defaultPlacement)
        assertEquals(Vec2.ZERO, placement.offsetUm)
        // The gap (20 mm) is less than the larger side (50 mm).
        assertEquals(DefaultPlacement.DocumentOrigin, DrawingPlacement.defaultPlacement(listOf(rectangle(20_000_000, 20_000_000, 50_000_000, 30_000_000))))
    }

    @Test
    fun aDrawingFarFromItsOriginPutsTheLowerLeftCornerAtTheOrigin() {
        val far = DrawingPlacement.load(listOf(rectangle(1_000_000_000, 1_000_000_000, 50_000_000, 30_000_000)))
        assertEquals(DefaultPlacement.ExtentsLowerLeft, far.defaultPlacement)
        assertEquals(Vec2(-1_000_000.0, -1_000_000.0), far.offsetUm)
        assertEquals(Vec2(0.0, 0.0), far.predictMachine(Vec2(1_000_000_000.0, 1_000_000_000.0)))
        // The origin is in the X range, but the Y gap is more than the larger side.
        assertEquals(DefaultPlacement.ExtentsLowerLeft, DrawingPlacement.defaultPlacement(listOf(rectangle(0, 1_000_000_000, 50_000_000, 30_000_000))))
    }

    @Test
    fun theRotateAndMirrorStepsChangeAllShapes() {
        val placement = DrawingPlacement.load(listOf(point(10_000_000, 5_000_000)))
        val left = placement.rotateLeft()
        assertEquals(listOf(-5_000_000L, 10_000_000L), listOf(left.shapes[0].x1, left.shapes[0].y1))
        val right = placement.rotateRight()
        assertEquals(listOf(5_000_000L, -10_000_000L), listOf(right.shapes[0].x1, right.shapes[0].y1))
        val mirrorH = placement.mirrorHorizontal()
        assertEquals(listOf(-10_000_000L, 5_000_000L), listOf(mirrorH.shapes[0].x1, mirrorH.shapes[0].y1))
        val mirrorV = placement.mirrorVertical()
        assertEquals(listOf(10_000_000L, -5_000_000L), listOf(mirrorV.shapes[0].x1, mirrorV.shapes[0].y1))
        assertEquals(listOf("L", "H", "V", "R"), placement.rotateLeft().mirrorHorizontal().mirrorVertical().rotateRight().steps.map { step -> step.token })
        // The file shapes stay as loaded.
        assertEquals(placement.fileShapes, left.fileShapes)
    }

    @Test
    fun aMirrorOfAnArcChangesItsStartAndItsSweep() {
        val arc = DrawingShape(type = ShapeType.Arc, x1 = 0, y1 = 0, radius = 1_000_000, startAngle = 0.5, sweepAngle = 1.0)
        val h = DrawingPlacement.load(listOf(arc)).mirrorHorizontal().shapes[0]
        assertEquals(PI - 0.5, assertNotNull(h.startAngle), 1e-12)
        assertEquals(-1.0, h.sweepAngle)
        val v = DrawingPlacement.load(listOf(arc)).mirrorVertical().shapes[0]
        assertEquals(2 * PI - 0.5, assertNotNull(v.startAngle), 1e-12)
        assertEquals(-1.0, v.sweepAngle)
    }

    @Test
    fun oneReferencePointGivesTheOffset() {
        val placement = DrawingPlacement.load(listOf(point(0, 0)))
            .setReference(0, drawingNm = Vec2(10_000_000.0, 5_000_000.0), machineUm = Vec2(110_000.0, 205_000.0))
        assertEquals(Vec2(100_000.0, 200_000.0), placement.offsetUm)
        assertEquals(0.0, placement.rotationRad)
        assertEquals(Vec2(100_000.0, 200_000.0), placement.predictMachine(Vec2(0.0, 0.0)))
        // A rotate step moves the reference point with the drawing, so the reference stays on its machine point.
        val turned = placement.rotateLeft()
        assertNear(Vec2(110_000.0, 205_000.0), turned.predictMachine(assertNotNull(turned.references[0]).drawingNm), 1e-9)
    }

    @Test
    fun twoReferencePointsGiveTheRotation() {
        val m1 = Vec2(10_000.0, 20_000.0)
        val m2 = Vec2(10_000.0 + 100_000.0 * cos(PI / 6.0), 20_000.0 + 100_000.0 * sin(PI / 6.0))
        val placement = DrawingPlacement.load(listOf(point(0, 0)))
            .setReference(0, Vec2(0.0, 0.0), m1)
            .setReference(1, Vec2(100_000_000.0, 0.0), m2)
        assertEquals(30.0, AngleMath.radToDegrees(placement.rotationRad), 1e-9)
        assertNear(m1, placement.predictMachine(Vec2(0.0, 0.0)), 1e-6)
        assertNear(m2, placement.predictMachine(Vec2(100_000_000.0, 0.0)), 1e-6)
        assertEquals(2, placement.referenceCount)
    }

    @Test
    fun aRotationThatMovesTheFarPointLessThan50UmIsIgnored() {
        // 100 mm: the limit is degrees(50000 nm ÷ 1e8 nm) = 0.02865°.
        fun rotationFor(degrees: Double): Double {
            val rad = AngleMath.degreesToRad(degrees)
            return DrawingPlacement.load(listOf(point(0, 0)))
                .setReference(0, Vec2(0.0, 0.0), Vec2(0.0, 0.0))
                .setReference(1, Vec2(100_000_000.0, 0.0), Vec2(100_000.0 * cos(rad), 100_000.0 * sin(rad)))
                .rotationRad
        }
        assertEquals(0.0, rotationFor(0.028))
        assertEquals(0.029, AngleMath.radToDegrees(rotationFor(0.029)), 1e-9)
    }

    private fun twoReferences(drawingMm: Double, machineMm: Double): DrawingPlacement =
        DrawingPlacement.load(listOf(point(0, 0)))
            .setReference(0, Vec2(0.0, 0.0), Vec2(0.0, 0.0))
            .setReference(1, Vec2(drawingMm * 1_000_000.0, 0.0), Vec2(machineMm * 1000.0, 0.0))

    @Test
    fun theUnitTestHasALimitOf05Percent() {
        assertEquals(UnitCheck.Agrees, twoReferences(100.0, 100.4).unitCheck())
        assertEquals(UnitCheck.Agrees, twoReferences(100.0, 99.5).unitCheck())
        assertEquals(UnitCheck.WrongUnit(UnitFactor.InchWrittenAsMm), twoReferences(4.0, 101.6).unitCheck())
        assertEquals(UnitCheck.WrongUnit(UnitFactor.MmWrittenAsInch), twoReferences(2540.0, 100.0).unitCheck())
        assertEquals(UnitCheck.WrongUnit(UnitFactor.UmWrittenAsNm), twoReferences(0.05, 50.0).unitCheck())
        assertEquals(UnitCheck.WrongUnit(UnitFactor.NmWrittenAsUm), twoReferences(50_000.0, 50.0).unitCheck())
        assertEquals(UnitCheck.WrongUnit(UnitFactor.MmWrittenAsNm), twoReferences(0.00005, 50.0).unitCheck())
        assertEquals(UnitCheck.WrongUnit(UnitFactor.NmWrittenAsMm), twoReferences(50_000_000.0, 50.0).unitCheck())
        val further = assertIs<UnitCheck.Mismatch>(twoReferences(100.0, 103.0).unitCheck())
        assertEquals(3.0, further.differenceMm, 1e-9)
        val closer = assertIs<UnitCheck.Mismatch>(twoReferences(100.0, 97.0).unitCheck())
        assertEquals(-3.0, closer.differenceMm, 1e-9)
        // Below 10 mm on the machine, a unit factor does not apply.
        val small = assertIs<UnitCheck.Mismatch>(twoReferences(0.2, 5.08).unitCheck())
        assertEquals(4.88, small.differenceMm, 1e-9)
        assertEquals(UnitCheck.Agrees, DrawingPlacement.load(listOf(point(0, 0))).unitCheck())
    }

    @Test
    fun applyScalesAWrongUnitAndPlacesTheShapes() {
        // The drawing is in inches but the file says mm: 4 "mm" apart in the drawing, 101.6 mm on the machine.
        val shapes = listOf(point(0, 0), point(4_000_000, 0), DrawingShape(type = ShapeType.Circle, x1 = 2_000_000, y1 = 0, radius = 125_000))
        val placement = DrawingPlacement.load(shapes)
            .setReference(0, Vec2(0.0, 0.0), Vec2(50_000.0, 10_000.0))
            .setReference(1, Vec2(4_000_000.0, 0.0), Vec2(151_600.0, 10_000.0))
        val placed = assertNotNull(placement.apply())
        assertEquals(listOf(50_000_000L, 10_000_000L), listOf(placed.shapes[0].x1, placed.shapes[0].y1))
        assertEquals(listOf(151_600_000L, 10_000_000L), listOf(placed.shapes[1].x1, placed.shapes[1].y1))
        assertEquals(listOf(100_800_000L, 10_000_000L, 3_175_000L), listOf(placed.shapes[2].x1, placed.shapes[2].y1, placed.shapes[2].radius))
        val holes = placed.snapGroups.single { group -> group.kind == SnapKind.CircleCenter }
        assertEquals(6350L, holes.diameterUm)
        assertTrue(placed.snapGroups.single { group -> group.kind == SnapKind.NamedPoint }.createSubDatums)
    }

    @Test
    fun applyKeepsThePointNames() {
        val shapes = listOf(DrawingShape(type = ShapeType.Point, x1 = 1_000_000, y1 = 2_000_000, name = "A1"))
        val placed = assertNotNull(DrawingPlacement.load(shapes).setReference(0, Vec2(1_000_000.0, 2_000_000.0), Vec2(0.0, 0.0)).apply())
        assertEquals("A1", placed.shapes.single().name)
        assertEquals(listOf(0L, 0L), listOf(placed.shapes.single().x1, placed.shapes.single().y1))
        assertEquals("A1", placed.snapGroups.single().points.single().name)
        // A mirror step keeps the name too.
        assertEquals("A1", DrawingPlacement.load(shapes).mirrorHorizontal().shapes.single().name)
    }

    @Test
    fun resetPutsTheDrawingBackToTheFileAsLoaded() {
        val shapes = listOf(point(10_000_000, 5_000_000))
        val changed = DrawingPlacement.load(shapes).rotateLeft().mirrorVertical().setReference(0, Vec2(0.0, 0.0), Vec2(5.0, 5.0))
        val reset = changed.reset()
        assertEquals(DrawingPlacement.load(shapes), reset)
        assertEquals(shapes, reset.shapes)
        assertTrue(reset.steps.isEmpty())
        assertEquals(0, reset.referenceCount)
    }

    @Test
    fun aLockedPlacementOrNoShapesIgnoresTheButtons() {
        val locked = DrawingPlacement.load(listOf(point(1, 1))).copy(locked = true)
        assertSame(locked, locked.rotateLeft())
        assertSame(locked, locked.setReference(0, Vec2.ZERO, Vec2.ZERO))
        assertNull(locked.apply())
        val empty = DrawingPlacement.load(emptyList())
        assertSame(empty, empty.mirrorHorizontal())
        assertNull(empty.apply())
    }

    @Test
    fun theStepTokensReadBack() {
        assertEquals(PlacementStep.RotateLeft, PlacementStep.parse("L"))
        assertEquals(PlacementStep.RotateRight, PlacementStep.parse("R"))
        assertEquals(PlacementStep.MirrorHorizontal, PlacementStep.parse("H"))
        assertEquals(PlacementStep.MirrorVertical, PlacementStep.parse("V"))
        assertEquals(PlacementStep.Scale(25.4), PlacementStep.parse(PlacementStep.Scale(25.4).token))
        assertNull(PlacementStep.parse("S:-1"))
        assertNull(PlacementStep.parse("X"))
    }
}
