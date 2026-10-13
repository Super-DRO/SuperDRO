package org.superdro.core.ui.graphics.image

import androidx.compose.ui.graphics.ImageBitmap
import kotlin.math.floor
import org.superdro.core.functions.graphics.ColorMatrix
import org.superdro.core.functions.graphics.GrayWeights
import org.superdro.core.functions.graphics.PixelTransform
import org.superdro.core.functions.graphics.ReferenceImageMath

/**
 * The pixels of a picture in memory: ARGB values row by row. The reference
 * image setup changes a copy of this object: rotate, flip, and the process of "Apply".
 */
class RasterImage(val width: Int, val height: Int, val argb: IntArray) {

    init {
        require(width > 0 && height > 0) { "An image needs a size of 1 × 1 or more, not $width × $height." }
        require(argb.size == width * height) { "The image needs ${width * height} pixels, not ${argb.size}." }
    }

    /** Gives the pixel at ([x], [y]). */
    fun pixel(x: Int, y: Int): Int = argb[y * width + x]

    /** The larger side in pixels. */
    val longSide: Int get() = maxOf(width, height)
}

/**
 * The image decode, the JPEG encode, and the image of the Compose canvas of the platform. Android
 * uses `BitmapFactory` and `Bitmap.compress`. The JVM uses `ImageIO` and Skia.
 */
expect object PlatformImages {

    /**
     * Decodes [bytes] (JPEG or PNG). The larger side of the result is [maxSide] or less: the
     * decode uses the sample size of [ReferenceImageMath.decodeSampleSize], then a scale when the
     * image is still too large. Gives null when the bytes are not an image.
     */
    fun decode(bytes: ByteArray, maxSide: Int = ReferenceImageMath.DECODE_LIMIT_PX): RasterImage?

    /** Encodes [image] as a JPEG with the quality [quality] (0 to 100). The alpha goes away. */
    fun encodeJpeg(image: RasterImage, quality: Int = ReferenceImageMath.JPEG_QUALITY): ByteArray

    /** Gives the image of [image] for the Compose canvas. */
    fun toImageBitmap(image: RasterImage): ImageBitmap
}

/** The pixel steps of the reference image setup in common code. */
object RasterImages {

    /**
     * Gives [image] after the pixel transform [transform]: rotate or flip. Each pixel of the
     * new image gets the old pixel under its center through the inverse matrix.
     */
    fun transform(image: RasterImage, transform: PixelTransform): RasterImage {
        val inverse = transform.matrix.inverse() ?: return image
        val width = transform.width
        val height = transform.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val old = inverse.map(x + 0.5, y + 0.5)
                val ox = floor(old.x).toInt().coerceIn(0, image.width - 1)
                val oy = floor(old.y).toInt().coerceIn(0, image.height - 1)
                pixels[y * width + x] = image.argb[oy * image.width + ox]
            }
        }
        return RasterImage(width, height, pixels)
    }

    /**
     * Gives [image] through the process matrix of the reference image: the invert matrix first
     * when [invert] is true, then the gray matrix of [grayWeights] when it is not null.
     * With no step, the function gives [image].
     */
    fun process(image: RasterImage, invert: Boolean, grayWeights: GrayWeights?): RasterImage {
        if (!invert && grayWeights == null) return image
        val matrix = ColorMatrix.process(invert, grayWeights)
        val pixels = IntArray(image.argb.size) { index -> matrix.apply(image.argb[index]) }
        return RasterImage(image.width, image.height, pixels)
    }

    /** Gives the 20 values of [matrix] for a Compose `ColorMatrix` (the offsets stay 0 to 255 on each platform). */
    fun composeColorMatrix(matrix: ColorMatrix): FloatArray = FloatArray(20) { index -> matrix.values[index].toFloat() }
}
