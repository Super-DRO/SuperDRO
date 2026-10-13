package org.superdro.core.functions.graphics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.superdro.core.functions.PointUm

/** The view transform, the zoom, and follow mode of the graphical view. */
class GraphicalViewportTest {

    // 254 dpi gives 10 px for each mm.
    private val metrics = ScreenMetrics(xdpi = 254.0, ydpi = 254.0, density = 1.5)

    @Test
    fun theScreenFormulaAtScale1PutsOneModelMmOnOnePhysicalMm() {
        val transform = ViewTransform(originX = 500.0, originY = 400.0, scale = 1.0, metrics = metrics)
        assertNear(Vec2(600.0, 350.0), transform.toScreen(PointUm(10_000.0, 5_000.0)), 1e-9)
        // 1 mm = 1000 µm gives xdpi / 25.4 px = 10 px = 1 physical mm.
        val oneMm = transform.toScreen(PointUm(1000.0, 0.0)).x - transform.toScreen(PointUm(0.0, 0.0)).x
        assertEquals(metrics.xdpi / 25.4, oneMm, 1e-9)
        // A positive vertical value goes up on the screen.
        assertTrue(transform.toScreen(PointUm(0.0, 1000.0)).y < 400.0)
    }

    @Test
    fun theInverseFormulaGivesTheModelPositionOfATouch() {
        val transform = ViewTransform(originX = 500.0, originY = 400.0, scale = 2.0, metrics = ScreenMetrics(200.0, 180.0, 1.0))
        val model = PointUm(-12_345.0, 6_789.0)
        val back = transform.toModel(transform.toScreen(model))
        assertEquals(model.h, back.h, 1e-9)
        assertEquals(model.v, back.v, 1e-9)
        assertNear(PointUm(0.0, 0.0), transform.toModel(Vec2(500.0, 400.0)), 0.0)
    }

    @Test
    fun eachZoomPathIsClampedTo001And10() {
        val view = GraphicalViewport(1000.0, 800.0, metrics)
        assertEquals(10.0, view.pinch(1000.0, Vec2(10.0, 10.0)).scale)
        assertEquals(0.01, view.pinch(1e-6, Vec2(10.0, 10.0)).scale)
        assertEquals(0.01, view.zoomTo(0.001).scale)
        assertEquals(10.0, view.zoomTo(20.0).scale)
        // Zoom to fit is clamped too: a box of 10 km needs a scale below 0.01.
        assertEquals(0.01, view.zoomToFit(listOf(PointUm(1e10, 1e10)), PointUm(0.0, 0.0)).scale)
        // A small box and a low density need a scale above 10.
        val lowDensity = GraphicalViewport(1000.0, 800.0, ScreenMetrics(10.0, 10.0, 1.0))
        assertEquals(10.0, lowDensity.zoomToFit(listOf(PointUm(100.0, 100.0)), PointUm(0.0, 0.0)).scale)
    }

    @Test
    fun thePresetsHaveTheirLabelsAndScales() {
        assertEquals(listOf("5:1" to 0.2, "1:1" to 1.0, "1:5" to 5.0), ZoomPreset.entries.map { preset -> preset.label to preset.scale })
        val view = GraphicalViewport(1000.0, 800.0, metrics, scale = 3.0)
        assertEquals(0.2, view.zoomTo(ZoomPreset.FiveToOne).scale)
        assertEquals(1.0, view.zoomTo(ZoomPreset.OneToOne).scale)
        assertEquals(5.0, view.zoomTo(ZoomPreset.OneToFive).scale)
    }

    @Test
    fun aPinchKeepsTheTouchPointAtItsModelPosition() {
        val view = GraphicalViewport(1000.0, 800.0, metrics, scale = 1.0, originX = 300.0, originY = 600.0, followMode = false)
        val focus = Vec2(700.0, 250.0)
        val before = view.transform.toModel(focus)
        val after = view.pinch(2.5, focus)
        assertEquals(2.5, after.scale)
        val model = after.transform.toModel(focus)
        assertEquals(before.h, model.h, 1e-9)
        assertEquals(before.v, model.v, 1e-9)
        // O = F − (F − O) × s_new ÷ s_old.
        assertEquals(700.0 - (700.0 - 300.0) * 2.5, after.originX, 1e-9)
        assertEquals(250.0 - (250.0 - 600.0) * 2.5, after.originY, 1e-9)
    }

    @Test
    fun followModeKeepsTheToolAtTheViewCenter() {
        val view = GraphicalViewport(1000.0, 800.0, metrics, scale = 2.0)
        val tool = PointUm(25_400.0, -12_700.0)
        val following = view.follow(tool)
        // O.x = width/2 − h × (xdpi/25400) × s, O.y = height/2 + v × (ydpi/25400) × s.
        assertEquals(500.0 - 25_400.0 * 0.01 * 2.0, following.originX, 1e-9)
        assertEquals(400.0 + -12_700.0 * 0.01 * 2.0, following.originY, 1e-9)
        assertNear(Vec2(500.0, 400.0), following.transform.toScreen(tool), 1e-9)
    }

    @Test
    fun aDragStopsFollowModeAndReCenterStartsItAgain() {
        val view = GraphicalViewport(1000.0, 800.0, metrics)
        assertTrue(view.followMode)
        val panned = view.panBy(30.0, -20.0)
        assertFalse(panned.followMode)
        assertEquals(view.originX + 30.0, panned.originX)
        assertEquals(view.originY - 20.0, panned.originY)
        // Out of follow mode, the tool does not move the view.
        assertEquals(panned, panned.follow(PointUm(5000.0, 5000.0)))
        assertTrue(panned.recenter().followMode)
    }

    @Test
    fun aSizeChangeDoesNotResetTheZoom() {
        val view = GraphicalViewport(1000.0, 800.0, metrics, scale = 4.0, followMode = false)
        val resized = view.resize(600.0, 1200.0)
        assertEquals(4.0, resized.scale)
        assertEquals(600.0, resized.widthPx)
        // The model position at the view center stays at the view center.
        assertNear(view.transform.toModel(Vec2(500.0, 400.0)), resized.transform.toModel(Vec2(300.0, 600.0)), 1e-9)
    }

    @Test
    fun zoomToFitHasTheOriginTheToolAndAMarginOfHalfAnInch() {
        val view = GraphicalViewport(1000.0, 800.0, metrics, scale = 1.0)
        val points = listOf(PointUm(40_000.0, 10_000.0), PointUm(60_000.0, 30_000.0))
        val tool = PointUm(80_000.0, 20_000.0)
        val fit = view.zoomToFit(points, tool)
        assertFalse(fit.followMode)
        // The box is (0, 0) to (80000, 30000) µm: 800 px × 300 px at s = 1, plus 254 px of margin.
        val expected = minOf(1000.0 / (800.0 + 254.0), 800.0 / (300.0 + 254.0))
        assertEquals(expected, fit.scale, 1e-12)
        val margin = 0.5 * metrics.xdpi * fit.scale
        for (point in points + tool + PointUm(0.0, 0.0)) {
            val screen = fit.transform.toScreen(point)
            assertTrue(screen.x >= margin - 1e-9 && screen.x <= 1000.0 - margin + 1e-9, "x of $point")
            assertTrue(screen.y >= 0.0 && screen.y <= 800.0, "y of $point")
        }
        // The box center is at the view center.
        assertNear(Vec2(500.0, 400.0), fit.transform.toScreen(PointUm(40_000.0, 15_000.0)), 1e-9)
    }

    @Test
    fun zoomToFitOfOnlyTheOriginGivesTheScale1() {
        val view = GraphicalViewport(1000.0, 800.0, metrics, scale = 3.0)
        val fit = view.zoomToFit(emptyList(), PointUm(0.0, 0.0))
        assertEquals(1.0, fit.scale)
        assertNear(Vec2(500.0, 400.0), fit.transform.toScreen(PointUm(0.0, 0.0)), 1e-9)
    }
}
