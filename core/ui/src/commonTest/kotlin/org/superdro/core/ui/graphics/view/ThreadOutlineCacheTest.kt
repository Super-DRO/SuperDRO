package org.superdro.core.ui.graphics.view

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
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
}
