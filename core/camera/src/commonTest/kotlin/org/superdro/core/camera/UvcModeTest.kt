package org.superdro.core.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UvcModeTest {

    @Test
    fun theModeKeyIsCodecWidthHeightAndInterval() {
        assertEquals("0/1920/1080/333333", UvcMode(UvcCodec.Mjpeg, 1920, 1080, 333_333L, "MJPG").key)
        assertEquals("1/1280/720/666666", UvcMode(UvcCodec.H264, 1280, 720, 666_666L, "H264").key)
        assertEquals("2/640/480/0", UvcMode(UvcCodec.Uncompressed, 640, 480, 0L, "YUY2").key)
    }

    @Test
    fun theFrameRateComesFromTheRealInterval() {
        assertEquals(30.0, UvcMode(UvcCodec.Mjpeg, 640, 480, 333_333L).frameRate, 0.001)
        assertEquals(15.0, UvcMode(UvcCodec.Mjpeg, 640, 480, 666_666L).frameRate, 0.001)
        assertEquals(7.5, UvcMode(UvcCodec.Mjpeg, 640, 480, 1_333_333L).frameRate, 0.001)
        assertEquals(0.0, UvcMode(UvcCodec.Mjpeg, 640, 480, 0L).frameRate)
        assertEquals(333_333L, UvcMode.intervalOf(30.0))
        assertEquals(0L, UvcMode.intervalOf(0.0))
    }

    @Test
    fun mjpegAndH264AreUsableAndUncompressedOnlyAsYuy2() {
        assertTrue(UvcMode(UvcCodec.Mjpeg, 1920, 1080, 333_333L, "MJPG").isUsable)
        assertTrue(UvcMode(UvcCodec.H264, 1920, 1080, 333_333L, "H264").isUsable)
        assertTrue(UvcMode(UvcCodec.Uncompressed, 640, 480, 333_333L, "YUY2").isUsable)
        assertTrue(UvcMode(UvcCodec.Uncompressed, 640, 480, 333_333L, "yuyv").isUsable)
        assertFalse(UvcMode(UvcCodec.Uncompressed, 640, 480, 333_333L, "NV12").isUsable)
        assertFalse(UvcMode(UvcCodec.Uncompressed, 640, 480, 333_333L, "UYVY").isUsable)
        assertFalse(UvcMode(UvcCodec.Uncompressed, 640, 480, 333_333L, "Y800").isUsable)
        assertFalse(UvcMode(UvcCodec.Unknown, 640, 480, 333_333L, "M2TS").isUsable)
        assertFalse(UvcMode(UvcCodec.Mjpeg, 0, 480, 333_333L, "MJPG").isUsable)
    }

    @Test
    fun theCodecValuesAreTheNativeValues() {
        assertEquals(UvcCodec.Mjpeg, UvcCodec.fromCode(0))
        assertEquals(UvcCodec.H264, UvcCodec.fromCode(1))
        assertEquals(UvcCodec.Uncompressed, UvcCodec.fromCode(2))
        assertEquals(UvcCodec.Unknown, UvcCodec.fromCode(-1))
        assertEquals(UvcCodec.Unknown, UvcCodec.fromCode(7))
        assertEquals(UvcCodec.H264, UvcCodec.fromStoreName("H264"))
        assertEquals(UvcCodec.Unknown, UvcCodec.fromStoreName("VP8"))
    }

    @Test
    fun theFourCcIsFourAsciiCharactersHighByteFirst() {
        assertEquals("YUY2", FourCc.unpack(0x59555932))
        assertEquals("MJPG", FourCc.unpack(FourCc.pack("MJPG")))
        assertEquals("H264", FourCc.unpack(0x48323634))
        assertEquals("Y?~?", FourCc.unpack(0x59007EFF))
        assertEquals(0x59555932, FourCc.pack("YUY2"))
        assertEquals(0x41422020, FourCc.pack("AB"))
    }

    @Test
    fun theNativeModeListHasTheStatusThenFiveValuesForEachMode() {
        val reply = intArrayOf(
            0,
            0, 1920, 1080, 333_333, FourCc.pack("MJPG"),
            2, 640, 480, 666_666, FourCc.pack("YUY2"),
            1, 1280, 720, -1, FourCc.pack("H264"),
        )
        val list = UvcModeList.parse(reply)
        assertTrue(list.isSuccess)
        assertEquals(
            listOf(
                UvcMode(UvcCodec.Mjpeg, 1920, 1080, 333_333L, "MJPG"),
                UvcMode(UvcCodec.Uncompressed, 640, 480, 666_666L, "YUY2"),
                UvcMode(UvcCodec.H264, 1280, 720, 0xFFFF_FFFFL, "H264"),
            ),
            list.modes,
        )
        assertEquals(UvcModeList(-99, emptyList()), UvcModeList.parse(intArrayOf(-99, 0, 1, 2, 3, 4)))
        assertEquals(UvcModeList.NO_REPLY, UvcModeList.parse(null).status)
        assertEquals(UvcModeList.NO_REPLY, UvcModeList.parse(IntArray(0)).status)
    }

    @Test
    fun anEncodedListReadsBack() {
        val modes = listOf(
            UvcMode(UvcCodec.Mjpeg, 1280, 720, 333_333L, "MJPG"),
            UvcMode(UvcCodec.H264, 1920, 1080, 400_000L, "H264"),
        )
        assertEquals(modes, UvcModeList.parse(UvcModeList.encode(modes)).modes)
        val many = List(UvcModeList.MAX_MODES + 3) { index -> UvcMode(UvcCodec.Mjpeg, 100 + index, 100, 333_333L, "MJPG") }
        assertEquals(UvcModeList.MAX_MODES, UvcModeList.parse(UvcModeList.encode(many)).modes.size)
    }
}
