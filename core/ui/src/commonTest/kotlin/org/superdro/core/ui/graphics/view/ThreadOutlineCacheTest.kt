package org.superdro.core.ui.graphics.view

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.superdro.core.model.ThreadCutter
import org.superdro.core.model.ThreadForm
import org.superdro.core.model.ThreadSpec

class ThreadOutlineCacheTest {

    private val spec = ThreadSpec(id = "m10", pitchUm = 1_500.0, majorDiameterUm = 10_000.0, minorDiameterUm = 8_160.0, startZUm = -15_000.0, stopZUm = 0.0)

    @Test
    fun theSameSpecGivesTheSameOutline() {
        val cache = ThreadOutlineCache()
        val first = cache.of(spec)
        assertSame(first, cache.of(spec.copy()))
        assertEquals(GraphicalSceneBuilder.threadOutline(spec), first)
    }

    @Test
    fun aChangedSpecGivesANewOutline() {
        val cache = ThreadOutlineCache()
        val first = cache.of(spec)
        val longer = cache.of(spec.copy(startZUm = -20_000.0))
        assertNotSame(first, longer)
        assertEquals(GraphicalSceneBuilder.threadOutline(spec.copy(startZUm = -20_000.0)), longer)
    }

    @Test
    fun aSpecWithNoOutlineGivesNull() {
        val cache = ThreadOutlineCache()
        assertNull(cache.of(spec.copy(pitchUm = 0.0)))
        assertEquals(GraphicalSceneBuilder.threadOutline(spec), cache.of(spec))
    }

    @Test
    fun trapezoidalAndAcmeFormsIncludeRootFlatSegments() {
        val trSpec = spec.copy(
            form = ThreadForm.IsoTrapezoidal,
            cutter = ThreadCutter.FullForm,
            pitchUm = 4_000.0,
            majorDiameterUm = 20_000.0,
            minorDiameterUm = 15_500.0,
            startZUm = -8_000.0,
            stopZUm = 0.0,
        )
        val outline = assertNotNull(GraphicalSceneBuilder.threadOutline(trSpec))
        val front = outline.polylines.first()
        val rootY = -7_750.0
        val rootPoints = front.filter { point -> abs(point.v - rootY) < 1e-3 }
        assertTrue(rootPoints.size >= 4, "Each trapezoidal tooth has two root vertices separated by the flat width")
        val firstToothFlat = rootPoints[1].h - rootPoints[0].h
        val expectedFlat = org.superdro.core.functions.thread.ThreadPassSchedule.rootFlatWidthUm(
            ThreadForm.IsoTrapezoidal,
            ThreadCutter.FullForm,
            4_000.0,
        )
        assertEquals(expectedFlat, firstToothFlat, 1.0)
    }

    @Test
    fun taperedPipeFormSlopesCrestAndRootAlongZ() {
        val nptSpec = spec.copy(
            form = ThreadForm.Npt,
            pitchUm = 1_814.3,
            majorDiameterUm = 21_336.0,
            minorDiameterUm = 18_321.0,
            startZUm = -16_000.0,
            stopZUm = 0.0,
        )
        val outline = assertNotNull(GraphicalSceneBuilder.threadOutline(nptSpec))
        val front = outline.polylines.first()
        val firstPoint = front.first()
        val lastPoint = front.last()
        assertEquals(-16_000.0, firstPoint.h, 1e-3)
        assertEquals(0.0, lastPoint.h, 1e-3)
        // Over 16 mm axial span, 1:16 diametral taper (1:32 radial slope) changes radius by 500 um.
        val expectedStartRadius = 21_336.0 / 2.0
        assertEquals(-expectedStartRadius, firstPoint.v, 1e-3)
        assertTrue(abs(lastPoint.v) > abs(firstPoint.v), "Radius increases along the 1:32 radial taper")
    }
}
