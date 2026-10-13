package org.superdro.core.functions.graphics

import kotlin.math.abs
import kotlin.math.max

/**
 * One reference card of the reference image setup.
 *
 * @property pixel The image pixel under the reticle, in image coordinates.
 * @property machine The machine values of the card in µm: x is the value of the horizontal axis
 *   of the plane and y is the value of the vertical axis, with no sign (X and Y on a mill, Z and
 *   X on a lathe).
 */
data class ImageReference(val pixel: Vec2, val machine: Vec2) {
    companion object {
        /** Gives the reference of a card with the card values [horizontalUm] and [verticalUm]. */
        fun fromCard(pixel: Vec2, horizontalUm: Double, verticalUm: Double): ImageReference =
            ImageReference(pixel, Vec2(horizontalUm, verticalUm))
    }
}

/** The reasons to refuse a set of reference points. */
enum class ImageFitRefusal {
    /** The count is 0, 1, or more than 4. Set two or four reference points. */
    WrongCount,

    /** Three references: set two or four reference points. Three cannot be fitted. */
    ThreeReferences,

    /** Two points are closer than 0.001 (image pixels or machine µm). */
    SamePosition,

    /** Two references with the same X or Y: the points must be diagonally apart. */
    NotDiagonal,

    /** Four references that cross over: a machine coordinate belongs to a different image point. */
    CrossedOver,

    /** The points give a singular matrix. */
    CannotFit,

    /** The projective weight changes sign between the image corners: the photo turns inside out. */
    InsideOut,

    /** The far edge resolves 2000 µm or more for each pixel. */
    TooOblique,
}

/** The quality of an accepted fit. */
enum class ImageFitQuality {
    /** Less than 600 µm for each pixel: no banner. */
    Good,

    /** 600 µm for each pixel or more: the banner "Steep angle". */
    SteepAngle,
}

/** The result of the reference image fit. */
sealed interface ImageFitResult {
    /**
     * The fit is accepted.
     *
     * @property matrix The map from image pixels to the machine values of the plane in µm.
     * @property umPerPixel The largest machine distance for one image pixel.
     */
    data class Fitted(val matrix: Matrix3, val quality: ImageFitQuality, val umPerPixel: Double) : ImageFitResult

    /** The fit is refused. [umPerPixel] has a value for [ImageFitRefusal.TooOblique]. */
    data class Refused(val reason: ImageFitRefusal, val umPerPixel: Double? = null) : ImageFitResult
}

/**
 * A change of the image pixels: rotate or flip. The [matrix] maps an old pixel position
 * to the new pixel position. The new image has the size [width] × [height].
 */
data class PixelTransform(val matrix: Matrix3, val width: Int, val height: Int)

/**
 * The reference image math.
 *
 * The stored matrix maps image pixels to the machine values of the plane in µm: (X, Y) on a mill in
 * the plane `XY`, and (Z, X) on a lathe. The matrix has no
 * vertical sign. The view applies the sign of the plane to the image as to all other layers
 * ([ViewTransform.imageToScreen]).
 */
object ReferenceImageMath {

    /** The minimum distance of two reference points, in image pixels or in µm. */
    const val SAME_POSITION_LIMIT: Double = 0.001

    /** The limit of the error "too oblique" in µm for each pixel. */
    const val TOO_OBLIQUE_UM_PER_PX: Double = 2000.0

    /** The limit of the banner "steep angle" in µm for each pixel. */
    const val STEEP_ANGLE_UM_PER_PX: Double = 600.0

    /** The alpha of a new reference image. */
    const val START_ALPHA: Int = 255

    /** The largest side of a decoded image, in pixels. */
    const val DECODE_LIMIT_PX: Int = 4096

    /** The JPEG quality of the saved image. */
    const val JPEG_QUALITY: Int = 100

    /**
     * Gives the image pixel under the fixed reticle at the view center, in image coordinates.
     *
     * The setup view shows the content: the image through [contentMatrix]. At the pan (0, 0),
     * the image center is at the view center. The pan moves with the finger, and the zoom
     * [scale] is about the view center. The content point under the reticle is
     * `q = center − pan / scale`. With the identity matrix, this is
     * `p = (W/2 − panX / scale, H/2 − panY / scale)`. In preview mode, [contentMatrix] is the
     * preview matrix, and the function gives the pixel through the inverse matrix.
     *
     * Gives null when [contentMatrix] has no inverse or [scale] is not more than 0.
     */
    fun reticlePixel(
        imageWidth: Double,
        imageHeight: Double,
        panX: Double,
        panY: Double,
        scale: Double,
        contentMatrix: Matrix3 = Matrix3.IDENTITY,
    ): Vec2? {
        if (!(scale > 0.0)) return null
        val center = contentMatrix.map(imageWidth / 2.0, imageHeight / 2.0)
        val q = Vec2(center.x - panX / scale, center.y - panY / scale)
        if (contentMatrix == Matrix3.IDENTITY) return q
        val inverse = contentMatrix.inverse() ?: return null
        val pixel = inverse.map(q)
        return if (pixel.x.isFinite() && pixel.y.isFinite()) pixel else null
    }

    /**
     * Gives the 4 references of a 2-point set: the two points and the two other corners of the
     * rectangle. The image point `p1.x, p2.y` goes to the machine point `m1.x, m2.y`. The image
     * point `p2.x, p1.y` goes to the machine point `m2.x, m1.y`. The map has no rotation.
     */
    fun rectangleCorners(first: ImageReference, second: ImageReference): List<ImageReference> = listOf(
        first,
        second,
        ImageReference(Vec2(first.pixel.x, second.pixel.y), Vec2(first.machine.x, second.machine.y)),
        ImageReference(Vec2(second.pixel.x, first.pixel.y), Vec2(second.machine.x, first.machine.y)),
    )

    /**
     * Gives the matrix of the references, or a refusal. The checks come in
     * this order:
     *
     * 1. The count.
     * 2. The same position.
     * 3. The diagonal test (2 points).
     * 4. The cross-over test (4 points).
     * 5. The singular test.
     * 6. The quality of the map of the image corners.
     */
    fun fit(references: List<ImageReference>, imageWidth: Double, imageHeight: Double): ImageFitResult {
        when (references.size) {
            2, 4 -> Unit
            3 -> return ImageFitResult.Refused(ImageFitRefusal.ThreeReferences)
            else -> return ImageFitResult.Refused(ImageFitRefusal.WrongCount)
        }
        for (i in references.indices) {
            for (j in i + 1 until references.size) {
                val a = references[i]
                val b = references[j]
                if (a.pixel.distanceTo(b.pixel) < SAME_POSITION_LIMIT || a.machine.distanceTo(b.machine) < SAME_POSITION_LIMIT) {
                    return ImageFitResult.Refused(ImageFitRefusal.SamePosition)
                }
            }
        }
        val matrix = if (references.size == 2) {
            val (first, second) = references
            if (abs(first.pixel.x - second.pixel.x) < SAME_POSITION_LIMIT ||
                abs(first.pixel.y - second.pixel.y) < SAME_POSITION_LIMIT ||
                abs(first.machine.x - second.machine.x) < SAME_POSITION_LIMIT ||
                abs(first.machine.y - second.machine.y) < SAME_POSITION_LIMIT
            ) {
                return ImageFitResult.Refused(ImageFitRefusal.NotDiagonal)
            }
            rectangleMatrix(first, second)
        } else {
            val pixels = references.map { reference -> reference.pixel }
            val machines = references.map { reference -> reference.machine }
            when (crossCheck(pixels, machines)) {
                CrossCheck.Degenerate -> return ImageFitResult.Refused(ImageFitRefusal.CannotFit)
                CrossCheck.Crossed -> return ImageFitResult.Refused(ImageFitRefusal.CrossedOver)
                CrossCheck.Valid -> Unit
            }
            Homography.solve(pixels, machines) ?: return ImageFitResult.Refused(ImageFitRefusal.CannotFit)
        }
        return quality(matrix, imageWidth, imageHeight)
    }

    /**
     * Gives the quality of [matrix] for an image of [imageWidth] × [imageHeight] pixels. The
     * function maps the four image corners and finds the largest machine distance for one image
     * pixel at a corner.
     */
    fun quality(matrix: Matrix3, imageWidth: Double, imageHeight: Double): ImageFitResult {
        val corners = listOf(
            Vec2(0.0, 0.0),
            Vec2(imageWidth, 0.0),
            Vec2(imageWidth, imageHeight),
            Vec2(0.0, imageHeight),
        )
        val weights = corners.map { corner -> matrix.weight(corner.x, corner.y) }
        if (weights.any { weight -> !(weight > 0.0) } && weights.any { weight -> !(weight < 0.0) }) {
            return ImageFitResult.Refused(ImageFitRefusal.InsideOut)
        }
        var umPerPixel = 0.0
        for (corner in corners) {
            val stepX = if (corner.x > 0.0) -1.0 else 1.0
            val stepY = if (corner.y > 0.0) -1.0 else 1.0
            val origin = matrix.map(corner)
            umPerPixel = max(umPerPixel, origin.distanceTo(matrix.map(corner.x + stepX, corner.y)))
            umPerPixel = max(umPerPixel, origin.distanceTo(matrix.map(corner.x, corner.y + stepY)))
        }
        if (!umPerPixel.isFinite()) return ImageFitResult.Refused(ImageFitRefusal.CannotFit)
        return when {
            umPerPixel >= TOO_OBLIQUE_UM_PER_PX -> ImageFitResult.Refused(ImageFitRefusal.TooOblique, umPerPixel)
            umPerPixel >= STEEP_ANGLE_UM_PER_PX -> ImageFitResult.Fitted(matrix, ImageFitQuality.SteepAngle, umPerPixel)
            else -> ImageFitResult.Fitted(matrix, ImageFitQuality.Good, umPerPixel)
        }
    }

    /**
     * Gives the preview matrix of the setup screen: the
     * fit [matrix] times `k = max(|Δx_px| / |Δx_µm|, |Δy_px| / |Δy_µm|)` of the first two
     * references, with the y axis turned over. This keeps the corrected image near its pixel size.
     */
    fun previewMatrix(matrix: Matrix3, references: List<ImageReference>): Matrix3 {
        var k = 0.0
        if (references.size >= 2) {
            val (first, second) = references
            val dxPx = abs(first.pixel.x - second.pixel.x)
            val dyPx = abs(first.pixel.y - second.pixel.y)
            val dxUm = abs(first.machine.x - second.machine.x)
            val dyUm = abs(first.machine.y - second.machine.y)
            if (dxUm > 0.0) k = max(k, dxPx / dxUm)
            if (dyUm > 0.0) k = max(k, dyPx / dyUm)
        }
        if (!(k > 0.0) || !k.isFinite()) k = 1.0
        return Matrix3.scale(k, -k) * matrix
    }

    /** Gives the next alpha of the opacity button: 0 → 255 → 127 → 0. */
    fun nextAlpha(alpha: Int): Int = if (alpha > 0) max(0, alpha - 128) else 255

    /** "Flip horizontal": left and right change places. */
    fun flipHorizontal(width: Int, height: Int): PixelTransform =
        PixelTransform(Matrix3(-1.0, 0.0, width.toDouble(), 0.0, 1.0, 0.0, 0.0, 0.0, 1.0), width, height)

    /** "Flip vertical": top and bottom change places. */
    fun flipVertical(width: Int, height: Int): PixelTransform =
        PixelTransform(Matrix3(1.0, 0.0, 0.0, 0.0, -1.0, height.toDouble(), 0.0, 0.0, 1.0), width, height)

    /** "Rotate right": 90° clockwise on the screen about the center. The new size is [height] × [width]. */
    fun rotateRight(width: Int, height: Int): PixelTransform =
        PixelTransform(Matrix3(0.0, -1.0, height.toDouble(), 1.0, 0.0, 0.0, 0.0, 0.0, 1.0), height, width)

    /** "Rotate left": 90° counterclockwise on the screen about the center. The new size is [height] × [width]. */
    fun rotateLeft(width: Int, height: Int): PixelTransform =
        PixelTransform(Matrix3(0.0, 1.0, 0.0, -1.0, 0.0, width.toDouble(), 0.0, 0.0, 1.0), height, width)

    /**
     * Gives the sample size for the decode of an image of [width] × [height] pixels. The
     * value is the smallest power of 2 that makes the larger side [limitPx] or less. Android
     * `BitmapFactory.Options.inSampleSize` takes this value.
     */
    fun decodeSampleSize(width: Int, height: Int, limitPx: Int = DECODE_LIMIT_PX): Int {
        require(limitPx > 0) { "The limit must be more than 0" }
        val longSide = max(width, height)
        var sample = 1
        while ((longSide + sample - 1) / sample > limitPx && sample < (1 shl 30)) sample *= 2
        return sample
    }

    private fun rectangleMatrix(first: ImageReference, second: ImageReference): Matrix3 {
        val sx = (second.machine.x - first.machine.x) / (second.pixel.x - first.pixel.x)
        val sy = (second.machine.y - first.machine.y) / (second.pixel.y - first.pixel.y)
        return Matrix3(
            sx, 0.0, first.machine.x - sx * first.pixel.x,
            0.0, sy, first.machine.y - sy * first.pixel.y,
            0.0, 0.0, 1.0,
        )
    }

    private enum class CrossCheck { Valid, Crossed, Degenerate }

    /**
     * Compares the orientation of each triangle of the 4 points in the image and on the machine. A
     * valid projective map keeps all orientations, or turns all of them over (a mirror). A
     * triangle with no area makes the set degenerate.
     */
    private fun crossCheck(pixels: List<Vec2>, machines: List<Vec2>): CrossCheck {
        val triangles = listOf(intArrayOf(0, 1, 2), intArrayOf(0, 1, 3), intArrayOf(0, 2, 3), intArrayOf(1, 2, 3))
        val pixelLimit = areaLimit(pixels)
        val machineLimit = areaLimit(machines)
        var sign = 0
        for (triangle in triangles) {
            val pixelArea = area(pixels, triangle)
            val machineArea = area(machines, triangle)
            if (abs(pixelArea) <= pixelLimit || abs(machineArea) <= machineLimit) return CrossCheck.Degenerate
            val product = if ((pixelArea > 0.0) == (machineArea > 0.0)) 1 else -1
            if (sign == 0) sign = product else if (sign != product) return CrossCheck.Crossed
        }
        return CrossCheck.Valid
    }

    private fun area(points: List<Vec2>, triangle: IntArray): Double {
        val a = points[triangle[0]]
        return (points[triangle[1]] - a).cross(points[triangle[2]] - a)
    }

    private fun areaLimit(points: List<Vec2>): Double {
        val width = points.maxOf { point -> point.x } - points.minOf { point -> point.x }
        val height = points.maxOf { point -> point.y } - points.minOf { point -> point.y }
        val extent = max(width, height)
        return 1e-9 * extent * extent
    }
}
