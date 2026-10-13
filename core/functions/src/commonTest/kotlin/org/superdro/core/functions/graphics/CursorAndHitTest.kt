package org.superdro.core.functions.graphics

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.superdro.core.functions.PointUm
import org.superdro.core.model.MachineType
import org.superdro.core.model.Plane

/** The ruler ticks, the cutter, the hit areas, and the gesture actions. */
class CursorAndHitTest {

    @Test
    fun inchTicksShowMajorAndMinorWhenTheFineIntervalIsBelow3Dp() {
        // 254 dpi: fine 0.01 in = 2.54 px < 3 dp (4.5 px); minor 0.1 in = 25.4 px; major 1 in = 254 px.
        val metrics = ScreenMetrics(254.0, 254.0, 1.5)
        val ticks = CursorGeometry.rulerTicks(metrics, scale = 1.0, metric = false, halfLengthPx = 600.0)
        assertEquals(23, ticks.size)
        assertTrue(ticks.none { tick -> tick.kind == TickKind.Fine })
        val majors = ticks.filter { tick -> tick.kind == TickKind.Major }.map { tick -> tick.offsetPx }
        assertEquals(2, majors.size)
        assertEquals(254.0, majors[0], 1e-9)
        assertEquals(508.0, majors[1], 1e-9)
        assertEquals(25.4, ticks.first().offsetPx, 1e-9)
        assertEquals(TickKind.Minor, ticks.first().kind)
        assertEquals(0.12 * 254.0, ticks.first().lengthPx, 1e-9)
        assertEquals(0.3 * 254.0, ticks[9].lengthPx, 1e-9)
    }

    @Test
    fun metricTicksAreEach10Mm5MmAnd1Mm() {
        val metrics = ScreenMetrics(254.0, 254.0, 1.5)
        val ticks = CursorGeometry.rulerTicks(metrics, scale = 1.0, metric = true, halfLengthPx = 200.0)
        // 10 px for each mm: 20 ticks to 200 px.
        assertEquals(20, ticks.size)
        assertEquals(TickKind.Fine, ticks[0].kind)
        assertEquals(TickKind.Minor, ticks[4].kind)
        assertEquals(TickKind.Major, ticks[9].kind)
        assertEquals(TickKind.Minor, ticks[14].kind)
        assertEquals(TickKind.Major, ticks[19].kind)
        assertEquals(0.06 * 254.0, ticks[0].lengthPx, 1e-9)
        assertEquals(100.0, ticks[9].offsetPx, 1e-9)
    }

    @Test
    fun ticksBelow3DpDoNotShow() {
        val metrics = ScreenMetrics(254.0, 254.0, 1.5)
        // Scale 0.1: fine 1 px and minor 5 px; 5 px ≥ 4.5 px.
        val minor = CursorGeometry.rulerTicks(metrics, scale = 0.1, metric = true, halfLengthPx = 100.0)
        assertEquals(setOf(TickKind.Minor, TickKind.Major), minor.map { tick -> tick.kind }.toSet())
        // Scale 0.05: minor 2.5 px < 4.5 px; only the majors (each 5 px) show.
        val major = CursorGeometry.rulerTicks(metrics, scale = 0.05, metric = true, halfLengthPx = 10.5)
        assertEquals(2, major.size)
        assertEquals(5.0, major[0].offsetPx, 1e-9)
        assertEquals(10.0, major[1].offsetPx, 1e-9)
        assertTrue(major.all { tick -> tick.kind == TickKind.Major })
        // At density 1, 3 dp is 3 px. A fine interval of 3.01 px shows; 2.99 px does not.
        val above = CursorGeometry.rulerTicks(ScreenMetrics(25.4 * 3.01, 25.4 * 3.01, 1.0), 1.0, metric = true, halfLengthPx = 4.0)
        assertEquals(listOf(TickKind.Fine), above.map { tick -> tick.kind })
        val below = CursorGeometry.rulerTicks(ScreenMetrics(25.4 * 2.99, 25.4 * 2.99, 1.0), 1.0, metric = true, halfLengthPx = 4.0)
        assertEquals(emptyList(), below)
    }

    @Test
    fun theSmallCrossIsOneInchOrOneCentimeter() {
        val metrics = ScreenMetrics(200.0, 180.0, 1.0)
        assertEquals(Vec2(200.0, 180.0), CursorGeometry.smallCrossPx(metrics, 1.0, metric = false))
        val metric = CursorGeometry.smallCrossPx(metrics, 2.0, metric = true)
        assertEquals(200.0 * 10.0 / 25.4 * 2.0, metric.x, 1e-9)
        assertEquals(180.0 * 10.0 / 25.4 * 2.0, metric.y, 1e-9)
    }

    @Test
    fun theCutterCenterIsTheToolPositionMinusTheToolOffsetVector() {
        val tool = PointUm(30_000.0, 20_000.0)
        val mill = CursorGeometry.cutter(Plane.XY, MachineType.VerticalMill, tool, 5000.0, -5000.0, 10_000.0)
        assertEquals(CutterCircle(PointUm(25_000.0, 25_000.0), 5000.0), mill)
        assertEquals(CutterCircle(tool, 0.0), CursorGeometry.cutter(Plane.ZX, MachineType.Lathe, tool, 5000.0, 0.0, 10_000.0))
        assertEquals(CutterCircle(tool, 0.0), CursorGeometry.cutter(Plane.XZ, MachineType.VerticalMill, tool, 5000.0, 0.0, 10_000.0))
    }

    // 200 dpi at scale 1: 0.25 in = 50 px = 6350 µm.
    private val metrics = ScreenMetrics(200.0, 200.0, 1.25)
    private val transform = ViewTransform(500.0, 500.0, 1.0, metrics)

    @Test
    fun thePointHitAreaIsASquareOfAQuarterInch() {
        val points = listOf(ViewPoint("p", PointUm(0.0, 0.0), 1))
        assertEquals("p", GraphHitTest.hitPoint(Vec2(550.0, 500.0), transform, points)?.id)
        assertEquals("p", GraphHitTest.hitPoint(Vec2(550.0, 450.0), transform, points)?.id)
        assertEquals(null, GraphHitTest.hitPoint(Vec2(550.01, 500.0), transform, points))
        assertEquals(null, GraphHitTest.hitPoint(Vec2(500.0, 449.99), transform, points))
    }

    @Test
    fun theNearestPointWinsAndOnEqualDistanceTheHigherRankWins() {
        val points = listOf(
            ViewPoint("far", PointUm(2540.0, 0.0), 9),
            ViewPoint("near", PointUm(1270.0, 0.0), 1),
        )
        assertEquals("near", GraphHitTest.hitPoint(Vec2(500.0, 500.0), transform, points)?.id)
        val tie = listOf(ViewPoint("old", PointUm(-1270.0, 0.0), 1), ViewPoint("new", PointUm(1270.0, 0.0), 2))
        assertEquals("new", GraphHitTest.hitPoint(Vec2(500.0, 500.0), transform, tie)?.id)
        assertEquals("new", GraphHitTest.hitPoint(Vec2(500.0, 500.0), transform, tie.reversed())?.id)
    }

    @Test
    fun theGuideHitAreaIsABandOfAnEighthInchAndTheNearerGuideWins() {
        // h = 25400 µm is x = 700 px. 0.125 in = 25 px.
        val guides = listOf(
            ViewGuide("v", GuideOrientation.Vertical, 25_400.0),
            ViewGuide("h", GuideOrientation.Horizontal, 0.0),
        )
        assertEquals("v", GraphHitTest.hitGuide(Vec2(725.0, 100.0), transform, guides)?.id)
        assertEquals(null, GraphHitTest.hitGuide(Vec2(725.01, 100.0), transform, guides))
        assertEquals("h", GraphHitTest.hitGuide(Vec2(100.0, 475.0), transform, guides)?.id)
        // Near both: x 10 px from the vertical guide, y 20 px from the horizontal guide.
        assertEquals("v", GraphHitTest.hitGuide(Vec2(710.0, 520.0), transform, guides)?.id)
        assertEquals("h", GraphHitTest.hitGuide(Vec2(720.0, 505.0), transform, guides)?.id)
    }

    @Test
    fun theToolHitAreaIsRoot2TimesAQuarterInchPlusTheToolRadius() {
        // Radius 12700 µm = 100 px; √2 × 0.25 in = 70.71 px.
        val cutter = CutterCircle(PointUm(0.0, 0.0), 12_700.0)
        val limit = sqrt(2.0) * 0.25 * 200.0 + 100.0
        assertTrue(GraphHitTest.hitsTool(Vec2(500.0 + limit, 500.0), transform, cutter))
        assertTrue(!GraphHitTest.hitsTool(Vec2(500.0 + limit + 0.01, 500.0), transform, cutter))
        assertEquals(GraphHit.Tool, GraphHitTest.hit(Vec2(600.0, 600.0), transform, emptyList(), emptyList(), cutter))
    }

    @Test
    fun theHitTestOrderIsPointGuideTool() {
        val point = ViewPoint("p", PointUm(0.0, 0.0), 1)
        val guide = ViewGuide("g", GuideOrientation.Vertical, 0.0)
        val cutter = CutterCircle(PointUm(0.0, 0.0), 1000.0)
        val touch = Vec2(505.0, 505.0)
        assertEquals(GraphHit.Point(point), GraphHitTest.hit(touch, transform, listOf(point), listOf(guide), cutter))
        assertEquals(GraphHit.Guide(guide), GraphHitTest.hit(touch, transform, emptyList(), listOf(guide), cutter))
        assertEquals(GraphHit.Tool, GraphHitTest.hit(touch, transform, emptyList(), emptyList(), cutter))
        val empty = GraphHitTest.hit(Vec2(700.0, 300.0), transform, emptyList(), emptyList(), cutter)
        assertTrue(empty is GraphHit.Empty)
        assertNear(PointUm(25_400.0, 25_400.0), empty.position, 1e-6)
    }

    private fun context(
        selection: GraphSelection = GraphSelection.None,
        selectedPoint: ViewPoint? = null,
        machineType: MachineType = MachineType.VerticalMill,
        plane: Plane = Plane.XY,
        diameter: Double = 10_000.0,
        offsetActive: Boolean = false,
    ) = GraphActionContext(machineType, plane, selection, selectedPoint, diameter, offsetActive)

    @Test
    fun theTapActionsOfTheTable() {
        val point = GraphHit.Point(ViewPoint("p", PointUm(0.0, 0.0), 1))
        assertEquals(
            GraphTapAction.Select(GraphSelection.Point("p"), snapToPointOff = true, snapToGuideOff = false),
            GraphHitTest.tapAction(point, context()),
        )
        assertEquals(
            GraphTapAction.Select(GraphSelection.None, snapToPointOff = true, snapToGuideOff = false),
            GraphHitTest.tapAction(point, context(selection = GraphSelection.Point("p"))),
        )
        val guide = GraphHit.Guide(ViewGuide("g", GuideOrientation.Vertical, 0.0))
        assertEquals(
            GraphTapAction.Select(GraphSelection.Guide("g"), snapToPointOff = false, snapToGuideOff = true),
            GraphHitTest.tapAction(guide, context(selection = GraphSelection.Point("p"))),
        )
        assertEquals(
            GraphTapAction.Select(GraphSelection.None, snapToPointOff = false, snapToGuideOff = true),
            GraphHitTest.tapAction(guide, context(selection = GraphSelection.Guide("g"))),
        )
        assertEquals(GraphTapAction.OpenDirectionPicker, GraphHitTest.tapAction(GraphHit.Tool, context()))
        assertEquals(GraphTapAction.NoAction, GraphHitTest.tapAction(GraphHit.Tool, context(diameter = 0.0)))
        assertEquals(GraphTapAction.NoAction, GraphHitTest.tapAction(GraphHit.Tool, context(plane = Plane.XZ)))
        assertEquals(GraphTapAction.NoAction, GraphHitTest.tapAction(GraphHit.Tool, context(machineType = MachineType.Lathe, plane = Plane.ZX)))
        assertEquals(GraphTapAction.NoAction, GraphHitTest.tapAction(GraphHit.Empty(PointUm(1.0, 2.0)), context()))
    }

    @Test
    fun theLongPressActionsOfTheTable() {
        val pressed = ViewPoint("b", PointUm(0.0, 0.0), 2)
        val selected = ViewPoint("a", PointUm(1.0, 1.0), 1)
        assertEquals(GraphLongPressAction.OpenSubDatumMenu("b"), GraphHitTest.longPressAction(GraphHit.Point(pressed), context()))
        assertEquals(
            GraphLongPressAction.OpenPairCommands("a", "b"),
            GraphHitTest.longPressAction(GraphHit.Point(pressed), context(GraphSelection.Point("a"), selected)),
        )
        assertEquals(
            GraphLongPressAction.OpenSubDatumMenu("b"),
            GraphHitTest.longPressAction(GraphHit.Point(pressed), context(GraphSelection.Point("b"), pressed)),
        )
        val guide = GraphHit.Guide(ViewGuide("g", GuideOrientation.Horizontal, 0.0))
        assertEquals(GraphLongPressAction.AskDeleteGuide("g"), GraphHitTest.longPressAction(guide, context()))
        assertEquals(GraphLongPressAction.AskClearRadiusCompensation, GraphHitTest.longPressAction(GraphHit.Tool, context()))
        assertEquals(
            GraphLongPressAction.AskClearRadiusCompensation,
            GraphHitTest.longPressAction(GraphHit.Tool, context(diameter = 0.0, offsetActive = true)),
        )
        assertEquals(GraphLongPressAction.OpenRadiusCompensation, GraphHitTest.longPressAction(GraphHit.Tool, context(diameter = 0.0)))
        assertEquals(GraphLongPressAction.NoAction, GraphHitTest.longPressAction(GraphHit.Tool, context(plane = Plane.YZ)))
        assertEquals(
            GraphLongPressAction.OpenAddGuide(PointUm(3.0, 4.0)),
            GraphHitTest.longPressAction(GraphHit.Empty(PointUm(3.0, 4.0)), context()),
        )
    }
}
