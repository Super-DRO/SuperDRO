package org.superdro.core.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.superdro.core.model.CameraCalibration

class CrosshairsTest {

    @Test
    fun theCrosshairsAreAtTheViewCenter() {
        val locked = Crosshairs.layout(1200.0, 800.0, locked = true, screenPixelsPerMm = null, metric = true, density = 2.0)
        assertEquals(CrosshairLayout(600.0, 400.0, 600.0, 400.0, emptyList()), locked)
        val small = Crosshairs.layout(1200.0, 800.0, locked = false, screenPixelsPerMm = null, metric = true, density = 2.0)
        // 0.25 × min(1200, 800) = 200 px across.
        assertEquals(CrosshairLayout(600.0, 400.0, 100.0, 100.0, emptyList()), small)
    }

    @Test
    fun theMetricTicksAreTenFiveAndOneMm() {
        val ticks = Crosshairs.ticks(extentPx = 100.0, screenPixelsPerMm = 10.0, metric = true, density = 1.0)
        assertEquals(20, ticks.size)
        assertEquals(listOf(-100.0, 100.0), ticks.filter { tick -> tick.level == TickLevel.Major }.map { tick -> tick.offsetPx })
        assertEquals(listOf(-50.0, 50.0), ticks.filter { tick -> tick.level == TickLevel.Minor }.map { tick -> tick.offsetPx })
        assertEquals(16, ticks.count { tick -> tick.level == TickLevel.Fine })
        assertEquals(10_000.0, ticks.last().valueUm)
        assertEquals(ticks.sortedBy { tick -> tick.offsetPx }, ticks)
    }

    @Test
    fun theInchTicksAreOneTenthAndOneHundredthInch() {
        // 2 px for each mm: 0.01 in is 0.508 px, less than 3 dp, so no fine ticks.
        val ticks = Crosshairs.ticks(extentPx = 60.0, screenPixelsPerMm = 2.0, metric = false, density = 1.0)
        assertEquals(listOf(-50.8, 50.8), ticks.filter { tick -> tick.level == TickLevel.Major }.map { tick -> tick.offsetPx })
        assertEquals(20, ticks.count { tick -> tick.level == TickLevel.Minor })
        assertEquals(0, ticks.count { tick -> tick.level == TickLevel.Fine })
        assertEquals(0.3, TickLevel.Major.lengthOfXdpi)
        assertEquals(0.12, TickLevel.Minor.lengthOfXdpi)
        assertEquals(0.06, TickLevel.Fine.lengthOfXdpi)
    }

    @Test
    fun aTickLevelNeedsThreeDpOfSpace() {
        // 0.5 px for each mm at density 2: 1 mm is 0.5 px, 5 mm is 2.5 px, 10 mm is 5 px (less than 6 px).
        assertTrue(Crosshairs.ticks(extentPx = 500.0, screenPixelsPerMm = 0.5, metric = true, density = 2.0).isEmpty())
        val some = Crosshairs.ticks(extentPx = 500.0, screenPixelsPerMm = 0.75, metric = true, density = 2.0)
        assertEquals(132, some.size)
        assertTrue(some.all { tick -> tick.level == TickLevel.Major })
    }

    @Test
    fun theVideoTicksUseTheImageScaleOrShowNoTicks() {
        val calibration = CameraCalibration(50.0, 1280, 720)
        val imageScale = ImageCalibrationMath.pixelsPerMmFor(calibration, 1280, 720)!!
        val videoScale = VideoFit.scale(1920.0, 1080.0, 1280, 720, fill = true)
        val screen = ImageCalibrationMath.screenPixelsPerMm(imageScale, videoScale)
        assertEquals(75.0, screen, 1e-9)
        val layout = Crosshairs.layout(1920.0, 1080.0, locked = true, screenPixelsPerMm = screen, metric = true, density = 2.0)
        assertEquals(750.0, layout.ticks.first { tick -> tick.level == TickLevel.Major && tick.offsetPx > 0 }.offsetPx, 1e-9)
        val uncalibrated = ImageCalibrationMath.pixelsPerMmFor(calibration, 640, 480)
        val none = Crosshairs.layout(1920.0, 1080.0, locked = true, screenPixelsPerMm = uncalibrated, metric = true, density = 2.0)
        assertTrue(none.ticks.isEmpty())
    }

    @Test
    fun theMajorCountRuleOfTheCursor() {
        assertEquals(4, Crosshairs.majorCount(1000.0, 100.0))
        assertEquals(0, Crosshairs.majorCount(1000.0, 0.0))
        assertEquals(0, Crosshairs.majorCount(100.0, 1000.0))
    }
}
