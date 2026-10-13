package org.superdro.core.functions.drawing

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.superdro.core.model.DrawingShape
import org.superdro.core.model.Plane
import org.superdro.core.model.ShapeType

/** The snap points and the sub-datums from snap points. */
class SnapPointsTest {

    private fun line(x1: Long, y1: Long, x2: Long, y2: Long) = DrawingShape(type = ShapeType.Line, x1 = x1, y1 = y1, x2 = x2, y2 = y2)

    private val rectangle = DrawingShape(
        type = ShapeType.Polyline,
        closed = true,
        points = listOf(0, 0, 50_000_000, 0, 50_000_000, 30_000_000, 0, 30_000_000),
    )

    @Test
    fun aClosedRectangleHasFourCorners() {
        val corners = SnapPoints.corners(listOf(rectangle))
        assertEquals(
            setOf(0L to 0L, 50_000_000L to 0L, 50_000_000L to 30_000_000L, 0L to 30_000_000L),
            corners.map { corner -> corner.x to corner.y }.toSet(),
        )
        assertEquals(4, corners.size)
    }

    @Test
    fun fourLinesThatMeetAtRightAnglesHaveFourCorners() {
        val lines = listOf(
            line(0, 0, 10_000_000, 0),
            line(10_000_000, 0, 10_000_000, 10_000_000),
            line(10_000_000, 10_000_000, 0, 10_000_000),
            // This end is 999 nm from (0, 0): the same cluster.
            line(0, 10_000_000, 999, 999),
        )
        val corners = SnapPoints.corners(lines)
        assertEquals(4, corners.size)
        assertTrue(corners.any { corner -> corner.x == 0L && corner.y == 0L })
    }

    @Test
    fun aStraightJoinIsNotACornerButThreeEndsAre() {
        val straight = listOf(line(0, 0, 10_000_000, 0), line(10_000_000, 0, 20_000_000, 0))
        assertTrue(SnapPoints.corners(straight).isEmpty())
        // 171° between the two directions: not a corner. 169°: a corner.
        val at171 = listOf(line(0, 0, 10_000_000, 0), line(10_000_000, 0, 20_000_000, 1_583_844))
        assertTrue(SnapPoints.corners(at171).isEmpty())
        val at169 = listOf(line(0, 0, 10_000_000, 0), line(10_000_000, 0, 20_000_000, 1_943_803))
        assertEquals(1, SnapPoints.corners(at169).size)
        val tee = straight + line(10_000_000, 0, 10_000_000, 5_000_000)
        assertEquals(listOf(10_000_000L to 0L), SnapPoints.corners(tee).map { corner -> corner.x to corner.y })
        // One open end is not a corner.
        assertTrue(SnapPoints.corners(listOf(line(0, 0, 1, 1))).isEmpty())
    }

    @Test
    fun anArcAndALineThatMeetAtATangentAreNotACorner() {
        // The arc from (0, 0) about (0, 1000000), counterclockwise from −90°: its start tangent is +X.
        val arc = DrawingShape(type = ShapeType.Arc, x1 = 0, y1 = 1_000_000, radius = 1_000_000, startAngle = 1.5 * PI, sweepAngle = PI / 2.0)
        val tangent = listOf(line(-10_000_000, 0, 0, 0), arc)
        assertTrue(SnapPoints.corners(tangent).none { corner -> corner.x == 0L && corner.y == 0L })
        val square = listOf(line(0, -10_000_000, 0, 0), arc)
        assertTrue(SnapPoints.corners(square).any { corner -> corner.x == 0L && corner.y == 0L })
    }

    @Test
    fun theGroupsAndTheirDefaults() {
        val shapes = listOf(
            rectangle,
            DrawingShape(type = ShapeType.Circle, x1 = 10_000_000, y1 = 10_000_000, radius = 3_175_000),
            DrawingShape(type = ShapeType.Circle, x1 = 20_000_000, y1 = 10_000_000, radius = 3_175_000),
            DrawingShape(type = ShapeType.Circle, x1 = 30_000_000, y1 = 10_000_000, radius = 2_500_000),
            DrawingShape(type = ShapeType.Arc, x1 = 5_000_000, y1 = 5_000_000, radius = 1_000_000, startAngle = 0.0, sweepAngle = 1.0),
            DrawingShape(type = ShapeType.Point, x1 = 1_000_000, y1 = 2_000_000),
        )
        val groups = SnapPoints.extract(shapes)
        assertEquals(
            listOf(SnapKind.ArcCenter, SnapKind.Corner, SnapKind.NamedPoint, SnapKind.CircleCenter, SnapKind.CircleCenter),
            groups.map { group -> group.kind },
        )
        assertEquals(listOf(5000L, 6350L), groups.filter { group -> group.kind == SnapKind.CircleCenter }.map { group -> group.diameterUm })
        assertEquals(2, groups.last().points.size)
        // "Named points" has "Create as sub-datums" on. The other groups are shown and off.
        assertTrue(groups.single { group -> group.kind == SnapKind.NamedPoint }.createSubDatums)
        assertTrue(groups.filter { group -> group.kind != SnapKind.NamedPoint }.none { group -> group.createSubDatums })
        assertTrue(groups.all { group -> group.shown })
        assertEquals(listOf("Arc_Center", "Corner", "Point", "Hole_5mm", "Hole_6.35mm"), groups.map(SnapPoints::groupName))
    }

    @Test
    fun aNamedPointHasTheNameOfItsShape() {
        val shapes = listOf(
            DrawingShape(type = ShapeType.Point, x1 = 0, y1 = 0, name = "Datum A"),
            DrawingShape(type = ShapeType.Point, x1 = 5_000_000, y1 = 0),
        )
        val group = SnapPoints.extract(shapes).single()
        assertEquals(listOf("Datum A", null), group.points.map { point -> point.name })
        val subDatums = SnapPoints.toSubDatums(listOf(group), Plane.XY, emptyList())
        assertEquals(listOf("Datum A", "Point 2"), subDatums.map { subDatum -> subDatum.name })
    }

    @Test
    fun moreThan32CornersAreNotShown() {
        val zigzag = DrawingShape(
            type = ShapeType.Polyline,
            points = (0..34).flatMap { index -> listOf(index * 1_000_000L, if (index % 2 == 0) 0L else 1_000_000L) },
        )
        val corners = SnapPoints.extract(listOf(zigzag)).single { group -> group.kind == SnapKind.Corner }
        assertEquals(33, corners.points.size)
        assertFalse(corners.shown)
    }

    @Test
    fun theDiameterKeyRoundsTo1Um() {
        assertEquals(6350L, SnapPoints.diameterKeyUm(3_175_000))
        assertEquals(6351L, SnapPoints.diameterKeyUm(3_175_250))
        assertEquals(6350L, SnapPoints.diameterKeyUm(3_175_249))
        assertEquals("6.35", SnapPoints.millimeters(6350))
        assertEquals("10", SnapPoints.millimeters(10_000))
        assertEquals("12.345", SnapPoints.millimeters(12_345))
    }

    @Test
    fun theSubDatumNamesTagsAndPlaneAxes() {
        val holes = SnapGroup(SnapKind.CircleCenter, 6350L, listOf(SnapPoint(SnapKind.CircleCenter, 10_000_000, 5_000_000)), createSubDatums = true)
        val named = SnapGroup(
            SnapKind.NamedPoint,
            null,
            listOf(SnapPoint(SnapKind.NamedPoint, 0, 0, "A1"), SnapPoint(SnapKind.NamedPoint, 1000, 0, null)),
            createSubDatums = true,
        )
        val hidden = SnapGroup(SnapKind.Corner, null, listOf(SnapPoint(SnapKind.Corner, 0, 0)), createSubDatums = false)
        val xy = SnapPoints.toSubDatums(listOf(holes, named, hidden), Plane.XY, existingTags = listOf("hole_6.35mm"))
        assertEquals(listOf("⌀6.35 Hole 1", "A1", "Point 2"), xy.map { subDatum -> subDatum.name })
        assertEquals(listOf(listOf("Hole_6.35mm_2"), listOf("Point"), listOf("Point")), xy.map { subDatum -> subDatum.tags })
        assertEquals(listOf(10_000.0, 5_000.0, null), listOf(xy[0].x, xy[0].y, xy[0].z))
        val xz = SnapPoints.toSubDatums(listOf(holes), Plane.XZ, emptyList()).single()
        assertEquals(listOf(10_000.0, null, 5_000.0), listOf(xz.x, xz.y, xz.z))
        val yz = SnapPoints.toSubDatums(listOf(holes), Plane.YZ, emptyList()).single()
        assertEquals(listOf(null, 10_000.0, 5_000.0), listOf(yz.x, yz.y, yz.z))
        // The lathe: X = −v, Z = h.
        val zx = SnapPoints.toSubDatums(listOf(holes), Plane.ZX, emptyList()).single()
        assertEquals(listOf(-5_000.0, null, 10_000.0), listOf(zx.x, zx.y, zx.z))
        assertEquals("Arc Center 3", SnapPoints.subDatumName(SnapGroup(SnapKind.ArcCenter, null, emptyList()), SnapPoint(SnapKind.ArcCenter, 0, 0), 3))
        assertEquals("Corner 2", SnapPoints.subDatumName(hidden, SnapPoint(SnapKind.Corner, 0, 0), 2))
    }

    @Test
    fun aTagThatExistsGetsANumber() {
        assertEquals("Corner", SnapPoints.uniqueTag("Corner", listOf("Point")))
        assertEquals("Corner_2", SnapPoints.uniqueTag("Corner", listOf("CORNER")))
        assertEquals("Corner_3", SnapPoints.uniqueTag("Corner", listOf("corner", "Corner_2")))
    }

    @Test
    fun aPickTakesTheNearestShownPointInTheTolerance() {
        val groups = listOf(
            SnapGroup(SnapKind.Corner, null, listOf(SnapPoint(SnapKind.Corner, 0, 0), SnapPoint(SnapKind.Corner, 1000, 0))),
            SnapGroup(SnapKind.ArcCenter, null, listOf(SnapPoint(SnapKind.ArcCenter, 600, 0)), shown = false),
        )
        assertEquals(SnapPoint(SnapKind.Corner, 1000, 0), SnapPoints.pick(groups, 700.0, 0.0, toleranceNm = 500.0))
        assertNull(SnapPoints.pick(groups, 500.0, 600.0, toleranceNm = 500.0))
        assertEquals(24.0, SnapPoints.PICK_TOLERANCE_DP)
    }
}
