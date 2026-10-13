package org.superdro.core.store.repository

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.superdro.core.model.CameraCalibration
import org.superdro.core.model.CameraModeResult
import org.superdro.core.model.CameraModeStatus
import org.superdro.core.model.CameraSelection
import org.superdro.core.model.MachineProfile
import org.superdro.core.store.StoreFixture
import org.superdro.core.store.valueOrNull

class CameraRepositoryTest {

    private val scope = TestScope()
    private val store = StoreFixture(scope)

    @AfterTest
    fun close() = store.close()

    private val time = Instant.parse("2026-10-12T08:00:00Z")

    @Test
    fun theModeMemoryIsForEachCameraModel() = scope.runTest {
        val good = CameraModeResult("046d:0825", "mjpeg-1280x720-30", CameraModeStatus.Good, 1280, 720, "MJPEG", 29.97, time)
        store.cameras.saveModeResult(good)
        // A good result with no measured rate keeps the old rate.
        store.cameras.saveModeResult(good.copy(measuredFps = null))
        assertEquals(29.97, store.cameras.loadModeResults("046d:0825").single().measuredFps)
        // A bad result replaces the rate.
        store.cameras.saveModeResult(good.copy(status = CameraModeStatus.Bad, measuredFps = null))
        assertEquals(listOf(CameraModeStatus.Bad to null), store.cameras.modeResults("046d:0825").first().map { it.status to it.measuredFps })
    }

    @Test
    fun aProfileDeleteRemovesItsSelectionAndKeepsTheModeResults() = scope.runTest {
        val profile = store.machines.createProfile(MachineProfile.create(name = "Mill")).valueOrNull()!!
        store.cameras.saveModeResult(CameraModeResult("046d:0825", "yuy2-640x480-30", CameraModeStatus.Good, 640, 480, "YUY2", 30.0, time))
        store.cameras.saveSelection(CameraSelection(profile.id, "046d:0825", "yuy2-640x480-30", time))
        store.cameras.saveSelection(CameraSelection(profile.id, "1234:abcd", "mjpeg", time.plus(kotlin.time.Duration.parse("1s"))))
        assertEquals(listOf("1234:abcd", "046d:0825"), store.cameras.selections(profile.id).first().map { it.cameraKey })
        assertEquals("yuy2-640x480-30", store.cameras.selection(profile.id, "046d:0825").first()!!.modeKey)

        store.machines.deleteProfile(profile.id, deleteTools = true)
        assertEquals(0L, store.count("camera_selection"))
        assertEquals(1L, store.count("camera_mode_result"))
        assertNull(store.cameras.selection(profile.id, "046d:0825").first())
    }

    @Test
    fun theSelectionKeepsTheImageScaleOfTheCamera() = scope.runTest {
        val profile = store.machines.createProfile(MachineProfile.create(name = "Mill")).valueOrNull()!!
        val scale = CameraCalibration(pixelsPerMm = 42.5, imageWidth = 1280, imageHeight = 720, rotationArcSec = -36.0, calibratedOn = time)
        store.cameras.saveSelection(CameraSelection(profile.id, "046d:0825", "0/1280/720/333333", time, scale))
        assertEquals(scale, store.cameras.selection(profile.id, "046d:0825").first()!!.calibration)

        // A scale with no row gives a row with an empty mode key.
        store.cameras.saveCalibration(profile.id, "1234:abcd", scale.copy(pixelsPerMm = 10.0), time)
        assertEquals("" to 10.0, store.cameras.selection(profile.id, "1234:abcd").first()!!.let { it.modeKey to it.calibration?.pixelsPerMm })

        // A new scale keeps the mode, and a null scale removes the scale.
        store.cameras.saveCalibration(profile.id, "046d:0825", null, time)
        val row = store.cameras.loadSelections(profile.id).first { it.cameraKey == "046d:0825" }
        assertEquals("0/1280/720/333333", row.modeKey)
        assertNull(row.calibration)

        // A row with no scale (the old form) loads with a null scale.
        store.cameras.saveSelection(CameraSelection(profile.id, "046d:0825", "0/640/480/333333", time))
        assertNull(store.cameras.selection(profile.id, "046d:0825").first()!!.calibration)
    }
}
