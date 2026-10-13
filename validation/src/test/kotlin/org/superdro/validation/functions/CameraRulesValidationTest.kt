package org.superdro.validation.functions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.superdro.core.camera.CalibrationResult
import org.superdro.core.camera.CameraCodes
import org.superdro.core.camera.CameraErrorKind
import org.superdro.core.camera.H264Timestamps
import org.superdro.core.camera.ImageCalibrationMath
import org.superdro.core.camera.ImagePoint
import org.superdro.core.camera.MachinePointUm
import org.superdro.core.camera.ModeLabel
import org.superdro.core.camera.UvcCodec
import org.superdro.core.camera.UvcMode

/**
 * The camera rules. Only the rules that a JVM test can examine
 * are here. The native rules
 * (the interval of the start request, the time limit of the UVC control transfers) are not.
 */
class CameraRulesValidationTest {

    @Test
    fun the_mode_text_uses_the_real_frame_interval_and_not_up_to() {
        val fifteen = ModeLabel.of(UvcMode(UvcCodec.Mjpeg, 1920, 1080, frameInterval = 666_666), null)
        val thirty = ModeLabel.of(UvcMode(UvcCodec.Mjpeg, 1920, 1080, frameInterval = 333_333), null)
        assertEquals("15", fifteen.nominalFps)
        assertEquals("30", thirty.nominalFps)
        assertNotEquals(fifteen.englishText(), thirty.englishText(), "each interval has its own line")
        assertFalse("up to" in fifteen.englishText(), fifteen.englishText())
    }

    @Test
    fun h264_time_stamps_use_the_negotiated_interval() {
        val stamps = H264Timestamps(H264Timestamps.intervalUs(666_666))
        val values = List(3) { stamps.next(0L) }
        assertEquals(listOf(0L, 66_666L, 133_332L), values, "15 fps, not a fixed 33 333 µs step")
    }

    @Test
    fun an_init_failure_does_not_show_the_powered_hub_hint() {
        assertNotEquals(CameraErrorKind.NeedsPoweredHub, CameraCodes.startKind(CameraCodes.INIT_FAILED))
        assertNotEquals(CameraErrorKind.NeedsPoweredHub, CameraCodes.listKind(CameraCodes.INIT_FAILED))
    }

    @Test
    fun h264_modes_are_usable() {
        assertTrue(UvcMode(UvcCodec.H264, 1920, 1080, 333_333).isUsable)
    }

    @Test
    fun image_calibration_in_pixels_for_each_mm_from_two_known_points() {
        val known = assertIs<CalibrationResult.Ok>(
            ImageCalibrationMath.fromKnownDistance(ImagePoint(100.0, 100.0), ImagePoint(300.0, 100.0), 10_000.0, 1920, 1080),
        )
        assertEquals(20.0, known.calibration.pixelsPerMm, 1e-9, "200 px for 10 mm")
        val move = assertIs<CalibrationResult.Ok>(
            ImageCalibrationMath.fromMachineMove(
                ImagePoint(100.0, 500.0), ImagePoint(300.0, 500.0), MachinePointUm(0.0, 0.0), MachinePointUm(10_000.0, 0.0), 1920, 1080,
            ),
        )
        assertEquals(20.0, move.calibration.pixelsPerMm, 1e-9)
        assertEquals(0.0, move.calibration.rotationArcSec, 1e-9)
    }
}
