package org.superdro.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/** The image scale of a camera selection is a new field with a default. */
class CameraCalibrationTest {

    private val time = Instant.parse("2026-10-12T08:00:00Z")

    @Test
    fun aSelectionWithACalibrationGoesBothWays() {
        val selection = CameraSelection("p1", "046d:0825", "0/1280/720/333333", time, CameraCalibration(50.0, 1280, 720, -324_000.0, time))
        val text = SuperDroJson.encodeToString(CameraSelection.serializer(), selection)
        assertEquals(selection, SuperDroJson.decodeFromString(CameraSelection.serializer(), text))
    }

    @Test
    fun anOldSelectionHasNoCalibration() {
        val old = """{"profileId":"p1","cameraKey":"046d:0825","modeKey":"0/1280/720/333333","updatedOn":"2026-10-12T08:00:00Z"}"""
        val selection = SuperDroJson.decodeFromString(CameraSelection.serializer(), old)
        assertEquals("0/1280/720/333333", selection.modeKey)
        assertNull(selection.calibration)
    }
}
