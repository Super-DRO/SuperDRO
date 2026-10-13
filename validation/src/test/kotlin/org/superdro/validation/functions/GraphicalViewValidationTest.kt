package org.superdro.validation.functions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.superdro.core.functions.PointUm
import org.superdro.core.functions.graphics.AutoGuideActions
import org.superdro.core.functions.graphics.AutoPlungeActions
import org.superdro.core.functions.graphics.ColorMatrix
import org.superdro.core.functions.graphics.ContrastPreservingGray
import org.superdro.core.functions.graphics.CursorGeometry
import org.superdro.core.functions.graphics.CutterCircle
import org.superdro.core.functions.graphics.GraphActionContext
import org.superdro.core.functions.graphics.GraphHit
import org.superdro.core.functions.graphics.GraphHitTest
import org.superdro.core.functions.graphics.GraphLongPressAction
import org.superdro.core.functions.graphics.GraphSelection
import org.superdro.core.functions.graphics.GraphTapAction
import org.superdro.core.functions.graphics.GraphicalViewport
import org.superdro.core.functions.graphics.GrayWeights
import org.superdro.core.functions.graphics.GuideOrientation
import org.superdro.core.functions.graphics.GuideRules
import org.superdro.core.functions.graphics.GuideSnapper
import org.superdro.core.functions.graphics.ImageFitQuality
import org.superdro.core.functions.graphics.ImageFitRefusal
import org.superdro.core.functions.graphics.ImageFitResult
import org.superdro.core.functions.graphics.ImageReference
import org.superdro.core.functions.graphics.Matrix3
import org.superdro.core.functions.graphics.PartRotationTransform
import org.superdro.core.functions.graphics.PlaneAxes
import org.superdro.core.functions.graphics.Plunge
import org.superdro.core.functions.graphics.PlungeWatcher
import org.superdro.core.functions.graphics.ReferenceImageMath
import org.superdro.core.functions.graphics.ScreenMetrics
import org.superdro.core.functions.graphics.SnapToPoint
import org.superdro.core.functions.graphics.TickKind
import org.superdro.core.functions.graphics.ToolOffsetChange
import org.superdro.core.functions.graphics.Vec2
import org.superdro.core.functions.graphics.ViewGuide
import org.superdro.core.functions.graphics.ViewPoint
import org.superdro.core.functions.graphics.ViewTransform
import org.superdro.core.functions.graphics.ZoomPreset
import org.superdro.core.model.AxisId
import org.superdro.core.model.Guide
import org.superdro.core.model.MachineMode
import org.superdro.core.model.MachineType
import org.superdro.core.model.Plane
import org.superdro.core.model.ProximityTarget
import org.superdro.core.model.SubDatum
import org.superdro.validation.functions.Expect.DEG

/**
 * The graphical view math: the view transform, the zoom clamp,
 * the hit areas, the automatic functions, the guides, and the reference image maps.
 *
 * The screen has 254 px for each inch (0.01 px for each µm at the scale 1) and 2 px for each dp.
 */
class GraphicalViewValidationTest {

    private val metrics = ScreenMetrics(xdpi = 254.0, ydpi = 254.0, density = 2.0)
    private val transform = ViewTransform(500.0, 400.0, 1.0, metrics)
    private fun viewport() = GraphicalViewport(1000.0, 800.0, metrics)

    @Test
    fun screen_formula_at_scale_1() {
        val screen = transform.toScreen(PointUm(10_000.0, 5_000.0))
        assertEquals(Vec2(600.0, 350.0), screen)
        assertEquals(PointUm(10_000.0, 5_000.0), transform.toModel(screen))
        // At s = 1, one model mm is xdpi / 25.4 px: one physical mm.
        assertEquals(254.0 / 25.4, transform.umToPxX(1000.0), 1e-9)
    }

    @Test
    fun plane_table_and_the_lathe_sign() {
        assertEquals(AxisId.X to AxisId.Y, PlaneAxes.horizontal(Plane.XY) to PlaneAxes.vertical(Plane.XY))
        assertEquals(AxisId.X to AxisId.Z, PlaneAxes.horizontal(Plane.XZ) to PlaneAxes.vertical(Plane.XZ))
        assertEquals(AxisId.Y to AxisId.Z, PlaneAxes.horizontal(Plane.YZ) to PlaneAxes.vertical(Plane.YZ))
        assertEquals(AxisId.Z to AxisId.X, PlaneAxes.horizontal(Plane.ZX) to PlaneAxes.vertical(Plane.ZX))
        assertEquals(listOf(1, 1, 1, -1), listOf(Plane.XY, Plane.XZ, Plane.YZ, Plane.ZX).map(PlaneAxes::verticalSign))
        assertEquals(PointUm(-20.0, -5.0), PlaneAxes.viewOf(Plane.ZX, x = 5.0, y = null, z = -20.0))
        assertEquals(Plane.ZX, PlaneAxes.planeFor(machineIsLathe = true, selected = Plane.XY))
    }

    @Test
    fun zoom_clamp_0_01_to_10_on_all_paths() {
        val vp = viewport()
        assertEquals(10.0, vp.pinch(1000.0, Vec2(500.0, 400.0)).scale)
        assertEquals(0.01, vp.pinch(1e-6, Vec2(500.0, 400.0)).scale)
        assertEquals(10.0, vp.zoomTo(100.0).scale)
        assertEquals(0.01, vp.zoomTo(0.0001).scale)
        assertEquals(0.01, vp.zoomToFit(listOf(PointUm(1e9, 1e9)), PointUm(0.0, 0.0)).scale, "zoom to fit is clamped")
        val big = GraphicalViewport(10_000.0, 10_000.0, metrics)
        assertEquals(10.0, big.zoomToFit(listOf(PointUm(1.0, 1.0)), PointUm(0.0, 0.0)).scale, "zoom to fit is clamped")
        assertEquals(0.01, GraphicalViewport.MIN_SCALE)
        assertEquals(10.0, GraphicalViewport.MAX_SCALE)
    }

    @Test
    fun a_size_change_does_not_reset_the_zoom() {
        assertEquals(2.0, viewport().zoomTo(2.0).resize(1200.0, 900.0).scale)
    }

    @Test
    fun presets_and_pinch_about_the_touch_point() {
        assertEquals(listOf("5:1" to 0.2, "1:1" to 1.0, "1:5" to 5.0), ZoomPreset.entries.map { it.label to it.scale })
        assertEquals(0.2, viewport().zoomTo(ZoomPreset.FiveToOne).scale)
        val before = viewport().copy(followMode = false)
        val focus = Vec2(300.0, 200.0)
        val modelBefore = before.transform.toModel(focus)
        val after = before.pinch(2.5, focus)
        assertEquals(2.5, after.scale)
        val modelAfter = after.transform.toModel(focus)
        assertEquals(modelBefore.h, modelAfter.h, 1e-6)
        assertEquals(modelBefore.v, modelAfter.v, 1e-6)
    }

    @Test
    fun follow_mode_drag_and_recenter() {
        val followed = viewport().follow(PointUm(10_000.0, 5_000.0))
        assertEquals(500.0 - 100.0, followed.originX, 1e-9, "O.x = width/2 − h × (xdpi/25400) × s")
        assertEquals(400.0 + 50.0, followed.originY, 1e-9, "O.y = height/2 + v × (ydpi/25400) × s")
        val dragged = followed.panBy(10.0, 0.0)
        assertFalse(dragged.followMode, "a drag stops follow mode")
        assertEquals(dragged, dragged.follow(PointUm(0.0, 0.0)), "no follow out of follow mode")
        assertTrue(dragged.recenter().followMode, "Re-center starts follow mode again")
    }

    @Test
    fun double_tap_fits_the_origin_the_tool_and_the_points_with_a_margin_of_0_5() {
        val points = listOf(PointUm(20_000.0, 10_000.0), PointUm(5_000.0, -3_000.0))
        val tool = PointUm(-5_000.0, 2_000.0)
        val fitted = viewport().zoomToFit(points, tool)
        assertFalse(fitted.followMode)
        val margin = 0.5 * metrics.xdpi
        for (point in points + tool + PointUm(0.0, 0.0)) {
            val s = fitted.transform.toScreen(point)
            assertTrue(s.x >= margin - 1e-6 && s.x <= 1000.0 - margin + 1e-6, "x of $point: ${s.x}")
            assertTrue(s.y >= margin - 1e-6 && s.y <= 800.0 - margin + 1e-6, "y of $point: ${s.y}")
        }
    }

    private val origin = ViewPoint("o", PointUm(0.0, 0.0), order = 1)

    @Test
    fun point_hit_square_of_0_25() {
        // 0.25 in = 63.5 px.
        assertEquals(origin, GraphHitTest.hitPoint(Vec2(560.0, 400.0), transform, listOf(origin)))
        assertEquals(origin, GraphHitTest.hitPoint(Vec2(560.0, 460.0), transform, listOf(origin)), "a square, not a circle")
        assertNull(GraphHitTest.hitPoint(Vec2(564.0, 400.0), transform, listOf(origin)))
        val far = ViewPoint("far", PointUm(3_000.0, 0.0), order = 2)
        assertEquals(far, GraphHitTest.hitPoint(Vec2(520.0, 400.0), transform, listOf(origin, far)), "the nearest point")
        val left = ViewPoint("left", PointUm(-1_000.0, 0.0), order = 5)
        val right = ViewPoint("right", PointUm(1_000.0, 0.0), order = 9)
        assertEquals(right, GraphHitTest.hitPoint(Vec2(500.0, 400.0), transform, listOf(right, left)), "equal distance: the higher ID")
        assertEquals(right, GraphHitTest.hitPoint(Vec2(500.0, 400.0), transform, listOf(left, right)), "equal distance: the higher ID")
    }

    @Test
    fun guide_band_of_0_125_in_and_tool_hit_distance() {
        val guide = ViewGuide("g", GuideOrientation.Vertical, 0.0)
        assertEquals(guide, GraphHitTest.hitGuide(Vec2(531.0, 0.0), transform, listOf(guide)), "31.75 px band")
        assertNull(GraphHitTest.hitGuide(Vec2(533.0, 0.0), transform, listOf(guide)))
        val horizontal = ViewGuide("h", GuideOrientation.Horizontal, 0.0)
        val vertical = ViewGuide("v", GuideOrientation.Vertical, 0.0)
        assertEquals(horizontal, GraphHitTest.hitGuide(Vec2(520.0, 405.0), transform, listOf(vertical, horizontal)), "the nearer guide")
        // √2 × 0.25 in = 89.80 px, plus the tool radius of 1000 µm = 10 px.
        val cutter = CutterCircle(PointUm(0.0, 0.0), 1_000.0)
        assertTrue(GraphHitTest.hitsTool(Vec2(599.0, 400.0), transform, cutter))
        assertFalse(GraphHitTest.hitsTool(Vec2(601.0, 400.0), transform, cutter))
    }

    @Test
    fun hit_order_is_point_guide_tool() {
        val guide = ViewGuide("g", GuideOrientation.Vertical, 0.0)
        val cutter = CutterCircle(PointUm(0.0, 0.0), 0.0)
        val touch = Vec2(500.0, 400.0)
        assertIs<GraphHit.Point>(GraphHitTest.hit(touch, transform, listOf(origin), listOf(guide), cutter))
        assertIs<GraphHit.Guide>(GraphHitTest.hit(touch, transform, emptyList(), listOf(guide), cutter))
        assertEquals(GraphHit.Tool, GraphHitTest.hit(touch, transform, emptyList(), emptyList(), cutter))
        val empty = assertIs<GraphHit.Empty>(GraphHitTest.hit(Vec2(600.0, 350.0), transform, emptyList(), emptyList(), null))
        assertEquals(PointUm(10_000.0, 5_000.0), empty.position)
    }

    private fun context(
        machineType: MachineType = MachineType.VerticalMill,
        plane: Plane = Plane.XY,
        selection: GraphSelection = GraphSelection.None,
        selectedPoint: ViewPoint? = null,
        diameter: Double = 6_000.0,
        offset: Boolean = false,
    ) = GraphActionContext(machineType, plane, selection, selectedPoint, diameter, offset)

    @Test
    fun tap_and_long_press_actions() {
        val pointHit = GraphHit.Point(origin)
        assertEquals(GraphTapAction.Select(GraphSelection.Point("o"), true, false), GraphHitTest.tapAction(pointHit, context()))
        assertEquals(
            GraphTapAction.Select(GraphSelection.None, true, false),
            GraphHitTest.tapAction(pointHit, context(selection = GraphSelection.Point("o"))),
            "a tap on the selected point clears it",
        )
        val guideHit = GraphHit.Guide(ViewGuide("g", GuideOrientation.Vertical, 0.0))
        assertEquals(GraphTapAction.Select(GraphSelection.Guide("g"), false, true), GraphHitTest.tapAction(guideHit, context()))
        assertEquals(GraphTapAction.OpenDirectionPicker, GraphHitTest.tapAction(GraphHit.Tool, context()))
        assertEquals(GraphTapAction.NoAction, GraphHitTest.tapAction(GraphHit.Tool, context(machineType = MachineType.Lathe, plane = Plane.ZX)))
        assertEquals(GraphTapAction.NoAction, GraphHitTest.tapAction(GraphHit.Tool, context(diameter = 0.0)))
        assertEquals(GraphTapAction.NoAction, GraphHitTest.tapAction(GraphHit.Empty(PointUm(0.0, 0.0)), context()))

        assertEquals(GraphLongPressAction.OpenSubDatumMenu("o"), GraphHitTest.longPressAction(pointHit, context()))
        val other = ViewPoint("a", PointUm(5.0, 5.0), 3)
        assertEquals(
            GraphLongPressAction.OpenPairCommands("a", "o"),
            GraphHitTest.longPressAction(pointHit, context(selection = GraphSelection.Point("a"), selectedPoint = other)),
        )
        assertEquals(GraphLongPressAction.AskDeleteGuide("g"), GraphHitTest.longPressAction(guideHit, context()))
        assertEquals(GraphLongPressAction.AskClearRadiusCompensation, GraphHitTest.longPressAction(GraphHit.Tool, context()))
        assertEquals(GraphLongPressAction.OpenRadiusCompensation, GraphHitTest.longPressAction(GraphHit.Tool, context(diameter = 0.0)))
        assertEquals(GraphLongPressAction.NoAction, GraphHitTest.longPressAction(GraphHit.Tool, context(plane = Plane.XZ)))
        assertEquals(
            GraphLongPressAction.OpenAddGuide(PointUm(1.0, 2.0)),
            GraphHitTest.longPressAction(GraphHit.Empty(PointUm(1.0, 2.0)), context()),
        )
    }

    @Test
    fun snap_to_point_at_6350_um_and_the_higher_id_on_equal_distance() {
        val tool = PointUm(0.0, 0.0)
        assertEquals("in", SnapToPoint.select(tool, listOf(ViewPoint("in", PointUm(6_350.0, 0.0), 1)), null))
        assertNull(SnapToPoint.select(tool, listOf(ViewPoint("out", PointUm(6_351.0, 0.0), 1)), null))
        val a = ViewPoint("a", PointUm(0.0, 6_000.0), 1)
        val b = ViewPoint("b", PointUm(6_000.0, 0.0), 2)
        assertEquals("b", SnapToPoint.select(tool, listOf(a, b), null))
        assertEquals("b", SnapToPoint.select(tool, listOf(b, a), null))
        assertNull(SnapToPoint.select(tool, listOf(a, b), "b"), "no change when the nearest point is selected")
    }

    @Test
    fun snap_to_guide_selects_the_guide_in_the_direction_of_travel() {
        val guides = listOf(ViewGuide("low", GuideOrientation.Vertical, 0.0), ViewGuide("high", GuideOrientation.Vertical, 10_000.0))
        val up = GuideSnapper()
        val upResults = (0..5).map { i -> up.update(i * 100L, PointUm(6_000.0 + i * 100.0, 0.0), guides) }
        assertTrue("high" in upResults, "moving positive past the middle selects upper: $upResults")
        assertFalse("low" in upResults)
        val down = GuideSnapper()
        val downResults = (0..5).map { i -> down.update(i * 100L, PointUm(4_000.0 - i * 100.0, 0.0), guides) }
        assertTrue("low" in downResults, "moving negative below the middle selects lower: $downResults")
        assertFalse("high" in downResults)
        // Vertical motion uses horizontal guides.
        val horizontal = listOf(ViewGuide("hz", GuideOrientation.Horizontal, 10_000.0))
        val v = GuideSnapper()
        val vResults = (0..5).map { i -> v.update(i * 100L, PointUm(0.0, 6_000.0 + i * 100.0), horizontal) }
        assertTrue("hz" in vResults, "vertical motion: $vResults")
    }

    @Test
    fun automatic_tool_offset_at_a_guide() {
        val guideX = Guide(id = "x", axis = AxisId.X, valueUm = 10_000.0)
        assertEquals(ToolOffsetChange(AxisId.X, -3_000.0), AutoGuideActions.toolOffset(guideX, Plane.XY, MachineType.VerticalMill, 15_000.0, 6_000.0))
        assertEquals(ToolOffsetChange(AxisId.X, 3_000.0), AutoGuideActions.toolOffset(guideX, Plane.XY, MachineType.VerticalMill, 5_000.0, 6_000.0))
        assertEquals(ToolOffsetChange(AxisId.X, 3_000.0), AutoGuideActions.toolOffset(guideX, Plane.XY, MachineType.VerticalMill, 10_000.0, 6_000.0), "equal: +1")
        val guideY = Guide(id = "y", axis = AxisId.Y, valueUm = 0.0)
        assertEquals(ToolOffsetChange(AxisId.Y, -3_000.0), AutoGuideActions.toolOffset(guideY, Plane.XY, MachineType.VerticalMill, 1.0, 6_000.0))
        assertNull(AutoGuideActions.toolOffset(guideX, Plane.XZ, MachineType.VerticalMill, 15_000.0, 6_000.0), "mill XY only")
        assertNull(AutoGuideActions.toolOffset(guideX, Plane.ZX, MachineType.Lathe, 15_000.0, 6_000.0), "mill XY only")
    }

    @Test
    fun automatic_proximity_warning_sets_target_0_in_incremental_mode() {
        assertEquals(ProximityTarget(AxisId.Y, 0.0, absolute = false), AutoGuideActions.proximityTarget(Guide(axis = AxisId.Y, valueUm = 123.0)))
    }

    @Test
    fun plunge_detector_with_a_hysteresis_of_10000_um() {
        val watcher = PlungeWatcher()
        val zs = listOf(0.0, -5_000.0, -10_000.0, -12_000.0, -5_000.0, -2_000.0)
        val results = zs.map { z -> watcher.update(z, 100.0, 200.0) }
        assertEquals(Plunge(-12_000.0), results.last(), "the plunge bottom: automatic functions")
        assertTrue(results.dropLast(1).all { it == null })
        val small = PlungeWatcher()
        assertTrue(listOf(0.0, -9_999.0, 0.0).map { small.update(it, 0.0, 0.0) }.all { it == null }, "9999 µm is not a plunge")
        val moved = PlungeWatcher()
        moved.update(0.0, 0.0, 0.0)
        moved.update(-10_000.0, 0.0, 0.0)
        assertNull(moved.update(0.0, 251.0, 0.0), "X moved more than 250 µm")
        val limit = PlungeWatcher()
        limit.update(0.0, 0.0, 0.0)
        limit.update(-10_000.0, 0.0, 0.0)
        assertNotNull(limit.update(0.0, 250.0, -250.0), "250 µm or less is valid")
    }

    @Test
    fun automatic_zero_and_automatic_sub_datum() {
        assertEquals(listOf(AxisId.X, AxisId.Y), AutoPlungeActions.AUTO_ZERO_AXES)
        val plunge = Plunge(-12_000.0)
        val point = assertNotNull(AutoPlungeActions.autoSubDatum(plunge, 400.0, 100.0, zApplies = true, existing = listOf(SubDatum(x = 100.0, y = 100.0))))
        assertEquals(-12_000.0, point.zUm)
        assertEquals("Point 3", point.toSubDatum(3).name)
        assertNull(AutoPlungeActions.autoSubDatum(plunge, 300.0, 300.0, zApplies = false, existing = listOf(SubDatum(x = 100.0, y = 100.0))), "a sub-datum within 250 µm")
        assertNull(AutoPlungeActions.autoSubDatum(plunge, 1.0, 1.0, zApplies = false, existing = emptyList())!!.zUm, "no Z when Z does not apply")
        assertTrue(AutoPlungeActions.zApplies(zEnabled = true, machineType = MachineType.VerticalMill, use3dSubDatums = true))
        assertFalse(AutoPlungeActions.zApplies(zEnabled = true, machineType = MachineType.VerticalMill, use3dSubDatums = false))
        assertFalse(PlungeWatcher.isActive(MachineType.Lathe, MachineMode.Cutting, automaticZero = true, automaticSubDatum = true), "mill only")
        assertTrue(PlungeWatcher.isActive(MachineType.VerticalMill, MachineMode.Cutting, automaticZero = true, automaticSubDatum = false))
    }

    @Test
    fun guide_map_for_each_plane_and_no_duplicate() {
        assertEquals(AxisId.X, GuideRules.axisOf(Plane.ZX, GuideOrientation.Horizontal))
        assertEquals(AxisId.Z, GuideRules.axisOf(Plane.ZX, GuideOrientation.Vertical))
        assertEquals(AxisId.Y, GuideRules.axisOf(Plane.XY, GuideOrientation.Horizontal))
        assertEquals(AxisId.X, GuideRules.axisOf(Plane.XY, GuideOrientation.Vertical))
        assertEquals(AxisId.Z, GuideRules.axisOf(Plane.XZ, GuideOrientation.Horizontal))
        assertEquals(AxisId.X, GuideRules.axisOf(Plane.XZ, GuideOrientation.Vertical))
        assertEquals(AxisId.Z, GuideRules.axisOf(Plane.YZ, GuideOrientation.Horizontal))
        assertEquals(AxisId.Y, GuideRules.axisOf(Plane.YZ, GuideOrientation.Vertical))
        val existing = listOf(Guide(axis = AxisId.X, valueUm = 1_000.0))
        assertNull(GuideRules.newGuide(existing, Plane.XY, GuideOrientation.Vertical, 1_000.0), "no duplicate guide")
        val added = assertNotNull(GuideRules.newGuide(existing, Plane.XY, GuideOrientation.Vertical, 2_000.0))
        assertEquals(AxisId.X, added.axis)
        assertEquals(2_000.0, added.valueUm)
    }

    @Test
    fun lathe_guides_points_and_reference_image_use_one_sign() {
        val guide = assertNotNull(GuideRules.viewOf(Plane.ZX, Guide(id = "g", axis = AxisId.X, valueUm = 5_000.0)))
        assertEquals(GuideOrientation.Horizontal, guide.orientation)
        val point = assertNotNull(PlaneAxes.viewOf(Plane.ZX, x = 5_000.0, y = null, z = 0.0))
        assertEquals(point.v, guide.position, "the guide and the point use the same sign")
        // An image pixel that the matrix maps to (Z, X) = (0, 5000) shows at the screen place of the point.
        val image = Matrix3.translation(0.0, 5_000.0)
        val screen = transform.imageToScreen(image, Plane.ZX).map(0.0, 0.0)
        val pointScreen = transform.toScreen(point)
        assertEquals(pointScreen.x, screen.x, 1e-9)
        assertEquals(pointScreen.y, screen.y, 1e-9)
    }

    @Test
    fun guide_buttons_use_their_own_coordinate_and_are_disabled_when_absent() {
        val xOnly = GuideRules.buttonsFor(Plane.XY, SubDatum(x = 1_000.0, y = null))
        assertFalse(xOnly.horizontalEnabled, "a horizontal guide needs Y")
        assertTrue(xOnly.verticalEnabled)
        assertEquals(1_000.0, xOnly.verticalGuideValue)
        val both = GuideRules.buttonsFor(Plane.XY, SubDatum(x = 1_000.0, y = 2_000.0))
        assertEquals(2_000.0, both.horizontalGuideValue, "at the vertical coordinate of the point")
        assertEquals(1_000.0, both.verticalGuideValue, "at the horizontal coordinate of the point")
        val lathe = GuideRules.buttonsFor(Plane.ZX, SubDatum(x = 3_000.0, z = null))
        assertEquals(3_000.0, lathe.horizontalGuideValue)
        assertFalse(lathe.verticalEnabled)
    }

    @Test
    fun ruler_ticks_and_the_cutter_circle() {
        val ticks = CursorGeometry.rulerTicks(metrics, scale = 1.0, metric = true, halfLengthPx = 210.0)
        assertEquals((1..21).map { it * 10.0 }, ticks.map { Math.round(it.offsetPx * 1e6) / 1e6 })
        assertEquals(TickKind.Major, ticks[9].kind, "10 mm")
        assertEquals(TickKind.Minor, ticks[4].kind, "5 mm")
        assertEquals(TickKind.Fine, ticks[0].kind, "1 mm")
        assertEquals(0.3 * 254.0, ticks[9].lengthPx, 1e-9)
        assertEquals(0.12 * 254.0, ticks[4].lengthPx, 1e-9)
        assertEquals(0.06 * 254.0, ticks[0].lengthPx, 1e-9)
        // 3 dp = 6 px. At s = 0.5 the fine interval is 5 px: no fine ticks.
        val half = CursorGeometry.rulerTicks(metrics, scale = 0.5, metric = true, halfLengthPx = 60.0)
        assertTrue(half.none { it.kind == TickKind.Fine })
        assertTrue(half.any { it.kind == TickKind.Minor })
        val inch = CursorGeometry.rulerTicks(metrics, scale = 1.0, metric = false, halfLengthPx = 260.0)
        assertTrue(inch.none { it.kind == TickKind.Fine }, "0.01 in = 2.54 px is less than 3 dp")
        assertEquals(254.0, inch.first { it.kind == TickKind.Major }.offsetPx, 1e-9)
        val cutter = CursorGeometry.cutter(Plane.XY, MachineType.VerticalMill, PointUm(10_000.0, 5_000.0), 2_500.0, 0.0, 5_000.0)
        assertEquals(CutterCircle(PointUm(7_500.0, 5_000.0), 2_500.0), cutter, "center = tool − tool offset, radius = D / 2")
    }

    @Test
    fun two_references_give_a_rectangle_map() {
        val a = ImageReference(Vec2(100.0, 100.0), Vec2(0.0, 50_000.0))
        val b = ImageReference(Vec2(500.0, 300.0), Vec2(40_000.0, 30_000.0))
        val fit = assertIs<ImageFitResult.Fitted>(ReferenceImageMath.fit(listOf(a, b), 600.0, 400.0))
        assertEquals(ImageFitQuality.Good, fit.quality)
        for (reference in ReferenceImageMath.rectangleCorners(a, b)) {
            val mapped = fit.matrix.map(reference.pixel)
            assertEquals(reference.machine.x, mapped.x, 1e-6)
            assertEquals(reference.machine.y, mapped.y, 1e-6)
        }
        // The other two corners: (p1.x, p2.y) → (m1.x, m2.y) and (p2.x, p1.y) → (m2.x, m1.y).
        assertEquals(Vec2(0.0, 30_000.0), fit.matrix.map(100.0, 300.0).let { Vec2(Math.round(it.x).toDouble(), Math.round(it.y).toDouble()) })
        assertEquals(Vec2(40_000.0, 50_000.0), fit.matrix.map(500.0, 100.0).let { Vec2(Math.round(it.x).toDouble(), Math.round(it.y).toDouble()) })
    }

    @Test
    fun four_references_give_a_projective_map() {
        val h = Matrix3(100.0, 5.0, 1_000.0, 3.0, 100.0, 2_000.0, 0.0001, 0.00005, 1.0)
        val pixels = listOf(Vec2(0.0, 0.0), Vec2(600.0, 0.0), Vec2(600.0, 400.0), Vec2(0.0, 400.0))
        val references = pixels.map { pixel -> ImageReference(pixel, h.map(pixel)) }
        val fit = assertIs<ImageFitResult.Fitted>(ReferenceImageMath.fit(references, 600.0, 400.0))
        for (test in pixels + Vec2(300.0, 200.0) + Vec2(123.0, 321.0)) {
            val expected = h.map(test)
            val actual = fit.matrix.map(test)
            assertEquals(expected.x, actual.x, 1e-6 * kotlin.math.abs(expected.x) + 1e-6)
            assertEquals(expected.y, actual.y, 1e-6 * kotlin.math.abs(expected.y) + 1e-6)
        }
    }

    @Test
    fun singular_and_bad_reference_sets_are_refused() {
        val line = listOf(0.0, 100.0, 200.0, 300.0).map { x -> ImageReference(Vec2(x, 0.0), Vec2(x * 10.0, x * 5.0)) }
        assertIs<ImageFitResult.Refused>(ReferenceImageMath.fit(line, 600.0, 400.0), "a singular set")
        val three = line.take(3)
        assertEquals(ImageFitRefusal.ThreeReferences, assertIs<ImageFitResult.Refused>(ReferenceImageMath.fit(three, 600.0, 400.0)).reason)
        val same = listOf(ImageReference(Vec2(1.0, 1.0), Vec2(0.0, 0.0)), ImageReference(Vec2(1.0, 1.0), Vec2(10.0, 10.0)))
        assertEquals(ImageFitRefusal.SamePosition, assertIs<ImageFitResult.Refused>(ReferenceImageMath.fit(same, 600.0, 400.0)).reason)
        val sameX = listOf(ImageReference(Vec2(1.0, 1.0), Vec2(0.0, 0.0)), ImageReference(Vec2(1.0, 100.0), Vec2(10.0, 10.0)))
        assertEquals(ImageFitRefusal.NotDiagonal, assertIs<ImageFitResult.Refused>(ReferenceImageMath.fit(sameX, 600.0, 400.0)).reason)
        val square = listOf(Vec2(0.0, 0.0), Vec2(600.0, 0.0), Vec2(600.0, 400.0), Vec2(0.0, 400.0))
        val machine = listOf(Vec2(0.0, 0.0), Vec2(60_000.0, 0.0), Vec2(0.0, 40_000.0), Vec2(60_000.0, 40_000.0))
        val crossed = square.zip(machine) { p, m -> ImageReference(p, m) }
        assertEquals(ImageFitRefusal.CrossedOver, assertIs<ImageFitResult.Refused>(ReferenceImageMath.fit(crossed, 600.0, 400.0)).reason)
    }

    @Test
    fun quality_banner_at_600_um_and_refusal_at_2000_um_for_each_pixel() {
        fun twoPoint(umPerPx: Double) = ReferenceImageMath.fit(
            listOf(ImageReference(Vec2(0.0, 0.0), Vec2(0.0, 0.0)), ImageReference(Vec2(100.0, 100.0), Vec2(100 * umPerPx, 100 * umPerPx))),
            200.0, 200.0,
        )
        assertEquals(ImageFitQuality.Good, assertIs<ImageFitResult.Fitted>(twoPoint(599.0)).quality)
        assertEquals(ImageFitQuality.SteepAngle, assertIs<ImageFitResult.Fitted>(twoPoint(700.0)).quality)
        val refused = assertIs<ImageFitResult.Refused>(twoPoint(2_500.0))
        assertEquals(ImageFitRefusal.TooOblique, refused.reason)
        assertEquals(2_500.0, refused.umPerPixel!!, 1e-6)
    }

    @Test
    fun opacity_cycle_and_the_start_alpha() {
        assertEquals(255, ReferenceImageMath.nextAlpha(0))
        assertEquals(127, ReferenceImageMath.nextAlpha(255))
        assertEquals(0, ReferenceImageMath.nextAlpha(127))
        assertEquals(255, ReferenceImageMath.START_ALPHA)
        assertEquals(100, ReferenceImageMath.JPEG_QUALITY)
    }

    @Test
    fun flip_horizontal_changes_left_and_right_and_flip_vertical_changes_top_and_bottom() {
        val horizontal = ReferenceImageMath.flipHorizontal(600, 400)
        assertEquals(Vec2(600.0, 10.0), horizontal.matrix.map(0.0, 10.0))
        assertEquals(Vec2(0.0, 10.0), horizontal.matrix.map(600.0, 10.0))
        val vertical = ReferenceImageMath.flipVertical(600, 400)
        assertEquals(Vec2(10.0, 400.0), vertical.matrix.map(10.0, 0.0))
        assertEquals(Vec2(10.0, 0.0), vertical.matrix.map(10.0, 400.0))
        val right = ReferenceImageMath.rotateRight(600, 400)
        assertEquals(400 to 600, right.width to right.height)
        assertEquals(Vec2(400.0, 0.0), right.matrix.map(0.0, 0.0), "rotate right moves the top left corner to the top right")
    }

    @Test
    fun reticle_pixel_is_in_image_coordinates_also_in_preview_mode() {
        val pixel = ReferenceImageMath.reticlePixel(600.0, 400.0, panX = 30.0, panY = -20.0, scale = 2.0)
        assertEquals(Vec2(300.0 - 15.0, 200.0 + 10.0), pixel, "p = (W/2 − panX / scale, H/2 − panY / scale)")
        val preview = ReferenceImageMath.reticlePixel(600.0, 400.0, 0.0, 0.0, 1.0, contentMatrix = Matrix3.scale(2.0, -2.0))
        assertEquals(300.0, preview!!.x, 1e-9)
        assertEquals(200.0, preview.y, 1e-9)
    }

    @Test
    fun decode_size_limit_of_4096_px_on_the_long_side() {
        assertEquals(4096, ReferenceImageMath.DECODE_LIMIT_PX)
        assertEquals(1, ReferenceImageMath.decodeSampleSize(4096, 3000))
        assertEquals(2, ReferenceImageMath.decodeSampleSize(4097, 10))
        assertEquals(2, ReferenceImageMath.decodeSampleSize(8000, 6000))
        assertEquals(8, ReferenceImageMath.decodeSampleSize(100, 20_000))
    }

    @Test
    fun invert_matrix_and_the_bt_601_fallback() {
        assertEquals(
            listOf(-1.0, 0.0, 0.0, 0.0, 255.0, 0.0, -1.0, 0.0, 0.0, 255.0, 0.0, 0.0, -1.0, 0.0, 255.0, 0.0, 0.0, 0.0, 1.0, 0.0),
            ColorMatrix.INVERT.values,
        )
        assertEquals(0xFFEFDFCF.toInt(), ColorMatrix.INVERT.apply(0xFF102030.toInt()))
        val flat = IntArray(16 * 16) { 0xFF808080.toInt() }
        val result = ContrastPreservingGray.weights(flat, 16, 16)
        assertFalse(result.searched, "no contrast: the search cannot find weights")
        assertEquals(GrayWeights(0.299, 0.587, 0.114), result.weights)
    }

    @Test
    fun inverse_display_transform_gives_the_stored_point() {
        // A pattern point in the shown frame goes through the inverse display transform before
        // the save. Rotate Part α = 30° about (5, 5) mm, then Align Axes 10°.
        val workspace = org.superdro.core.model.Workspace(
            partRotation = org.superdro.core.model.PartRotation(active = true, angleArcSec = 30 * DEG, pivotXUm = 5_000.0, pivotYUm = 5_000.0),
            axisAlignment = org.superdro.core.model.AxisAlignment(active = true, angleArcSec = 10 * DEG),
        )
        val frame = org.superdro.core.functions.graphics.DisplayFrame.of(workspace, null, MachineType.VerticalMill, MachineMode.Measurement)
        val stored = Vec2(12_345.0, -6_789.0)
        val back = frame.inverse(frame.apply(stored))
        assertEquals(stored.x, back.x, 1e-6)
        assertEquals(stored.y, back.y, 1e-6)
        assertFalse(frame.isIdentity)
    }

    @Test
    fun rotate_part_alpha_90_shows_10_0_at_0_minus_10() {
        val shown = PartRotationTransform.typed(90 * DEG, Vec2(0.0, 0.0)).apply(Vec2(10.0, 0.0))
        assertEquals(0.0, shown.x, 1e-9)
        assertEquals(-10.0, shown.y, 1e-9)
        val back = PartRotationTransform.typed(90 * DEG, Vec2(0.0, 0.0)).inverse(shown)
        assertEquals(10.0, back.x, 1e-9)
        assertEquals(0.0, back.y, 1e-9)
    }
}
