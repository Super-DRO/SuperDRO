package org.superdro.core.ui.graphics.image

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.superdro.core.functions.graphics.ContrastPreservingGray
import org.superdro.core.functions.graphics.ReferenceImageMath

/** The pixel steps and the platform image code of the reference image. */
class RasterImagesTest {

    private val a = 0xFF0000AA.toInt()
    private val b = 0xFF00BB00.toInt()
    private val c = 0xFFCC0000.toInt()

    @Test
    fun flipHorizontalChangesLeftAndRight() {
        val image = TestImages.raster(3, 1, a, b, c)
        val flipped = RasterImages.transform(image, ReferenceImageMath.flipHorizontal(3, 1))
        assertContentEquals(intArrayOf(c, b, a), flipped.argb)
    }

    @Test
    fun flipVerticalChangesTopAndBottom() {
        val image = TestImages.raster(1, 3, a, b, c)
        val flipped = RasterImages.transform(image, ReferenceImageMath.flipVertical(1, 3))
        assertContentEquals(intArrayOf(c, b, a), flipped.argb)
    }

    @Test
    fun rotateRightTurnsTheImageClockwise() {
        // A B          A
        //       →      B   (90° clockwise: the left pixel goes to the top)
        val image = TestImages.raster(2, 1, a, b)
        val right = RasterImages.transform(image, ReferenceImageMath.rotateRight(2, 1))
        assertEquals(1, right.width)
        assertEquals(2, right.height)
        assertContentEquals(intArrayOf(a, b), right.argb)
        val left = RasterImages.transform(image, ReferenceImageMath.rotateLeft(2, 1))
        assertContentEquals(intArrayOf(b, a), left.argb)
    }

    @Test
    fun theInvertMatrixGivesTheNegativeImage() {
        val image = TestImages.raster(1, 1, 0xFF102030.toInt())
        assertEquals(0xFFEFDFCF.toInt(), RasterImages.process(image, invert = true, grayWeights = null).argb[0])
        assertTrue(RasterImages.process(image, invert = false, grayWeights = null) === image)
    }

    @Test
    fun theGrayMatrixUsesTheSameWeightsOnTheThreeChannels() {
        val image = TestImages.raster(1, 1, 0xFF64C832.toInt())
        val gray = RasterImages.process(image, invert = false, grayWeights = ContrastPreservingGray.BT601).argb[0]
        val r = gray ushr 16 and 0xFF
        val g = gray ushr 8 and 0xFF
        val bl = gray and 0xFF
        // 0.299 × 100 + 0.587 × 200 + 0.114 × 50 = 153.0
        assertEquals(153, r)
        assertEquals(r, g)
        assertEquals(r, bl)
    }

    @Test
    fun theDecodeLimitsTheLongSideTo4096Pixels() {
        val image = assertNotNull(PlatformImages.decode(TestImages.solidPng(5000, 100, 0x336699)))
        assertTrue(image.longSide <= ReferenceImageMath.DECODE_LIMIT_PX, "long side ${image.longSide}")
        assertEquals(4096, image.width)
        assertTrue(image.height in 81..82)
        assertEquals(1, ReferenceImageMath.decodeSampleSize(4096, 100))
        assertEquals(2, ReferenceImageMath.decodeSampleSize(5000, 100))
    }

    @Test
    fun theJpegFileDecodesAgain() {
        val image = assertNotNull(PlatformImages.decode(TestImages.solidPng(64, 32, 0x336699)))
        val jpeg = PlatformImages.encodeJpeg(image, ReferenceImageMath.JPEG_QUALITY)
        assertEquals(0xFF.toByte(), jpeg[0])
        assertEquals(0xD8.toByte(), jpeg[1])
        val again = assertNotNull(PlatformImages.decode(jpeg))
        assertEquals(64, again.width)
        assertEquals(32, again.height)
        val pixel = again.pixel(10, 10)
        assertTrue(kotlin.math.abs((pixel ushr 16 and 0xFF) - 0x33) <= 2)
        assertTrue(kotlin.math.abs((pixel and 0xFF) - 0x99) <= 2)
        assertNull(PlatformImages.decode("not an image".encodeToByteArray()))
    }

    @Test
    fun theImageBitmapHasTheSizeOfTheImage() {
        val bitmap = PlatformImages.toImageBitmap(TestImages.raster(2, 1, a, b))
        assertEquals(2, bitmap.width)
        assertEquals(1, bitmap.height)
    }
}
