package org.superdro.core.ui.graphics.image

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** Pictures for the tests of the reference image. */
object TestImages {

    /** Gives a PNG file of [width] × [height] pixels with a grid of [cell] pixels and a red left column. */
    fun gridPng(width: Int, height: Int, cell: Int = 50): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = Color(0xE8, 0xE4, 0xD8)
            graphics.fillRect(0, 0, width, height)
            graphics.color = Color(0x2E, 0x5C, 0x8A)
            var x = 0
            while (x < width) {
                graphics.fillRect(x, 0, 2, height)
                x += cell
            }
            var y = 0
            while (y < height) {
                graphics.fillRect(0, y, width, 2)
                y += cell
            }
            graphics.color = Color(0xC0, 0x30, 0x30)
            graphics.fillOval(width / 4 - 12, height / 4 - 12, 24, 24)
            graphics.fillOval(3 * width / 4 - 12, 3 * height / 4 - 12, 24, 24)
        } finally {
            graphics.dispose()
        }
        return png(image)
    }

    /** Gives a PNG file of one color. */
    fun solidPng(width: Int, height: Int, rgb: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, rgb)
        return png(image)
    }

    private fun png(image: BufferedImage): ByteArray = ByteArrayOutputStream().also { out -> ImageIO.write(image, "png", out) }.toByteArray()

    /** Gives an image in memory of [width] × [height] with the ARGB values [pixels]. */
    fun raster(width: Int, height: Int, vararg pixels: Int): RasterImage = RasterImage(width, height, pixels)
}
