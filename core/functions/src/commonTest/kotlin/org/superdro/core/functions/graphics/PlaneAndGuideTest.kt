package org.superdro.core.functions.graphics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.superdro.core.functions.PointUm
import org.superdro.core.model.AxisId
import org.superdro.core.model.Guide
import org.superdro.core.model.Plane
import org.superdro.core.model.SubDatum

/** The plane table, the lathe sign, and the guide rules. */
class PlaneAndGuideTest {

    @Test
    fun eachPlaneHasItsAxesAndSign() {
        val table = Plane.entries.map { plane ->
            listOf(plane, PlaneAxes.horizontal(plane), PlaneAxes.vertical(plane), PlaneAxes.verticalSign(plane))
        }
        assertEquals(
            listOf(
                listOf(Plane.XY, AxisId.X, AxisId.Y, 1),
                listOf(Plane.XZ, AxisId.X, AxisId.Z, 1),
                listOf(Plane.YZ, AxisId.Y, AxisId.Z, 1),
                listOf(Plane.ZX, AxisId.Z, AxisId.X, -1),
            ),
            table,
        )
    }

    @Test
    fun theLatheUsesOneSignForThePointsTheToolTheGuidesAndTheImage() {
        val point = SubDatum(x = 10_000.0, z = -30_000.0)
        assertEquals(PointUm(-30_000.0, -10_000.0), PlaneAxes.viewOf(Plane.ZX, point))
        val tool = PlaneAxes.viewOf(Plane.ZX, x = 10_000.0, y = null, z = -30_000.0)
        assertEquals(PointUm(-30_000.0, -10_000.0), tool)
        // A horizontal guide on X = 10000 µm is at the same v as the point.
        val guide = GuideRules.viewOf(Plane.ZX, Guide(id = "g", axis = AxisId.X, valueUm = 10_000.0))
        assertEquals(ViewGuide("g", GuideOrientation.Horizontal, -10_000.0), guide)
        // An image pixel that the stored matrix maps to the machine values (Z, X) shows at the
        // screen position of the point.
        val transform = ViewTransform(400.0, 300.0, 1.0, ScreenMetrics(254.0, 254.0, 1.0))
        val reference = ImageReference.fromCard(Vec2(10.0, 20.0), horizontalUm = -30_000.0, verticalUm = 10_000.0)
        val imageMatrix = Matrix3.translation(reference.machine.x - 10.0, reference.machine.y - 20.0)
        val screen = transform.imageToScreen(imageMatrix, Plane.ZX).map(reference.pixel)
        assertNear(transform.toScreen(PointUm(-30_000.0, -10_000.0)), screen, 1e-9)
        assertNear(Vec2(100.0, 400.0), screen, 1e-9)
        // The inverse gives the axis values back.
        assertEquals(-30_000.0 to 10_000.0, PlaneAxes.fromView(Plane.ZX, PointUm(-30_000.0, -10_000.0)))
    }

    @Test
    fun aPointWithoutBothPlaneCoordinatesIsNotInTheView() {
        val flat = SubDatum(x = 1.0, y = 2.0)
        assertEquals(PointUm(1.0, 2.0), PlaneAxes.viewOf(Plane.XY, flat))
        assertNull(PlaneAxes.viewOf(Plane.XZ, flat))
        assertNull(PlaneAxes.viewOf(Plane.YZ, flat))
    }

    @Test
    fun theGuideMapOfEachPlane() {
        val rows = listOf(Plane.ZX, Plane.XY, Plane.XZ, Plane.YZ).map { plane ->
            Triple(plane, GuideRules.axisOf(plane, GuideOrientation.Horizontal), GuideRules.axisOf(plane, GuideOrientation.Vertical))
        }
        assertEquals(
            listOf(
                Triple(Plane.ZX, AxisId.X, AxisId.Z),
                Triple(Plane.XY, AxisId.Y, AxisId.X),
                Triple(Plane.XZ, AxisId.Z, AxisId.X),
                Triple(Plane.YZ, AxisId.Z, AxisId.Y),
            ),
            rows,
        )
    }

    @Test
    fun aGuideShowsOnlyInAPlaneThatHasItsAxis() {
        val zGuide = Guide(id = "z", axis = AxisId.Z, valueUm = -5000.0)
        assertNull(GuideRules.viewOf(Plane.XY, zGuide))
        assertEquals(ViewGuide("z", GuideOrientation.Horizontal, -5000.0), GuideRules.viewOf(Plane.XZ, zGuide))
        assertEquals(ViewGuide("z", GuideOrientation.Vertical, -5000.0), GuideRules.viewOf(Plane.ZX, zGuide))
        val xGuide = Guide(id = "x", axis = AxisId.X, valueUm = 7000.0)
        assertEquals(ViewGuide("x", GuideOrientation.Vertical, 7000.0), GuideRules.viewOf(Plane.XY, xGuide))
        assertNull(GuideRules.viewOf(Plane.YZ, xGuide))
    }

    @Test
    fun theAppDoesNotAddADuplicateGuide() {
        val guides = listOf(Guide(axis = AxisId.X, valueUm = 25_400.0))
        assertNull(GuideRules.newGuide(guides, Plane.XY, GuideOrientation.Vertical, 25_400.0))
        assertNull(GuideRules.newGuide(guides, Plane.XY, GuideOrientation.Vertical, 25_400.4))
        // The same value on a different axis is a different guide.
        val horizontal = assertNotNull(GuideRules.newGuide(guides, Plane.XY, GuideOrientation.Horizontal, 25_400.0))
        assertEquals(AxisId.Y, horizontal.axis)
        assertEquals(25_400.0, horizontal.valueUm)
        assertNotNull(GuideRules.newGuide(guides, Plane.XY, GuideOrientation.Vertical, 25_401.0))
    }

    @Test
    fun eachGuideButtonUsesItsOwnCoordinateAndIsDisabledWhenItIsAbsent() {
        val full = GuideRules.buttonsFor(Plane.XY, SubDatum(x = 10_000.0, y = 20_000.0))
        // "Add Horizontal Guide Here" uses the vertical coordinate (Y); "Add Vertical Guide Here" uses X.
        assertEquals(20_000.0, full.horizontalGuideValue)
        assertEquals(10_000.0, full.verticalGuideValue)
        val onlyX = GuideRules.buttonsFor(Plane.XY, SubDatum(x = 10_000.0))
        assertTrue(onlyX.verticalEnabled)
        assertFalse(onlyX.horizontalEnabled)
        assertNull(onlyX.horizontalGuideValue)
        val lathe = GuideRules.buttonsFor(Plane.ZX, SubDatum(x = 5000.0, z = -2000.0))
        assertEquals(5000.0, lathe.horizontalGuideValue)
        assertEquals(-2000.0, lathe.verticalGuideValue)
    }
}
