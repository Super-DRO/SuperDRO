package org.superdro.validation.store

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.superdro.core.camera.CalibrationResult
import org.superdro.core.camera.CameraCodes
import org.superdro.core.camera.CameraErrorDialog
import org.superdro.core.camera.CameraErrorKind
import org.superdro.core.camera.CameraIdentity
import org.superdro.core.camera.CameraModeMemory
import org.superdro.core.camera.CameraOverlay
import org.superdro.core.camera.CameraRetry
import org.superdro.core.camera.CameraViewSettings
import org.superdro.core.camera.CrosshairColor
import org.superdro.core.camera.Crosshairs
import org.superdro.core.camera.ImageCalibrationMath
import org.superdro.core.camera.ImagePoint
import org.superdro.core.camera.MachinePointUm
import org.superdro.core.camera.ModeTestEffect
import org.superdro.core.camera.ModeTestMachine
import org.superdro.core.camera.ModeTestProgress
import org.superdro.core.camera.ModeTestState
import org.superdro.core.camera.OverlayLayers
import org.superdro.core.camera.UsbVideoClass
import org.superdro.core.camera.UvcCodec
import org.superdro.core.camera.UvcMode
import org.superdro.core.camera.UvcModeRules
import org.superdro.core.camera.ViewLockState
import org.superdro.core.camera.modeToSave
import org.superdro.core.model.CameraModeResult
import org.superdro.core.model.CameraModeStatus
import org.superdro.core.model.CameraSelection
import org.superdro.core.model.MachineProfile
import org.superdro.core.store.valueOrNull

/**
 * The common rules of `core:camera` (the common code of the camera) and
 * the mode memory of `core:store`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CameraRulesTest {

    private val harnesses = mutableListOf<StoreHarness>()

    private fun TestScope.store(): StoreHarness = StoreHarness(StandardTestDispatcher(testScheduler)).also(harnesses::add)

    @AfterTest
    fun closeAll() {
        harnesses.forEach(StoreHarness::close)
    }

    private fun mode(codec: UvcCodec, width: Int, height: Int, fps: Int = 30, fourcc: String = "") =
        UvcMode(codec, width, height, 10_000_000L / fps, fourcc)

    @Test
    fun the_camera_key_is_vid_pid_as_04x_04x_and_the_label_rule() {
        assertEquals("046d:0825", CameraIdentity.keyOf(0x046D, 0x0825))
        assertEquals("0001:00ff", CameraIdentity.keyOf(1, 255))
        assertEquals("Logitech HD Webcam", CameraIdentity(0x046D, 0x0825, "Logitech", "HD Webcam").label)
        assertEquals("046d:0825", CameraIdentity(0x046D, 0x0825, "", "").label)
        assertEquals("046d:0825", CameraIdentity(0x046D, 0x0825).label)
        assertTrue(CameraIdentity(0x046D, 0x0825).labelIsKey)
        // The USB class 14 and the subclass 1 of the attach filter.
        assertEquals(14, UsbVideoClass.CLASS_VIDEO)
        assertEquals(1, UsbVideoClass.SUBCLASS_VIDEO_CONTROL)
        assertTrue(UsbVideoClass.isCamera(listOf(1, 14)))
        assertFalse(UsbVideoClass.isCamera(listOf(1, 2, 255)))
    }

    @Test
    fun the_mode_key_and_the_usable_modes() {
        assertEquals("0/1920/1080/333333", UvcMode(UvcCodec.Mjpeg, 1920, 1080, 333_333).key)
        assertTrue(mode(UvcCodec.Mjpeg, 640, 480).isUsable)
        assertTrue(mode(UvcCodec.H264, 640, 480).isUsable)
        assertTrue(mode(UvcCodec.Uncompressed, 640, 480, fourcc = "YUY2").isUsable)
        assertTrue(mode(UvcCodec.Uncompressed, 640, 480, fourcc = "yuyv").isUsable)
        assertFalse(mode(UvcCodec.Uncompressed, 640, 480, fourcc = "NV12").isUsable)
        assertFalse(mode(UvcCodec.Uncompressed, 640, 480, fourcc = "UYVY").isUsable)
    }

    @Test
    fun the_setup_list_keeps_one_usable_mode_with_the_highest_rate_for_each_codec_and_size() {
        val m30 = mode(UvcCodec.Mjpeg, 1280, 720, fps = 30)
        val m15 = mode(UvcCodec.Mjpeg, 1280, 720, fps = 15)
        val h30 = mode(UvcCodec.H264, 1280, 720, fps = 30)
        val nv12 = mode(UvcCodec.Uncompressed, 1280, 720, fourcc = "NV12")
        val list = UvcModeRules.setupList(listOf(m15, nv12, h30, m30))
        assertEquals(setOf(m30, h30), list.toSet())
        assertEquals(2, list.size)
    }

    @Test
    fun the_default_mode_order_of_camera_8_3() {
        val mjpeg1080 = mode(UvcCodec.Mjpeg, 1920, 1080)
        val h2641080 = mode(UvcCodec.H264, 1920, 1080)
        val mjpeg720 = mode(UvcCodec.Mjpeg, 1280, 720)
        val yuy2480 = mode(UvcCodec.Uncompressed, 640, 480, fourcc = "YUY2")
        val all = listOf(yuy2480, mjpeg720, h2641080, mjpeg1080)
        assertEquals(mjpeg1080, UvcModeRules.bestMode(all))
        assertEquals(h2641080, UvcModeRules.bestMode(all - mjpeg1080))
        assertEquals(mjpeg720, UvcModeRules.bestMode(all - mjpeg1080 - h2641080))
        assertEquals(yuy2480, UvcModeRules.bestMode(listOf(yuy2480, mode(UvcCodec.Mjpeg, 320, 240))))
        // Else the largest mode with not more than 2 073 600 pixels, the frame rate as the second key.
        val large15 = mode(UvcCodec.Mjpeg, 1600, 1200, fps = 15)
        val large30 = mode(UvcCodec.Mjpeg, 1600, 1200, fps = 30)
        val huge = mode(UvcCodec.Mjpeg, 3840, 2160)
        assertEquals(large30, UvcModeRules.bestMode(listOf(huge, large15, large30, mode(UvcCodec.Mjpeg, 800, 600))))
        // Else the smallest mode, the higher frame rate first.
        val big5 = mode(UvcCodec.Mjpeg, 2592, 1944, fps = 5)
        val big10 = mode(UvcCodec.Mjpeg, 2592, 1944, fps = 10)
        assertEquals(big10, UvcModeRules.bestMode(listOf(huge, big5, big10)))
        assertNull(UvcModeRules.bestMode(listOf(mode(UvcCodec.Uncompressed, 640, 480, fourcc = "NV12"))))
    }

    @Test
    fun the_setup_default_and_the_play_mode() {
        val now = Instant.parse("2026-10-11T14:00:00Z")
        val mjpeg1080 = mode(UvcCodec.Mjpeg, 1920, 1080)
        val mjpeg720 = mode(UvcCodec.Mjpeg, 1280, 720)
        val modes = listOf(mjpeg1080, mjpeg720)
        val good720 = listOf(CameraModeResult("046d:0825", mjpeg720.key, CameraModeStatus.Good, 1280, 720, "MJPEG", 29.9, now))
        assertEquals(mjpeg720, UvcModeRules.setupDefault(modes, savedModeKey = mjpeg720.key, results = emptyList()))
        assertEquals(mjpeg720, UvcModeRules.setupDefault(modes, savedModeKey = null, results = good720))
        assertEquals(mjpeg1080, UvcModeRules.setupDefault(modes, savedModeKey = null, results = emptyList()))
        // The video start uses only the saved selection and the GOOD modes.
        assertEquals(mjpeg720, UvcModeRules.playMode(modes, null, good720))
        assertNull(UvcModeRules.playMode(modes, null, emptyList()))
        assertTrue(UvcModeRules.isConfigured(attached = true, hasSavedSelection = false, results = good720))
        assertTrue(UvcModeRules.isConfigured(attached = true, hasSavedSelection = true, results = emptyList()))
        assertFalse(UvcModeRules.isConfigured(attached = true, hasSavedSelection = false, results = emptyList()))
        assertFalse(UvcModeRules.isConfigured(attached = false, hasSavedSelection = true, results = good720))
    }

    @Test
    fun the_mode_test_has_2_attempts_of_3500_ms_and_a_restart_check() {
        assertEquals(3500L, CameraIdentity.MODE_TEST_TIMEOUT_MS)
        assertEquals(2, CameraIdentity.MODE_TEST_ATTEMPTS)
        val pass = ModeTestMachine()
        assertEquals(listOf(ModeTestEffect.StartPreview, ModeTestEffect.StartTimer(3500)), pass.test())
        assertEquals(ModeTestState.Testing, pass.state)
        assertEquals(listOf(ModeTestEffect.RestartPreview, ModeTestEffect.StartTimer(3500)), pass.onTimer(frames = 40, measuredFps = 29.8))
        assertEquals(ModeTestState.Verifying, pass.state)
        assertEquals(listOf(ModeTestEffect.Store(CameraModeStatus.Good, 29.8)), pass.onTimer(frames = 40, measuredFps = 29.8))
        assertEquals(ModeTestState.Ok, pass.state)
        val fail = ModeTestMachine()
        fail.test()
        assertEquals(listOf(ModeTestEffect.RestartPreview, ModeTestEffect.StartTimer(3500)), fail.onTimer(frames = 0, measuredFps = null))
        assertEquals(2, fail.attempt)
        assertEquals(listOf(ModeTestEffect.StopPreview, ModeTestEffect.Store(CameraModeStatus.Bad, null)), fail.onTimer(frames = 0, measuredFps = null))
        assertEquals(ModeTestState.Failed, fail.state)
    }

    @Test
    fun the_mode_test_errors_cancel_and_save_rules() {
        val modeFailed = ModeTestMachine().also { it.test() }
        assertTrue(ModeTestEffect.Store(CameraModeStatus.Bad, null) in modeFailed.onError(CameraErrorKind.ModeFailed))
        assertEquals(ModeTestState.Failed, modeFailed.state)
        val other = ModeTestMachine().also { it.test() }
        assertTrue(other.onError(CameraErrorKind.Disconnected).none { it is ModeTestEffect.Store })
        assertEquals(ModeTestState.Failed, other.state)
        val noFrames = ModeTestMachine().also { it.test() }
        assertEquals(emptyList(), noFrames.onError(CameraErrorKind.NoFrames))
        assertEquals(ModeTestState.Testing, noFrames.state)
        val cancel = ModeTestMachine().also { it.test() }
        cancel.cancel(CameraModeStatus.Good)
        assertEquals(ModeTestState.KnownGood, cancel.state)
        // "Save" saves the selected mode in KNOWN_GOOD and the tested mode in OK. Else nothing.
        val selected = mode(UvcCodec.Mjpeg, 1280, 720)
        val tested = mode(UvcCodec.Mjpeg, 1920, 1080)
        assertEquals(selected, modeToSave(ModeTestState.KnownGood, selected, tested))
        assertEquals(tested, modeToSave(ModeTestState.Ok, selected, tested))
        listOf(ModeTestState.Untested, ModeTestState.KnownBad, ModeTestState.Testing, ModeTestState.Verifying, ModeTestState.Failed)
            .forEach { state -> assertNull(modeToSave(state, selected, tested), "$state") }
    }

    @Test
    fun the_progress_formula_of_camera_11_4() {
        // round(((clamp(t, 0, 3500) / 3500) + (attempt − 1)) × 25) + (50 if VERIFYING)
        assertEquals(0, ModeTestProgress.formula(ModeTestState.Testing, 1, 0))
        assertEquals(13, ModeTestProgress.formula(ModeTestState.Testing, 1, 1750))
        assertEquals(25, ModeTestProgress.formula(ModeTestState.Testing, 1, 9000))
        assertEquals(50, ModeTestProgress.formula(ModeTestState.Testing, 2, 3500))
        assertEquals(50, ModeTestProgress.formula(ModeTestState.Verifying, 1, 0))
        assertEquals(75, ModeTestProgress.formula(ModeTestState.Verifying, 1, 3500))
        assertEquals(0, ModeTestProgress.formula(ModeTestState.Testing, 1, -100))
        val progress = ModeTestProgress()
        assertEquals(25, progress.update(ModeTestState.Testing, 1, 3500))
        assertEquals(25, progress.update(ModeTestState.Testing, 1, 100), "the value does not go down")
        assertEquals(100L, ModeTestProgress.TICK_MS)
    }

    @Test
    fun the_13_error_kinds_the_retry_set_and_the_dialogs() {
        assertEquals(13, CameraErrorKind.entries.size)
        // The retry set {transfer memory, mode failed}, 3 attempts with 400 ms.
        assertEquals(setOf(CameraErrorKind.UsbTransferMemory, CameraErrorKind.ModeFailed), CameraRetry.RETRY_SET)
        assertEquals(3, CameraRetry.MAX_ATTEMPTS)
        assertEquals(400L, CameraRetry.PAUSE_MS)
        assertEquals(CameraErrorKind.UsbTransferMemory, CameraCodes.startKind(-11))
        assertEquals(CameraErrorKind.AlreadyStreaming, CameraCodes.startKind(-100))
        assertEquals(CameraErrorKind.NoDevice, CameraCodes.startKind(-101))
        assertEquals(CameraErrorKind.CameraServiceUnavailable, CameraCodes.startKind(-102))
        assertEquals(CameraErrorKind.CameraServiceUnavailable, CameraCodes.startKind(-103))
        assertEquals(CameraErrorKind.RenderFailed, CameraCodes.startKind(-104))
        assertEquals(CameraErrorKind.ModeFailed, CameraCodes.startKind(-51))
        assertEquals(CameraErrorKind.ModeFailed, CameraCodes.startKind(null))
        assertEquals(-11, CameraCodes.codeOf("uvc start failed (-11)"))
        // The mode list errors.
        assertEquals(CameraErrorKind.CameraServiceUnavailable, CameraCodes.listKind(Int.MIN_VALUE))
        assertEquals(CameraErrorKind.NoDevice, CameraCodes.listKind(-1))
        assertEquals(CameraErrorKind.OpenFailed, CameraCodes.listKind(-6))
        assertNull(CameraCodes.listKind(0))
        assertEquals(CameraErrorDialog("camera_play_needs_setup", offersSetup = true), CameraErrorDialog.of(CameraErrorKind.NoFrames))
        assertEquals(CameraErrorDialog("camera_play_needs_setup", offersSetup = true), CameraErrorDialog.of(CameraErrorKind.ModeFailed))
        assertTrue(CameraErrorDialog.of(CameraErrorKind.CameraPermissionMissing).offersSetup)
        assertFalse(CameraErrorDialog.of(CameraErrorKind.Disconnected).offersSetup)
    }

    @Test
    fun the_six_crosshair_colors() {
        assertEquals(
            listOf("default", "white", "black", "cherry", "dark_green", "dark_magenta"),
            CrosshairColor.entries.map(CrosshairColor::key),
        )
        assertEquals(0xFFFFFFFFL, CrosshairColor.White.argb)
        assertEquals(0xFF000000L, CrosshairColor.Black.argb)
        assertEquals(0xFF8C0020L, CrosshairColor.Cherry.argb)
        assertEquals(0xFF0B5D1EL, CrosshairColor.DarkGreen.argb)
        assertEquals(0xFF7A0C6EL, CrosshairColor.DarkMagenta.argb)
        // "default": the theme cursor color with the alpha 200.
        assertEquals(0xC8123456L, CrosshairColor.Default.resolve(0xFF123456L))
        assertEquals(CrosshairColor.Default, CrosshairColor.fromKey(null))
        assertEquals(CrosshairColor.Default, CameraViewSettings().crosshairColor)
        assertFalse(CameraViewSettings().keepOverlays)
        // The color applies only while the video plays. "Half opacity" multiplies the alpha by 0.5.
        val settings = CameraViewSettings(crosshairColor = CrosshairColor.Cherry)
        assertEquals(0xFF8C0020L, CameraOverlay.crosshairArgb(videoOn = true, settings, themeCursorArgb = 0xFF00FF00L, dimmed = false))
        assertEquals(0xFF00FF00L, CameraOverlay.crosshairArgb(videoOn = false, settings, themeCursorArgb = 0xFF00FF00L, dimmed = false))
        assertEquals(0x6400FF00L, CameraOverlay.crosshairArgb(videoOn = true, CameraViewSettings(), themeCursorArgb = 0xFF00FF00L, dimmed = true))
    }

    @Test
    fun the_crosshairs_the_view_lock_and_the_overlay_rules() {
        val locked = Crosshairs.layout(1000.0, 600.0, locked = true, screenPixelsPerMm = null, metric = true, density = 1.0)
        assertEquals(500.0, locked.centerX)
        assertEquals(300.0, locked.centerY)
        assertEquals(500.0, locked.halfWidthPx)
        assertEquals(300.0, locked.halfHeightPx)
        // Lock off: the cross has the size 0.25 × min(view width, view height).
        val free = Crosshairs.layout(1000.0, 600.0, locked = false, screenPixelsPerMm = null, metric = true, density = 1.0)
        assertEquals(75.0, free.halfWidthPx)
        assertEquals(75.0, free.halfHeightPx)
        assertEquals(listOf(10_000.0, 5_000.0, 1_000.0), Crosshairs.METRIC_INTERVALS_UM)
        assertEquals(listOf(25_400.0, 2_540.0, 254.0), Crosshairs.INCH_INTERVALS_UM)
        // The video holds the view lock, and the old state comes back after the video.
        val start = ViewLockState(locked = false)
        val playing = start.onVideoStarted()
        assertTrue(playing.locked)
        assertTrue(playing.heldByCamera)
        assertEquals(start, playing.onVideoStopped())
        assertEquals(ViewLockState(locked = true), ViewLockState(locked = true).onVideoStarted().onVideoStopped())
        // The overlay hide rules.
        val all = OverlayLayers()
        val hidden = CameraOverlay.visibleLayers(videoOn = true, CameraViewSettings(), all)
        assertEquals(OverlayLayers(crosshairs = true, subDatums = false, guides = false, toolShape = false, drawing = false, referenceImage = false), hidden)
        val kept = CameraOverlay.visibleLayers(videoOn = true, CameraViewSettings(keepOverlays = true), all)
        assertEquals(OverlayLayers(referenceImage = false), kept)
        assertEquals(all, CameraOverlay.visibleLayers(videoOn = false, CameraViewSettings(), all))
        assertTrue(CameraOverlay.transparentBackground(videoOn = true))
        assertEquals(1.0f, CameraOverlay.cameraRowAlpha(cameraAttached = true))
        assertEquals(0.4f, CameraOverlay.cameraRowAlpha(cameraAttached = false))
    }

    @Test
    fun the_image_scale_from_two_known_points() {
        // Two image points 100 px apart with 2 mm between them: 50 px/mm.
        val result = assertIs<CalibrationResult.Ok>(
            ImageCalibrationMath.fromKnownDistance(ImagePoint(100.0, 100.0), ImagePoint(160.0, 180.0), 2000.0, 1920, 1080),
        )
        assertEquals(50.0, result.calibration.pixelsPerMm, 1e-9)
        assertEquals(1920, result.calibration.imageWidth)
        // A mode with the same aspect ratio uses the scale times the width ratio.
        assertEquals(50.0 * 1280 / 1920, ImageCalibrationMath.pixelsPerMmFor(result.calibration, 1280, 720)!!, 1e-9)
        assertNull(ImageCalibrationMath.pixelsPerMmFor(result.calibration, 640, 480), "a different aspect ratio")
        assertIs<CalibrationResult.DistanceTooSmall>(ImageCalibrationMath.fromKnownDistance(ImagePoint(0.0, 0.0), ImagePoint(100.0, 0.0), 0.0, 1920, 1080))
        // A machine move of 2 mm in +Y moves the feature 100 px to the right in the image: 50 px/mm and −90°.
        val move = assertIs<CalibrationResult.Ok>(
            ImageCalibrationMath.fromMachineMove(ImagePoint(500.0, 500.0), ImagePoint(600.0, 500.0), MachinePointUm(0.0, 0.0), MachinePointUm(0.0, 2000.0), 1920, 1080),
        )
        assertEquals(50.0, move.calibration.pixelsPerMm, 1e-9)
        assertEquals(-324_000.0, move.calibration.rotationArcSec, 1e-6)
        // The crosshair ticks measure the image: 10 mm at 50 px/mm is 500 screen px at a video scale of 1.
        val ticks = Crosshairs.layout(1920.0, 1080.0, locked = true, screenPixelsPerMm = ImageCalibrationMath.screenPixelsPerMm(50.0, 1.0), metric = true, density = 1.0).ticks
        assertTrue(ticks.any { tick -> tick.offsetPx == 500.0 && tick.valueUm == 10_000.0 })
    }

    @Test
    fun the_mode_memory_rules_of_camera_12() {
        val t0 = Instant.parse("2026-10-11T14:00:00Z")
        val m = mode(UvcCodec.Mjpeg, 1920, 1080)
        val good = CameraModeMemory.record(emptyList(), "046d:0825", m, CameraModeStatus.Good, 29.9, t0)
        // A GOOD write with no rate keeps the old rate. A BAD write has no rate.
        val again = CameraModeMemory.record(good, "046d:0825", m, CameraModeStatus.Good, null, t0)
        assertEquals(29.9, again.single().measuredFps)
        val bad = CameraModeMemory.record(again, "046d:0825", m, CameraModeStatus.Bad, 12.0, t0)
        assertEquals(CameraModeStatus.Bad, bad.single().status)
        assertNull(bad.single().measuredFps)
        // A profile delete removes its selections and keeps the shared mode results.
        val selections = CameraModeMemory.select(emptyList(), "p1", "046d:0825", m, t0)
        assertEquals(emptyList(), CameraModeMemory.removeProfile(selections, "p1"))
    }

    @Test
    fun the_store_shares_the_mode_results_and_keeps_one_selection_for_each_profile_and_camera() = runTest {
        val store = store()
        val t0 = Instant.parse("2026-10-11T14:00:00Z")
        val profile = store.machines.createProfile(MachineProfile.create(name = "Cam")).valueOrNull()!!
        val key = "046d:0825"
        store.cameras.saveModeResult(CameraModeResult(key, "0/1920/1080/333333", CameraModeStatus.Good, 1920, 1080, "MJPEG", 29.9, t0))
        store.cameras.saveModeResult(CameraModeResult(key, "0/1920/1080/333333", CameraModeStatus.Good, 1920, 1080, "MJPEG", null, t0))
        assertEquals(29.9, store.cameras.loadModeResults(key).single().measuredFps, "a GOOD write with no rate keeps the old rate")
        store.cameras.saveSelection(CameraSelection(profile.id, key, "0/1920/1080/333333", t0))
        store.cameras.saveSelection(CameraSelection(profile.id, key, "0/1280/720/333333", t0))
        assertEquals(listOf("0/1280/720/333333"), store.cameras.selections(profile.id).first().map { it.modeKey })
        store.machines.deleteProfile(profile.id, deleteTools = true)
        assertTrue(store.cameras.selections(profile.id).first().isEmpty())
        assertEquals(1, store.cameras.loadModeResults(key).size, "the mode results stay after a profile delete")
    }
}
