package org.superdro.core.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.superdro.core.model.CameraModeResult
import org.superdro.core.model.CameraModeStatus

class ModeTextTest {

    @Test
    fun theRateTextHasNoTrailingZeros() {
        assertEquals("30", FrameRateText.format(10_000_000.0 / 333_333, 2))
        assertEquals("29.97", FrameRateText.format(10_000_000.0 / 333_667, 2))
        assertEquals("7.5", FrameRateText.format(10_000_000.0 / 1_333_333, 2))
        assertEquals("15", FrameRateText.format(10_000_000.0 / 666_666, 2))
        assertEquals("29.8", FrameRateText.format(29.83, 1))
        assertEquals("7.3", FrameRateText.format(7.25, 1))
        assertEquals("29,97", FrameRateText.format(29.97, 2, decimalSeparator = ','))
        assertNull(FrameRateText.format(0.0, 2))
        assertNull(FrameRateText.format(-1.0, 2))
    }

    @Test
    fun theModeLineShowsTheRateOfItsOwnInterval() {
        // The frame descriptor has the default interval 333333 (30 fps). The mode of the
        // interval 666666 shows 15 fps, not "up to 30 fps".
        val mode = UvcMode(UvcCodec.Mjpeg, 1920, 1080, 666_666L, "MJPG")
        val label = ModeLabel.of(mode, null)
        assertEquals("MJPEG 1920 × 1080, 15 fps", label.englishText())
        assertEquals("camera_mode_format", label.formatKey)
        assertNull(label.status)
    }

    @Test
    fun aMeasuredRateShowsBesideTheNominalRate() {
        val mode = UvcMode(UvcCodec.H264, 1280, 720, 333_333L, "H264")
        val result = CameraModeResult("046d:0825", mode.key, CameraModeStatus.Good, 1280, 720, "H264", 24.87)
        val label = ModeLabel.of(mode, result)
        assertEquals("H.264 1280 × 720, 30 fps (measured 24.9 fps)", label.englishText())
        assertEquals("camera_mode_format_measured", label.formatKey)
        assertEquals(CameraModeStatus.Good, label.status)
        val same = ModeLabel.of(mode, result.copy(measuredFps = 29.99))
        assertEquals("H.264 1280 × 720, 30 fps", same.englishText())
    }

    @Test
    fun aModeWithNoIntervalHasNoRate() {
        val label = ModeLabel.of(UvcMode(UvcCodec.Uncompressed, 640, 480, 0L, "YUY2"), null)
        assertEquals("YUY2 640 × 480", label.englishText())
        assertEquals("camera_mode_format_no_rate", label.formatKey)
    }

    @Test
    fun eachCodecHasAText() {
        assertEquals("camera_codec_mjpeg", ModeLabel.codecKey(UvcCodec.Mjpeg))
        assertEquals("camera_codec_h264", ModeLabel.codecKey(UvcCodec.H264))
        assertEquals("camera_codec_yuy2", ModeLabel.codecKey(UvcCodec.Uncompressed))
        assertEquals("camera_codec_unknown", ModeLabel.codecKey(UvcCodec.Unknown))
    }
}
