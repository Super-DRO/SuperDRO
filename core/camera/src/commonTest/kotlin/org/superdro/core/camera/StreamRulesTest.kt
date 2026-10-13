package org.superdro.core.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamRulesTest {

    @Test
    fun theBufferHasOneMibOrMoreAndAFrameHasNotMoreThan64Mib() {
        assertEquals(1_048_576, StreamRules.bufferSize(0L))
        assertEquals(1_048_576, StreamRules.bufferSize(614_400L))
        assertEquals(4_147_200, StreamRules.bufferSize(4_147_200L))
        assertEquals(67_108_864, StreamRules.bufferSize(67_108_864L))
        assertNull(StreamRules.bufferSize(67_108_865L))
        assertEquals("frame size 67108865 exceeds the ceiling", StreamRules.frameSizeText(67_108_865L))
    }

    @Test
    fun theFrameCheckOfEachCodec() {
        val jpeg = ByteArray(1024).also { data ->
            data[0] = 0xFF.toByte()
            data[1] = 0xD8.toByte()
        }
        assertTrue(StreamRules.accepts(UvcCodec.Mjpeg, jpeg, 1024, 640, 480))
        assertFalse(StreamRules.accepts(UvcCodec.Mjpeg, jpeg, 1023, 640, 480))
        assertFalse(StreamRules.accepts(UvcCodec.Mjpeg, ByteArray(2048), 2048, 640, 480))
        assertTrue(StreamRules.accepts(UvcCodec.H264, ByteArray(8), 8, 640, 480))
        assertFalse(StreamRules.accepts(UvcCodec.H264, ByteArray(8), 7, 640, 480))
        assertTrue(StreamRules.accepts(UvcCodec.Uncompressed, ByteArray(0), 614_400, 640, 480))
        assertFalse(StreamRules.accepts(UvcCodec.Uncompressed, ByteArray(0), 614_399, 640, 480))
        assertFalse(StreamRules.accepts(UvcCodec.Unknown, jpeg, 1024, 640, 480))
        assertEquals(614_400L, StreamRules.yuy2Bytes(640, 480))
    }

    @Test
    fun twelveEmptyWaitsOf250MsStopTheRun() {
        val watchdog = FrameWatchdog()
        repeat(11) { assertFalse(watchdog.onWait(gotFrame = false)) }
        assertFalse(watchdog.onWait(gotFrame = true))
        repeat(11) { assertFalse(watchdog.onWait(gotFrame = false)) }
        assertTrue(watchdog.onWait(gotFrame = false))
        assertEquals("no frames for 3000 ms", watchdog.endText())
        assertEquals(CameraErrorKind.NoFrames, StreamRules.endKind(0L))
        assertEquals(CameraErrorKind.Disconnected, StreamRules.endKind(1L))
    }

    @Test
    fun theRateIsFramesMinusOneOverTheTime() {
        val statistics = StreamStatistics()
        assertTrue(statistics.onFrame(1_000L))
        assertEquals(0.0, statistics.fps)
        assertFalse(statistics.onFrame(1_100L))
        assertFalse(statistics.onFrame(1_200L))
        assertTrue(statistics.onFrame(1_250L))
        assertEquals(4, statistics.frames)
        // (4 − 1) × 1000 / 250 = 12 fps.
        assertEquals(12.0, statistics.fps, 1e-9)
    }

    @Test
    fun theDecoderStartsAtAnSps() {
        assertTrue(H264Stream.containsSps(byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0x00)))
        assertTrue(H264Stream.containsSps(byteArrayOf(0, 0, 1, 0x09, 0x10, 0, 0, 1, 0x27, 0x42)))
        assertFalse(H264Stream.containsSps(byteArrayOf(0, 0, 0, 1, 0x68, 0x42)))
        assertFalse(H264Stream.containsSps(byteArrayOf(0, 0, 0, 1, 0x65, 0x42)))
        assertFalse(H264Stream.containsSps(byteArrayOf(0, 0, 1, 0x67), length = 3))
        assertFalse(H264Stream.containsSps(byteArrayOf(0x67, 0, 0, 1)))
    }

    @Test
    fun theTimeStampsComeFromTheCaptureTime() {
        // A camera at 15 fps: the frames come 66.7 ms apart, not 33.3 ms.
        val stamps = H264Timestamps(H264Timestamps.intervalUs(666_666L))
        assertEquals(0L, stamps.next(5_000_000_000L))
        assertEquals(66_666L, stamps.next(5_066_666_667L))
        assertEquals(133_333L, stamps.next(5_133_333_333L))
        // No capture time: the last value plus the negotiated interval.
        assertEquals(199_999L, stamps.next(0L))
        // A capture time that does not increase: the step again.
        assertEquals(266_665L, stamps.next(5_100_000_000L))
        assertEquals(66_666L, H264Timestamps.intervalUs(666_666L))
        assertEquals(33_333L, H264Timestamps(0L).let { fallback -> fallback.next(0L); fallback.next(0L) })
    }

    @Test
    fun thePreviewFitsAndTheBackdropFills() {
        assertEquals(16.0 / 9.0, VideoFit.aspect(0, 0))
        assertEquals(4.0 / 3.0, VideoFit.aspect(640, 480))
        assertEquals(1000 to 563, VideoFit.size(1000, 1000, 16.0 / 9.0, fill = false))
        assertEquals(1778 to 1000, VideoFit.size(1000, 1000, 16.0 / 9.0, fill = true))
        assertEquals(1600 to 900, VideoFit.size(2000, 900, 16.0 / 9.0, fill = false))
        assertEquals(2000 to 1125, VideoFit.size(2000, 900, 16.0 / 9.0, fill = true))
        assertEquals(0 to 0, VideoFit.size(0, -5, 1.0, fill = true))
        assertEquals(1.5, VideoFit.scale(1920.0, 900.0, 1280, 720, fill = true))
        assertEquals(1.25, VideoFit.scale(1920.0, 900.0, 1280, 720, fill = false))
    }

    @Test
    fun theTimeLimitsOfTheCamera() {
        assertEquals(5_000L, CameraTimeouts.SERVICE_BIND_MS)
        assertEquals(8_000L, CameraTimeouts.LIST_MODES_MS)
        assertEquals(20_000L, CameraTimeouts.START_MS)
        assertEquals(1_500L, CameraTimeouts.RENDER_STOP_MS)
        assertEquals(5_000, CameraTimeouts.CONTROL_TRANSFER_MS)
        assertEquals(5_000L, CameraTimeouts.USB_PERMISSION_BACK_OFF_MS)
        assertEquals(3_500L, CameraIdentity.MODE_TEST_TIMEOUT_MS)
        assertEquals(2, CameraIdentity.MODE_TEST_ATTEMPTS)
    }
}
