package org.superdro.core.ui.graphics.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayOutputStream
import org.superdro.core.functions.graphics.ReferenceImageMath

/**
 * The image code of Android: `BitmapFactory` with a sample size for the decode,
 * `Bitmap.compress` for the JPEG, and `asImageBitmap` for the canvas.
 */
internal actual fun decodePlatformImage(bytes: ByteArray, maxSide: Int): RasterImage? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = ReferenceImageMath.decodeSampleSize(bounds.outWidth, bounds.outHeight, maxSide)
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
    val longSide = maxOf(decoded.width, decoded.height)
    val bitmap = if (longSide > maxSide) {
        val factor = maxSide.toDouble() / longSide
        val width = maxOf(1, (decoded.width * factor).toInt())
        val height = maxOf(1, (decoded.height * factor).toInt())
        Bitmap.createScaledBitmap(decoded, width, height, true).also { scaled -> if (scaled !== decoded) decoded.recycle() }
    } else {
        decoded
    }
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    val result = RasterImage(bitmap.width, bitmap.height, pixels)
    bitmap.recycle()
    return result
}

internal actual fun encodePlatformJpeg(image: RasterImage, quality: Int): ByteArray {
    val bitmap = Bitmap.createBitmap(image.argb, image.width, image.height, Bitmap.Config.ARGB_8888)
    val output = ByteArrayOutputStream()
    try {
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(0, 100), output)) { "The JPEG encode failed." }
    } finally {
        bitmap.recycle()
    }
    return output.toByteArray()
}

internal actual fun toPlatformImageBitmap(image: RasterImage): ImageBitmap =
    Bitmap.createBitmap(image.argb, image.width, image.height, Bitmap.Config.ARGB_8888).asImageBitmap()
