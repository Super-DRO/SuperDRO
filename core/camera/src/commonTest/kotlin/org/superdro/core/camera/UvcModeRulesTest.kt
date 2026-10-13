package org.superdro.core.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.superdro.core.model.CameraModeResult
import org.superdro.core.model.CameraModeStatus

class UvcModeRulesTest {

    private fun mjpeg(width: Int, height: Int, interval: Long = 333_333L) = UvcMode(UvcCodec.Mjpeg, width, height, interval, "MJPG")
    private fun h264(width: Int, height: Int, interval: Long = 333_333L) = UvcMode(UvcCodec.H264, width, height, interval, "H264")
    private fun yuy2(width: Int, height: Int, interval: Long = 333_333L) = UvcMode(UvcCodec.Uncompressed, width, height, interval, "YUY2")
    private fun good(mode: UvcMode) = CameraModeResult("046d:0825", mode.key, CameraModeStatus.Good)
    private fun bad(mode: UvcMode) = CameraModeResult("046d:0825", mode.key, CameraModeStatus.Bad)

    @Test
    fun theSetupListKeepsTheHighestRealRateOfEachCodecAndSize() {
        val modes = listOf(
            mjpeg(1280, 720, 666_666L),
            mjpeg(1280, 720, 333_333L),
            mjpeg(1280, 720, 1_000_000L),
            yuy2(640, 480, 666_666L),
            UvcMode(UvcCodec.Uncompressed, 640, 480, 333_333L, "NV12"),
            h264(1920, 1080, 400_000L),
            mjpeg(1920, 1080, 333_333L),
        )
        assertEquals(
            listOf(mjpeg(1920, 1080), h264(1920, 1080, 400_000L), mjpeg(1280, 720, 333_333L), yuy2(640, 480, 666_666L)),
            UvcModeRules.setupList(modes),
        )
    }

    @Test
    fun theBestModeFollowsTheOrderOfRuleThree() {
        val all = listOf(yuy2(640, 480), mjpeg(1280, 720), h264(1920, 1080), mjpeg(1920, 1080), mjpeg(3840, 2160))
        assertEquals(mjpeg(1920, 1080), UvcModeRules.bestMode(all))
        assertEquals(h264(1920, 1080), UvcModeRules.bestMode(all - mjpeg(1920, 1080)))
        assertEquals(mjpeg(1280, 720), UvcModeRules.bestMode(listOf(yuy2(640, 480), mjpeg(1280, 720), mjpeg(3840, 2160))))
        assertEquals(yuy2(640, 480), UvcModeRules.bestMode(listOf(yuy2(640, 480), h264(1280, 720), mjpeg(3840, 2160))))
    }

    @Test
    fun theFallbackIsTheLargestModeUpToFullHdThenTheSmallestMode() {
        val largest = UvcModeRules.bestMode(
            listOf(h264(1280, 720, 666_666L), h264(1280, 720, 333_333L), mjpeg(800, 600), mjpeg(2560, 1440))
        )
        assertEquals(h264(1280, 720, 333_333L), largest)
        val smallest = UvcModeRules.bestMode(listOf(mjpeg(3840, 2160), mjpeg(2560, 1440, 666_666L), mjpeg(2560, 1440, 333_333L)))
        assertEquals(mjpeg(2560, 1440, 333_333L), smallest)
        assertNull(UvcModeRules.bestMode(listOf(UvcMode(UvcCodec.Uncompressed, 640, 480, 333_333L, "NV12"))))
        assertNull(UvcModeRules.bestMode(emptyList()))
    }

    @Test
    fun theSetupDefaultIsTheSavedModeThenTheBestGoodModeThenTheBestMode() {
        val modes = listOf(mjpeg(1920, 1080), mjpeg(1280, 720), yuy2(640, 480))
        assertEquals(yuy2(640, 480), UvcModeRules.setupDefault(modes, yuy2(640, 480).key, emptyList()))
        assertEquals(
            mjpeg(1280, 720),
            UvcModeRules.setupDefault(modes, "0/9999/9999/1", listOf(good(mjpeg(1280, 720)), good(yuy2(640, 480)), bad(mjpeg(1920, 1080)))),
        )
        assertEquals(mjpeg(1920, 1080), UvcModeRules.setupDefault(modes, null, listOf(bad(mjpeg(1280, 720)))))
    }

    @Test
    fun thePlayModeUsesOnlyTheSavedModeAndTheGoodModes() {
        val modes = listOf(mjpeg(1920, 1080), mjpeg(1280, 720))
        assertEquals(mjpeg(1280, 720), UvcModeRules.playMode(modes, mjpeg(1280, 720).key, emptyList()))
        assertEquals(mjpeg(1280, 720), UvcModeRules.playMode(modes, null, listOf(good(mjpeg(1280, 720)))))
        assertNull(UvcModeRules.playMode(modes, null, emptyList()))
        assertNull(UvcModeRules.playMode(modes, null, listOf(bad(mjpeg(1920, 1080)))))
    }

    @Test
    fun aCameraIsConfiguredWhenAttachedWithASelectionOrAGoodMode() {
        assertTrue(UvcModeRules.isConfigured(attached = true, hasSavedSelection = true, results = emptyList()))
        assertTrue(UvcModeRules.isConfigured(attached = true, hasSavedSelection = false, results = listOf(good(mjpeg(640, 480)))))
        assertFalse(UvcModeRules.isConfigured(attached = true, hasSavedSelection = false, results = listOf(bad(mjpeg(640, 480)))))
        assertFalse(UvcModeRules.isConfigured(attached = false, hasSavedSelection = true, results = listOf(good(mjpeg(640, 480)))))
    }
}
