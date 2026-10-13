package org.superdro.core.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import org.superdro.core.model.CameraCalibration
import org.superdro.core.model.CameraModeResult
import org.superdro.core.model.CameraModeStatus
import org.superdro.core.model.CameraSelection

class CameraModeMemoryTest {

    private val t1 = Instant.fromEpochMilliseconds(1_000L)
    private val t2 = Instant.fromEpochMilliseconds(2_000L)
    private val t3 = Instant.fromEpochMilliseconds(3_000L)
    private val logitech = CameraIdentity(0x046D, 0x0825, deviceName = "/dev/bus/usb/001/002")
    private val microscope = CameraIdentity(0x1D6B, 0x0102, deviceName = "/dev/bus/usb/001/003")
    private val hd = UvcMode(UvcCodec.Mjpeg, 1280, 720, 333_333L, "MJPG")
    private val vga = UvcMode(UvcCodec.Uncompressed, 640, 480, 333_333L, "YUY2")

    @Test
    fun aResultReplacesTheRowOfTheSameCameraAndMode() {
        val first = CameraModeMemory.record(emptyList(), logitech.key, hd, CameraModeStatus.Good, 29.5, t1)
        assertEquals(
            listOf(CameraModeResult("046d:0825", "0/1280/720/333333", CameraModeStatus.Good, 1280, 720, "MJPEG", 29.5, t1)),
            first,
        )
        val second = CameraModeMemory.record(first, logitech.key, hd, CameraModeStatus.Bad, 10.0, t2)
        assertEquals(1, second.size)
        assertEquals(CameraModeStatus.Bad, second[0].status)
        assertNull(second[0].measuredFps)
        val other = CameraModeMemory.record(second, microscope.key, hd, CameraModeStatus.Good, null, t3)
        assertEquals(2, other.size)
        assertEquals(listOf(other[1]), CameraModeMemory.resultsFor(other, microscope.key))
    }

    @Test
    fun aGoodWriteWithNoRateKeepsTheOldRate() {
        val first = CameraModeMemory.record(emptyList(), logitech.key, vga, CameraModeStatus.Good, 30.1, t1)
        val again = CameraModeMemory.record(first, logitech.key, vga, CameraModeStatus.Good, null, t2)
        assertEquals(30.1, again.single().measuredFps)
        assertEquals(t2, again.single().updatedOn)
        assertEquals("UNCOMPRESSED", again.single().codec)
    }

    @Test
    fun theSelectionIsOneRowForEachProfileAndCamera() {
        val calibration = CameraCalibration(50.0, 1280, 720)
        var rows = CameraModeMemory.select(emptyList(), "mill", logitech.key, hd, t1)
        rows = CameraModeMemory.calibrate(rows, "mill", logitech.key, calibration, t2)
        rows = CameraModeMemory.select(rows, "mill", logitech.key, vga, t3)
        rows = CameraModeMemory.select(rows, "lathe", logitech.key, hd, t3)
        assertEquals(2, rows.size)
        val mill = CameraModeMemory.selectionFor(rows, "mill", logitech.key)!!
        assertEquals(vga.key, mill.modeKey)
        assertEquals(calibration, mill.calibration)
        assertEquals(hd.key, CameraModeMemory.selectionFor(rows, "lathe", logitech.key)!!.modeKey)
        assertNull(CameraModeMemory.selectionFor(rows, "mill", microscope.key))
    }

    @Test
    fun aProfileDeleteKeepsTheSharedResults() {
        val rows = listOf(
            CameraSelection("mill", logitech.key, hd.key, t1),
            CameraSelection("lathe", logitech.key, hd.key, t1),
        )
        assertEquals(listOf(rows[1]), CameraModeMemory.removeProfile(rows, "mill"))
    }

    @Test
    fun thePlayCameraIsTheAttachedCameraWithTheNewestSelection() {
        val rows = listOf(
            CameraSelection("mill", logitech.key, hd.key, t1),
            CameraSelection("mill", microscope.key, hd.key, t2),
            CameraSelection("mill", "aaaa:bbbb", hd.key, t3),
        )
        val play = CameraModeMemory.cameraForPlay(listOf(logitech, microscope), rows, "mill", now = Instant.fromEpochMilliseconds(9_000L))!!
        assertEquals(microscope, play.camera)
        // The newest row is for a camera that is not attached, so the row gets a new time.
        assertEquals(Instant.fromEpochMilliseconds(9_000L), play.selection!!.updatedOn)
        val touched = play.touchedSelections!!
        assertEquals(Instant.fromEpochMilliseconds(9_000L), CameraModeMemory.selectionFor(touched, "mill", microscope.key)!!.updatedOn)

        val newest = CameraModeMemory.cameraForPlay(listOf(logitech, microscope), rows.dropLast(1), "mill", t3)!!
        assertEquals(microscope, newest.camera)
        assertNull(newest.touchedSelections)

        val none = CameraModeMemory.cameraForPlay(listOf(logitech, microscope), emptyList(), "mill", t3)!!
        assertEquals(logitech, none.camera)
        assertNull(none.selection)
        assertNull(CameraModeMemory.cameraForPlay(emptyList(), rows, "mill", t3))
    }

    @Test
    fun thePlayChoiceNeedsASavedModeOrAGoodMode() {
        val modes = listOf(hd, vga)
        assertEquals(PlayChoice.NoCamera, CameraModeMemory.playChoice(null, modes, emptyList(), emptyList(), "mill"))
        val saved = listOf(CameraSelection("mill", logitech.key, vga.key, t1))
        assertEquals(PlayChoice.Start(logitech, vga), CameraModeMemory.playChoice(logitech, modes, saved, emptyList(), "mill"))
        val goodRows = listOf(CameraModeResult(logitech.key, hd.key, CameraModeStatus.Good))
        assertEquals(PlayChoice.Start(logitech, hd), CameraModeMemory.playChoice(logitech, modes, emptyList(), goodRows, "mill"))
        val otherCamera = listOf(CameraSelection("mill", microscope.key, vga.key, t1))
        assertEquals(PlayChoice.WrongCamera, CameraModeMemory.playChoice(logitech, modes, otherCamera, emptyList(), "mill"))
        assertEquals(PlayChoice.NeedsSetup, CameraModeMemory.playChoice(logitech, modes, emptyList(), emptyList(), "mill"))
        val otherGood = listOf(CameraModeResult(microscope.key, hd.key, CameraModeStatus.Good))
        assertEquals(PlayChoice.NeedsSetup, CameraModeMemory.playChoice(logitech, modes, emptyList(), otherGood, "mill"))
    }

    @Test
    fun theFirstFrameStoresGoodAndTheSelection() {
        val before = CameraMemory(
            results = listOf(CameraModeResult(logitech.key, hd.key, CameraModeStatus.Good, 1280, 720, "MJPEG", 25.0, t1)),
        )
        val after = CameraModeMemory.onFirstFrame(before, "mill", logitech, hd, t2)
        assertEquals(25.0, after.results.single().measuredFps)
        assertEquals(t2, after.results.single().updatedOn)
        assertEquals(CameraSelection("mill", logitech.key, hd.key, t2), after.selections.single())
    }

    @Test
    fun aCameraIsConfiguredForTheProfile() {
        val saved = listOf(CameraSelection("mill", logitech.key, hd.key, t1))
        assertTrue(CameraModeMemory.isConfigured(logitech, saved, emptyList(), "mill"))
        assertFalse(CameraModeMemory.isConfigured(logitech, saved, emptyList(), "lathe"))
        val good = listOf(CameraModeResult(logitech.key, hd.key, CameraModeStatus.Good))
        assertTrue(CameraModeMemory.isConfigured(logitech, emptyList(), good, "lathe"))
        assertFalse(CameraModeMemory.isConfigured(null, saved, good, "mill"))
    }
}
