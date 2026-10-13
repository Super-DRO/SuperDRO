package org.superdro.core.functions.graphics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.superdro.core.model.ReferenceImage

/** The reference image math. */
class ReferenceImageTest {

    private fun reference(px: Double, py: Double, h: Double, v: Double) = ImageReference(Vec2(px, py), Vec2(h, v))

    @Test
    fun theReticlePixelIsInImageCoordinates() {
        // p = (W/2 − panX / scale, H/2 − panY / scale).
        assertEquals(Vec2(1900.0, 1550.0), ReferenceImageMath.reticlePixel(4000.0, 3000.0, panX = 200.0, panY = -100.0, scale = 2.0))
        assertEquals(Vec2(2000.0, 1500.0), ReferenceImageMath.reticlePixel(4000.0, 3000.0, 0.0, 0.0, 0.5))
    }

    @Test
    fun theReticlePixelInPreviewModeIsAlsoInImageCoordinates() {
        val preview = Matrix3.scale(2.0, -2.0)
        val pixel = ReferenceImageMath.reticlePixel(4000.0, 3000.0, panX = 200.0, panY = -100.0, scale = 2.0, contentMatrix = preview)
        // The content point (4000 − 100, −3000 + 50) goes back through the preview matrix.
        assertEquals(Vec2(1950.0, 1475.0), pixel)
        assertEquals(Vec2(2000.0, 1500.0), ReferenceImageMath.reticlePixel(4000.0, 3000.0, 0.0, 0.0, 1.0, preview))
        assertNull(ReferenceImageMath.reticlePixel(4000.0, 3000.0, 0.0, 0.0, 1.0, Matrix3.scale(0.0, 1.0)))
    }

    @Test
    fun twoPointsGiveARectangleMap() {
        val first = reference(100.0, 200.0, 0.0, 50_000.0)
        val second = reference(1100.0, 1200.0, 100_000.0, -50_000.0)
        val fit = assertIs<ImageFitResult.Fitted>(ReferenceImageMath.fit(listOf(first, second), 1200.0, 1400.0))
        assertEquals(Matrix3(100.0, 0.0, -10_000.0, 0.0, -100.0, 70_000.0, 0.0, 0.0, 1.0), fit.matrix)
        // The two other corners of the rectangle.
        val corners = ReferenceImageMath.rectangleCorners(first, second)
        assertEquals(reference(100.0, 1200.0, 0.0, -50_000.0), corners[2])
        assertEquals(reference(1100.0, 200.0, 100_000.0, 50_000.0), corners[3])
        for (corner in corners) assertNear(corner.machine, fit.matrix.map(corner.pixel), 1e-9)
        // The projective map of the four corners is the same map: no rotation.
        val homography = Homography.solve(corners.map { corner -> corner.pixel }, corners.map { corner -> corner.machine })
        assertNear(fit.matrix, homography, 1e-9)
        assertEquals(ImageFitQuality.Good, fit.quality)
        assertEquals(100.0, fit.umPerPixel, 1e-9)
    }

    @Test
    fun fourPointsGiveAProjectiveMap() {
        val truth = Matrix3(50.0, 5.0, 1000.0, -3.0, -48.0, 60_000.0, 0.00001, 0.00002, 1.0)
        val pixels = listOf(Vec2(100.0, 100.0), Vec2(1900.0, 150.0), Vec2(1800.0, 1400.0), Vec2(200.0, 1300.0))
        val references = pixels.map { pixel -> ImageReference(pixel, truth.map(pixel)) }
        val fit = assertIs<ImageFitResult.Fitted>(ReferenceImageMath.fit(references, 2000.0, 1500.0))
        for (reference in references) assertNear(reference.machine, fit.matrix.map(reference.pixel), 1e-6)
        assertNear(truth, fit.matrix, 1e-9)
        // A fifth point maps as the true map gives it.
        assertNear(truth.map(Vec2(1000.0, 700.0)), fit.matrix.map(Vec2(1000.0, 700.0)), 1e-6)
        assertEquals(ImageFitQuality.Good, fit.quality)
    }

    @Test
    fun aSingularSetIsRefused() {
        val collinear = listOf(
            reference(0.0, 0.0, 0.0, 0.0),
            reference(100.0, 0.0, 10_000.0, 0.0),
            reference(200.0, 0.0, 20_000.0, 0.0),
            reference(0.0, 100.0, 0.0, -10_000.0),
        )
        assertEquals(ImageFitResult.Refused(ImageFitRefusal.CannotFit), ReferenceImageMath.fit(collinear, 1000.0, 1000.0))
        assertNull(Homography.solve(collinear.map { reference -> reference.pixel }, collinear.map { reference -> reference.machine }))
    }

    @Test
    fun theFitChecksRefuseBadReferenceSets() {
        val a = reference(0.0, 0.0, 0.0, 0.0)
        val b = reference(100.0, 100.0, 10_000.0, -10_000.0)
        val c = reference(100.0, 0.0, 10_000.0, 0.0)
        assertEquals(ImageFitResult.Refused(ImageFitRefusal.WrongCount), ReferenceImageMath.fit(listOf(a), 100.0, 100.0))
        assertEquals(ImageFitResult.Refused(ImageFitRefusal.ThreeReferences), ReferenceImageMath.fit(listOf(a, b, c), 100.0, 100.0))
        val same = reference(0.0005, 0.0, 5_000.0, 5_000.0)
        assertEquals(ImageFitResult.Refused(ImageFitRefusal.SamePosition), ReferenceImageMath.fit(listOf(a, same), 100.0, 100.0))
        val sameMachine = reference(50.0, 50.0, 0.0, 0.0005)
        assertEquals(ImageFitResult.Refused(ImageFitRefusal.SamePosition), ReferenceImageMath.fit(listOf(a, sameMachine), 100.0, 100.0))
        assertEquals(ImageFitResult.Refused(ImageFitRefusal.NotDiagonal), ReferenceImageMath.fit(listOf(a, c), 100.0, 100.0))
        // The machine values of references 3 and 4 change places: the set crosses over.
        val crossed = listOf(
            reference(0.0, 0.0, 0.0, 0.0),
            reference(100.0, 0.0, 10_000.0, 0.0),
            reference(100.0, 100.0, 0.0, -10_000.0),
            reference(0.0, 100.0, 10_000.0, -10_000.0),
        )
        assertEquals(ImageFitResult.Refused(ImageFitRefusal.CrossedOver), ReferenceImageMath.fit(crossed, 100.0, 100.0))
        val square = listOf(
            reference(0.0, 0.0, 0.0, 0.0),
            reference(100.0, 0.0, 10_000.0, 0.0),
            reference(100.0, 100.0, 10_000.0, -10_000.0),
            reference(0.0, 100.0, 0.0, -10_000.0),
        )
        assertIs<ImageFitResult.Fitted>(ReferenceImageMath.fit(square, 100.0, 100.0))
    }

    @Test
    fun theQualityBannerAndTheObliqueLimit() {
        val origin = reference(0.0, 0.0, 0.0, 0.0)
        val steep = assertIs<ImageFitResult.Fitted>(
            ReferenceImageMath.fit(listOf(origin, reference(100.0, 100.0, 60_000.0, -60_000.0)), 100.0, 100.0),
        )
        assertEquals(ImageFitQuality.SteepAngle, steep.quality)
        assertEquals(600.0, steep.umPerPixel, 1e-9)
        val good = assertIs<ImageFitResult.Fitted>(
            ReferenceImageMath.fit(listOf(origin, reference(100.0, 100.0, 59_999.0, -59_999.0)), 100.0, 100.0),
        )
        assertEquals(ImageFitQuality.Good, good.quality)
        val oblique = ReferenceImageMath.fit(listOf(origin, reference(100.0, 100.0, 250_000.0, -250_000.0)), 100.0, 100.0)
        assertEquals(ImageFitResult.Refused(ImageFitRefusal.TooOblique, 2500.0), oblique)
        val limit = ReferenceImageMath.fit(listOf(origin, reference(100.0, 100.0, 200_000.0, -200_000.0)), 100.0, 100.0)
        assertEquals(ImageFitRefusal.TooOblique, assertIs<ImageFitResult.Refused>(limit).reason)
    }

    @Test
    fun aWeightThatChangesSignBetweenTheCornersTurnsThePhotoInsideOut() {
        val matrix = Matrix3(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, -0.001, 0.0, 1.0)
        assertEquals(ImageFitResult.Refused(ImageFitRefusal.InsideOut), ReferenceImageMath.quality(matrix, 2000.0, 1000.0))
        assertIs<ImageFitResult.Fitted>(ReferenceImageMath.quality(matrix, 900.0, 1000.0))
    }

    @Test
    fun theOpacityCyclesFrom0To255To127To0AndANewImageStartsAt255() {
        assertEquals(255, ReferenceImageMath.nextAlpha(0))
        assertEquals(127, ReferenceImageMath.nextAlpha(255))
        assertEquals(0, ReferenceImageMath.nextAlpha(127))
        assertEquals(255, ReferenceImageMath.START_ALPHA)
        assertEquals(255, ReferenceImage().alpha)
    }

    @Test
    fun theFlipNamesAgreeWithTheirMatrices() {
        // "Flip horizontal": left and right change places.
        val horizontal = ReferenceImageMath.flipHorizontal(400, 300)
        assertEquals(Vec2(390.0, 20.0), horizontal.matrix.map(10.0, 20.0))
        assertEquals(400 to 300, horizontal.width to horizontal.height)
        // "Flip vertical": top and bottom change places.
        val vertical = ReferenceImageMath.flipVertical(400, 300)
        assertEquals(Vec2(10.0, 280.0), vertical.matrix.map(10.0, 20.0))
    }

    @Test
    fun rotateLeftAndRightTurnAboutTheCenter() {
        val right = ReferenceImageMath.rotateRight(400, 300)
        assertEquals(300 to 400, right.width to right.height)
        // The top left corner goes to the top right corner.
        assertEquals(Vec2(300.0, 0.0), right.matrix.map(0.0, 0.0))
        assertEquals(Vec2(0.0, 400.0), right.matrix.map(400.0, 300.0))
        val left = ReferenceImageMath.rotateLeft(400, 300)
        // The top left corner goes to the bottom left corner.
        assertEquals(Vec2(0.0, 400.0), left.matrix.map(0.0, 0.0))
        // Rotate right, then rotate left on the new size, gives each pixel back.
        val back = ReferenceImageMath.rotateLeft(right.width, right.height).matrix * right.matrix
        assertNear(Vec2(123.0, 45.0), back.map(123.0, 45.0), 0.0)
    }

    @Test
    fun theInvertMatrixAndTheGrayMatrix() {
        assertEquals(
            listOf(-1.0, 0.0, 0.0, 0.0, 255.0, 0.0, -1.0, 0.0, 0.0, 255.0, 0.0, 0.0, -1.0, 0.0, 255.0, 0.0, 0.0, 0.0, 1.0, 0.0),
            ColorMatrix.INVERT.values,
        )
        assertEquals(0xFFEFDFCF.toInt(), ColorMatrix.INVERT.apply(0xFF102030.toInt()))
        val gray = ColorMatrix.gray(ContrastPreservingGray.BT601)
        assertEquals(0xFF969696.toInt(), gray.apply(0xFF808080.toInt() + 0x161616))
        // The order is invert, then gray: white becomes black.
        assertEquals(0xFF000000.toInt(), ColorMatrix.process(invert = true, grayWeights = ContrastPreservingGray.BT601).apply(0xFFFFFFFF.toInt()))
        assertEquals(0x80FFFFFF.toInt(), ColorMatrix.process(invert = false, grayWeights = null).apply(0x80FFFFFF.toInt()))
    }

    @Test
    fun theSampleSizeKeepsTheLongSideAt4096PxOrLess() {
        assertEquals(1, ReferenceImageMath.decodeSampleSize(4000, 3000))
        assertEquals(1, ReferenceImageMath.decodeSampleSize(4096, 100))
        assertEquals(2, ReferenceImageMath.decodeSampleSize(4097, 100))
        assertEquals(2, ReferenceImageMath.decodeSampleSize(6000, 8192))
        assertEquals(4, ReferenceImageMath.decodeSampleSize(12_000, 9_000))
    }

    @Test
    fun aUniformImageFallsBackToBt601() {
        val pixels = IntArray(256 * 256) { 0xFF3366CC.toInt() }
        val result = ContrastPreservingGray.weights(pixels, 256, 256)
        assertEquals(GrayWeightResult(GrayWeights(0.299, 0.587, 0.114), false, 64, 64), result)
        assertEquals(GrayWeights(0.299, 0.587, 0.114), ContrastPreservingGray.weights(IntArray(0), 0, 0).weights)
    }

    @Test
    fun theThumbnailIsHalvedThenScaledTo64Px() {
        val (_, width, height) = ContrastPreservingGray.thumbnail(IntArray(1024 * 768), 1024, 768)
        assertEquals(64 to 48, width to height)
        val (_, oddWidth, oddHeight) = ContrastPreservingGray.thumbnail(IntArray(1001 * 500), 1001, 500)
        assertEquals(64 to 32, oddWidth to oddHeight)
        assertEquals(66, ContrastPreservingGray.CANDIDATES.size)
    }

    @Test
    fun theWeightSearchKeepsTheContrastOfRedAndGreen() {
        // The left half is red, the right half is green. BT.601 gives two near gray values.
        val pixels = IntArray(100 * 100) { index -> if (index % 100 < 50) 0xFFFF0000.toInt() else 0xFF00FF00.toInt() }
        val result = ContrastPreservingGray.weights(pixels, 100, 100)
        assertEquals(true, result.searched)
        assertEquals(GrayWeights(0.0, 1.0, 0.0), result.weights)
        val searched = ColorMatrix.gray(result.weights)
        val bt601 = ColorMatrix.gray(ContrastPreservingGray.BT601)
        fun level(matrix: ColorMatrix, argb: Int): Int = matrix.apply(argb) and 0xFF
        val searchedContrast = level(searched, 0xFF00FF00.toInt()) - level(searched, 0xFFFF0000.toInt())
        val bt601Contrast = level(bt601, 0xFF00FF00.toInt()) - level(bt601, 0xFFFF0000.toInt())
        assertEquals(255, searchedContrast)
        assertEquals(74, bt601Contrast)
    }
}
