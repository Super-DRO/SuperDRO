package org.superdro.core.drawing

import kotlin.test.Test
import org.superdro.core.model.DxfImportLimits
import org.superdro.core.model.SuperDroJson
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DxfLimitsTest {

    @Test
    fun theBinarySentinelHas22Bytes() {
        assertEquals(22, DxfLimits.BINARY_SENTINEL.size)
        assertTrue(DxfLimits.isBinaryDxf(DxfLimits.BINARY_SENTINEL + byteArrayOf(0, 0)))
        assertFalse(DxfLimits.isBinaryDxf("  0\nSECTION\n".encodeToByteArray()))
    }

    @Test
    fun theDefaultLimits() {
        val limits = DxfLimits()
        assertEquals(2_000_000L, limits.maxSourceBytes)
        assertEquals(10_000, limits.maxEntities)
        assertEquals(8, limits.maxBlockLevels)
        assertEquals(5000L, limits.maxParseTimeMs)
        assertEquals(1000L, limits.curveToleranceNm)
        assertEquals(10_000, limits.maxBezierSegments)
        assertEquals(2000, limits.polylineWarningPoints)
        assertEquals(1000, limits.maxSplineControlPoints)
    }

    @Test
    fun theStoredSettingsGiveTheSameLimits() {
        assertEquals(DxfLimits(), DxfLimits.from(DxfImportLimits()))
        val changed = DxfLimits(maxEntities = 20_000, curveToleranceNm = 500)
        assertEquals(changed, DxfLimits.from(changed.toSettings()))
        val json = SuperDroJson.encodeToString(DxfImportLimits.serializer(), changed.toSettings())
        assertEquals(changed.toSettings(), SuperDroJson.decodeFromString(DxfImportLimits.serializer(), json))
        assertEquals(DxfImportLimits(), SuperDroJson.decodeFromString(DxfImportLimits.serializer(), "{}"))
    }
}
