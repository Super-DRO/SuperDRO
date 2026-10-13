package org.superdro.core.ui.graphics.image

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/** The image code of the JVM: `ImageIO` for the decode and the JPEG encode, Skia for the canvas. */
actual object PlatformImages {

    actual fun decode(bytes: ByteArray, maxSide: Int): RasterImage? {
        val source = try {
            ImageIO.read(ByteArrayInputStream(bytes))
        } catch (_: Exception) {
            null
        } ?: return null
        val longSide = maxOf(source.width, source.height)
        val image = if (longSide > maxSide) scaled(source, maxSide) else source
        val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        return RasterImage(image.width, image.height, pixels)
    }

    actual fun encodeJpeg(image: RasterImage, quality: Int): ByteArray {
        val rgb = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
        rgb.setRGB(0, 0, image.width, image.height, image.argb, 0, image.width)
        val writer = ImageIO.getImageWritersByFormatName("jpg").next()
        val output = ByteArrayOutputStream()
        try {
            ImageIO.createImageOutputStream(output).use { stream ->
                writer.output = stream
                val parameters = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = quality.coerceIn(0, 100) / 100f
                }
                writer.write(null, IIOImage(rgb, null, null), parameters)
            }
        } finally {
            writer.dispose()
        }
        return output.toByteArray()
    }

    actual fun toImageBitmap(image: RasterImage): ImageBitmap {
        val bytes = ByteArray(image.argb.size * 4)
        image.argb.forEachIndexed { index, argb ->
            // BGRA_8888 in memory: blue, green, red, alpha.
            bytes[index * 4] = (argb and 0xFF).toByte()
            bytes[index * 4 + 1] = (argb ushr 8 and 0xFF).toByte()
            bytes[index * 4 + 2] = (argb ushr 16 and 0xFF).toByte()
            bytes[index * 4 + 3] = (argb ushr 24 and 0xFF).toByte()
        }
        val info = ImageInfo(image.width, image.height, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL)
        return Image.makeRaster(info, bytes, image.width * 4).toComposeImageBitmap()
    }

    private fun scaled(source: BufferedImage, maxSide: Int): BufferedImage {
        val factor = maxSide.toDouble() / maxOf(source.width, source.height)
        val width = maxOf(1, (source.width * factor).toInt())
        val height = maxOf(1, (source.height * factor).toInt())
        val target = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = target.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.drawImage(source, 0, 0, width, height, null)
        } finally {
            graphics.dispose()
        }
        return target
    }
}
