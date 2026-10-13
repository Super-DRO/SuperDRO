package org.superdro.core.functions.transform

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.superdro.core.functions.graphics.Matrix3
import org.superdro.core.functions.graphics.assertNear
import org.superdro.core.model.AxisId
import org.superdro.core.model.Drawing
import org.superdro.core.model.DrawingShape
import org.superdro.core.model.Guide
import org.superdro.core.model.ReferenceImage
import org.superdro.core.model.ShapeType
import org.superdro.core.model.SubDatum
import org.superdro.core.model.Workspace

/** The workspace transforms and the worked examples. */
class WorkspaceTransformTest {

    private fun point(x: Double?, y: Double?, z: Double? = null) =
        SubDatum(id = "p", x = x, y = y, z = z, createdOn = GUIDE_TIME, updatedOn = GUIDE_TIME)

    @Test
    fun flipAcrossXAt25Mm() {
        val flip = FlipTransform(FlipAxis.AcrossX, lineUm = 25_000.0)
        val result = flip.mapPoint(point(10_000.0, 40_000.0, -2_000.0))
        assertEquals(listOf(10_000.0, 10_000.0, -2_000.0), listOf(result.x, result.y, result.z))
        assertEquals(GuideMapResult.Kept(Guide(id = "y", axis = AxisId.Y, valueUm = 20_000.0, createdOn = GUIDE_TIME)), flip.mapGuide(guide("y", AxisId.Y, 30_000.0), GuidePolicy.Delete))
        assertEquals(GuideMapResult.Kept(guide("x", AxisId.X, 5_000.0)), flip.mapGuide(guide("x", AxisId.X, 5_000.0), GuidePolicy.Delete))
        assertEquals(Matrix3(1.0, 0.0, 0.0, 0.0, -1.0, 50_000.0, 0.0, 0.0, 1.0), flip.imageMatrix)
        val shape = flip.mapShape(DrawingShape(type = ShapeType.Point, x1 = 0, y1 = 40_000_000))
        assertEquals(10_000_000L, shape.y1)
        val arc = flip.mapShape(DrawingShape(type = ShapeType.Arc, x1 = 0, y1 = 0, radius = 1000, startAngle = 0.5, sweepAngle = 1.0))
        assertEquals(5.7832, assertNotNull(arc.startAngle), 0.00005)
        assertEquals(-1.0, arc.sweepAngle)
        assertFalse(flip.breaksGuides)
        assertFalse(flip.keepsAxisAlignment)
    }

    @Test
    fun flipAcrossYAndTheZeroRule() {
        val flip = FlipTransform(FlipAxis.AcrossY, lineUm = 5_000.0)
        val result = flip.mapPoint(point(10_000.0, 40_000.0))
        assertEquals(listOf(0.0, 40_000.0), listOf(result.x, result.y))
        // −0.0 becomes +0.0.
        assertEquals("0.0", result.x.toString())
        assertEquals(Matrix3(-1.0, 0.0, 10_000.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0), flip.imageMatrix)
        val arc = flip.mapShape(DrawingShape(type = ShapeType.Arc, x1 = 1_000_000, y1 = 0, radius = 1000, startAngle = 0.5, sweepAngle = 1.0))
        assertEquals(9_000_000L, arc.x1)
        assertEquals(PI - 0.5, assertNotNull(arc.startAngle), 1e-12)
        assertEquals(GuideMapResult.Kept(guide("x", AxisId.X, -2_000.0)), flip.mapGuide(guide("x", AxisId.X, 12_000.0), GuidePolicy.Delete))
        assertFailsWith<IllegalArgumentException> { FlipTransform(FlipAxis.AcrossX, Double.NaN) }
    }

    @Test
    fun rotate30DegreesAbout10And0() {
        val rotate = RotateTransform.ofDegrees(30.0, pivotXUm = 10_000.0, pivotYUm = 0.0)
        val result = rotate.mapPoint(point(20_000.0, 0.0, 3_000.0))
        assertEquals(18_660.254, assertNotNull(result.x), 0.0005)
        assertEquals(5_000.0, assertNotNull(result.y), 1e-9)
        assertEquals(3_000.0, result.z)
        // The guide X = 30 mm: "Continue" deletes it; "Convert and Continue" gives the 2D point (27.321, 10.000) mm.
        val guide = guide("g", AxisId.X, 30_000.0)
        assertTrue(rotate.breaksGuides)
        assertTrue(rotate.breaksGuide(guide))
        assertEquals(GuideMapResult.Deleted, rotate.mapGuide(guide, GuidePolicy.Delete))
        val converted = assertIs<GuideMapResult.ConvertedToPoint>(rotate.mapGuide(guide, GuidePolicy.Convert))
        assertEquals(27_320.508, converted.xUm, 0.0005)
        assertEquals(10_000.0, converted.yUm, 1e-9)
        assertEquals("30°", rotate.label)
        // A guide on Z does not change and does not count.
        val zGuide = guide("z", AxisId.Z, -1_000.0)
        assertFalse(rotate.breaksGuide(zGuide))
        assertEquals(GuideMapResult.Kept(zGuide), rotate.mapGuide(zGuide, GuidePolicy.Delete))
        assertEquals(2, rotate.countBrokenGuides(listOf(guide, zGuide, guide("y", AxisId.Y, 4.0))))
    }

    @Test
    fun rotate90DegreesIsExactAndKeepsTheGuides() {
        val rotate = RotateTransform.ofDegrees(90.0, 0.0, 0.0)
        val result = rotate.mapPoint(point(20_000.0, 5_000.0))
        assertEquals(listOf(-5_000.0, 20_000.0), listOf(result.x, result.y))
        assertEquals(GuideMapResult.Kept(guide("g", AxisId.Y, 10_000.0)), rotate.mapGuide(guide("g", AxisId.X, 10_000.0), GuidePolicy.Delete))
        assertEquals(GuideMapResult.Kept(guide("h", AxisId.X, -7_000.0)), rotate.mapGuide(guide("h", AxisId.Y, 7_000.0), GuidePolicy.Delete))
        assertFalse(rotate.breaksGuides)
        val half = RotateTransform.ofDegrees(180.0, 1_000.0, 2_000.0)
        assertEquals(GuideMapResult.Kept(guide("g", AxisId.X, -8_000.0)), half.mapGuide(guide("g", AxisId.X, 10_000.0), GuidePolicy.Delete))
        assertEquals(GuideMapResult.Kept(guide("h", AxisId.Y, -3_000.0)), half.mapGuide(guide("h", AxisId.Y, 7_000.0), GuidePolicy.Delete))
        val line = rotate.mapShape(DrawingShape(type = ShapeType.Line, x1 = 20_000_000, y1 = 5_000_000, x2 = 0, y2 = 0))
        assertEquals(listOf(-5_000_000L, 20_000_000L), listOf(line.x1, line.y1))
    }

    @Test
    fun theRotateAngleIsNormalizedTo180() {
        assertEquals(-90.0, RotateTransform.ofDegrees(270.0, 0.0, 0.0).angleDegrees)
        assertEquals(180.0, RotateTransform.ofDegrees(-180.0, 0.0, 0.0).angleDegrees)
        assertEquals(180.0, RotateTransform.ofDegrees(540.0, 0.0, 0.0).angleDegrees)
        assertEquals(10.0, RotateTransform.ofDegrees(-350.0, 0.0, 0.0).angleDegrees, 1e-12)
        assertTrue(RotateTransform.ofDegrees(360.0, 5.0, 5.0).isIdentity)
        assertEquals("-12.3457°", RotateTransform.ofDegrees(-12.345678, 0.0, 0.0).label)
        // The image matrix of a rotation about a pivot.
        val rotate = RotateTransform.ofDegrees(90.0, 10_000.0, 0.0)
        assertEquals(Matrix3(0.0, -1.0, 10_000.0, 1.0, 0.0, -10_000.0, 0.0, 0.0, 1.0), rotate.imageMatrix)
        // A row without Y does not change.
        assertEquals(point(5.0, null, 1.0), rotate.mapPoint(point(5.0, null, 1.0)))
    }

    @Test
    fun scale150PercentAbout100And50And0() {
        val scale = ScaleTransform.ofPercent(150.0, pivotXUm = 100_000.0, pivotYUm = 50_000.0, pivotZUm = 0.0)
        val result = scale.mapPoint(point(120_000.0, 50_000.0, -10_000.0))
        assertEquals(listOf(130_000.0, 50_000.0, -15_000.0), listOf(result.x, result.y, result.z))
        assertEquals(GuideMapResult.Kept(guide("y", AxisId.Y, 65_000.0)), scale.mapGuide(guide("y", AxisId.Y, 60_000.0), GuidePolicy.Delete))
        assertEquals(Matrix3(1.5, 0.0, -50_000.0, 0.0, 1.5, -25_000.0, 0.0, 0.0, 1.0), scale.imageMatrix)
        assertTrue(scale.keepsAxisAlignment)
        // Without a Z pivot, the scale does not use Z.
        val flat = ScaleTransform.ofPercent(150.0, 100_000.0, 50_000.0)
        assertEquals(-10_000.0, flat.mapPoint(point(120_000.0, 50_000.0, -10_000.0)).z)
        assertEquals(GuideMapResult.Kept(guide("z", AxisId.Z, -4_000.0)), flat.mapGuide(guide("z", AxisId.Z, -4_000.0), GuidePolicy.Delete))
        assertFalse(flat.usesZ)
        val shape = scale.mapShape(DrawingShape(type = ShapeType.Circle, x1 = 120_000_000, y1 = 50_000_000, radius = 1_000_000))
        assertEquals(listOf(130_000_000L, 50_000_000L, 1_500_000L), listOf(shape.x1, shape.y1, shape.radius))
        assertTrue(ScaleTransform.ofPercent(100.0, 1.0, 2.0).isIdentity)
        assertFailsWith<IllegalArgumentException> { ScaleTransform.ofPercent(0.0, 0.0, 0.0) }
        assertFailsWith<IllegalArgumentException> { ScaleTransform.ofPercent(-50.0, 0.0, 0.0) }
    }

    @Test
    fun translateMovesOnlyTheCoordinatesThatExist() {
        val translate = TranslateTransform(-25_000.0, -40_000.0, 5_000.0)
        val result = translate.mapPoint(point(30_000.0, 50_000.0, 0.0))
        assertEquals(listOf(5_000.0, 10_000.0, 5_000.0), listOf(result.x, result.y, result.z))
        assertEquals(point(5_000.0, null), translate.mapPoint(point(30_000.0, null)))
        assertEquals(Matrix3.translation(-25_000.0, -40_000.0), translate.imageMatrix)
        val shape = translate.mapShape(DrawingShape(type = ShapeType.Line, x1 = 0, y1 = 0, x2 = 1, y2 = 1))
        assertEquals(listOf(-25_000_000L, -40_000_000L, -24_999_999L, -39_999_999L), listOf(shape.x1, shape.y1, shape.x2, shape.y2))
        assertEquals(GuideMapResult.Kept(guide("z", AxisId.Z, 6_000.0)), translate.mapGuide(guide("z", AxisId.Z, 1_000.0), GuidePolicy.Delete))
        assertTrue(TranslateTransform(0.0, 0.0).isIdentity)
    }

    @Test
    fun aTransformChangesAllStoredGeometryOfTheWorkspace() {
        val subDatum = SubDatum(id = "s", name = "Hole", tags = listOf("Holes", "Pocket"), x = 20_000.0, y = 0.0)
        val workspace = Workspace(
            activeSubDatumId = "s",
            subDatums = listOf(subDatum),
            guides = listOf(guide("gx", AxisId.X, 30_000.0), guide("gz", AxisId.Z, -1_000.0)),
            referenceImage = ReferenceImage(fileName = "images/a.jpg", matrix = Matrix3.scale(100.0, -100.0).toList()),
        )
        val drawing = Drawing(id = "d", shapes = listOf(DrawingShape(type = ShapeType.Point, x1 = 20_000_000, y1 = 0)))
        val rotate = RotateTransform.ofDegrees(30.0, 10_000.0, 0.0)
        val outcome = rotate.applyTo(workspace, drawing, GuidePolicy.Convert)
        assertTrue(outcome.changed)
        // The active sub-datum is not active after the transform.
        assertEquals(null, outcome.workspace.activeSubDatumId)
        val moved = outcome.workspace.subDatums.first()
        assertEquals(18_660.254, assertNotNull(moved.x), 0.0005)
        // All tags of a row stay.
        assertEquals(listOf("Holes", "Pocket"), moved.tags)
        assertEquals(listOf("gx"), outcome.deletedGuideIds)
        assertEquals(listOf("Guide"), outcome.convertedPoints.map { point -> point.name })
        assertEquals(2, outcome.workspace.subDatums.size)
        assertEquals(listOf("gz"), outcome.workspace.guides.map { guide -> guide.id })
        // The image matrix: the rotation after the stored matrix.
        val expected = Matrix3.scale(100.0, -100.0).then(rotate.imageMatrix)
        assertNear(expected, Matrix3.of(assertNotNull(outcome.workspace.referenceImage).matrix), 1e-12)
        assertEquals("images/a.jpg", outcome.workspace.referenceImage?.fileName)
        val shape = assertNotNull(outcome.drawing).shapes.single()
        assertEquals(18_660_254L, shape.x1)
        assertEquals(5_000_000L, shape.y1)
        // An identity transform writes nothing.
        val identity = RotateTransform.ofDegrees(0.0, 0.0, 0.0).applyTo(workspace, drawing, GuidePolicy.Delete)
        assertFalse(identity.changed)
        assertEquals(workspace, identity.workspace)
    }

    private fun guide(id: String, axis: AxisId, value: Double) = Guide(id = id, axis = axis, valueUm = value, createdOn = GUIDE_TIME)

    private companion object {
        val GUIDE_TIME = Guide().createdOn
    }
}
